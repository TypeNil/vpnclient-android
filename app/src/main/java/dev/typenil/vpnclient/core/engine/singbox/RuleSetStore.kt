package dev.typenil.vpnclient.core.engine.singbox

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.typenil.vpnclient.core.engine.EngineError
import dev.typenil.vpnclient.core.engine.RouteMode
import dev.typenil.vpnclient.core.common.log.SecureLog
import io.nekohasekai.libbox.Libbox
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.Inflater

/**
 * App-side store for sing-box binary rule sets (.srs). Remote rule sets are
 * fetched synchronously inside `BoxService.start` — a connect on a network
 * where the CDN is unreachable (or slow, or censored) then fails the whole
 * engine start with a generic error. Instead we download over OkHttp ahead
 * of compile, keep files under `filesDir` (unlike `cacheDir` the system
 * can't evict them), and emit `type: "local"` rule sets — the engine's
 * start path stays fully offline once a copy exists.
 *
 * Integrity: upstream publishes no checksums, so the guarantees are a
 * bundled baseline in `assets/rule_sets/` (seeds a missing file, so a
 * fresh install connects offline), a size cap + `SRS\x01` magic check on
 * every downloaded and stored file (a captive-portal 200 or a truncated
 * copy can't poison the store), atomic installs (a partial file never
 * reaches the final name), and last-known-good fallback — a failed
 * refresh never destroys a working copy.
 */
@Singleton
class RuleSetStore(
    context: Context,
    private val client: OkHttpClient,
    private val bundled: BundledRuleSets,
    private val coreValidator: CoreValidator = LibboxCoreValidator,
    private val blockingDownloadTimeoutMs: Long = BLOCKING_DOWNLOAD_TIMEOUT_MS,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        client: OkHttpClient,
        bundled: BundledRuleSets,
    ) : this(
        context = context,
        client = client,
        bundled = bundled,
        coreValidator = LibboxCoreValidator,
    )
    private val dir = File(context.filesDir, "rule_sets")

    /** One mutex per tag: concurrent ensureReady calls for the same tag
     *  serialize instead of racing a shared .tmp file. Tags come from the
     *  RouteMode enum — the map stays bounded. */
    private val tagLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    /** Absolute .srs path per tag required by [mode]; downloads or
     *  revalidates as needed. Empty for modes without rule sets. Tags are
     *  handled in parallel — a blackholed CDN must not multiply the call
     *  timeout by the tag count on the connect path. */
    suspend fun ensureReady(mode: RouteMode): Map<String, String> =
        withContext(Dispatchers.IO) {
            if (mode.ruleSetTags.isEmpty()) return@withContext emptyMap()
            dir.mkdirs()
            mode.ruleSetTags.map { tag ->
                async {
                    tag to tagLocks.getOrPut(tag) { Mutex() }.withLock {
                        ensureFile(tag).absolutePath
                    }
                }
            }.awaitAll().toMap()
        }

    private val refreshing = AtomicBoolean(false)

    private val validatedCache = ConcurrentHashMap<String, Boolean>()

    private suspend fun ensureFile(tag: String): File {
        val target = File(dir, "$tag.srs")
        // A valid copy is used as-is, however old: age only schedules a
        // background refresh ([refreshStale]) and must never stall a connect
        // on a CDN that may be blackholed.
        if (isValidSrs(target)) return target
        // Seed from the bundled baseline — a fresh install connects offline.
        // A present-but-corrupt file is not a seed blocker — seeding
        // overwrites it atomically.
        if (seedFromBundle(tag, target)) return target
        // No usable copy and no baseline: the only connect-path download.
        // Bounded, and cancellable through the caller's job.
        try {
            withTimeoutOrNull(blockingDownloadTimeoutMs) {
                download(tag) { tmp -> place(tmp, target) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // fall through to the typed failure below
        }
        if (isValidSrs(target)) return target
        throw EngineError.StartFailed("routing lists unavailable")
    }

    /** Refreshes copies older than [STALE_MS] from the CDN — meant for the
     *  background once a tunnel is up, not for the connect path. Overlapping
     *  calls collapse into one. Each file is validated before it replaces
     *  the working copy, so a failure of any kind keeps last-known-good, and
     *  the network wait holds no tag lock (a connect never queues behind it).
     *  The new file is picked up by the next connect. */
    suspend fun refreshStale(mode: RouteMode) {
        if (mode.ruleSetTags.isEmpty() || !refreshing.compareAndSet(false, true)) return
        try {
            withContext(Dispatchers.IO) {
                for (tag in mode.ruleSetTags) {
                    val target = File(dir, "$tag.srs")
                    // Missing/corrupt copies are ensureReady's job.
                    if (!isValidSrs(target) ||
                        System.currentTimeMillis() - target.lastModified() < STALE_MS
                    ) {
                        continue
                    }
                    try {
                        download(tag) { tmp ->
                            tagLocks.getOrPut(tag) { Mutex() }.withLock { place(tmp, target) }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Class name only: messages can carry URLs.
                        SecureLog.w(TAG, "rule set refresh failed: ${e.javaClass.simpleName}")
                    }
                }
            }
        } finally {
            refreshing.set(false)
        }
    }

    /** A stored file is usable only when it starts with the .srs
     *  magic ("SRS\x01"), its zlib-compressed payload is intact and within
     *  size bounds, its decompressed header conforms to the sing-box binary
     *  schema, and the core can decode it. A truncated, poisoned, or
     *  corrupt file must never pass as fresh or replace last-known-good. */
    private fun isValidSrs(file: File): Boolean {
        if (!file.isFile || file.length() < SRS_MAGIC.size + 4 || file.length() > MAX_RULE_SET_BYTES) return false
        val cacheKey = "${file.absolutePath}:${file.length()}:${file.lastModified()}"
        if (validatedCache[cacheKey] == true) return true
        val structurallyValid = try {
            file.inputStream().use { raw ->
                val magic = ByteArray(SRS_MAGIC.size)
                if (raw.read(magic) != SRS_MAGIC.size || !magic.contentEquals(SRS_MAGIC)) {
                    return false
                }
                val inflater = Inflater(false)
                try {
                    val inputBuf = ByteArray(8192)
                    val outBuf = ByteArray(8192)
                    var decompressedBytes = 0L
                    val headerProbe = ByteArray(16)
                    var headerBytesRead = 0
                    while (true) {
                        val n = raw.read(inputBuf)
                        if (n < 0) break
                        inflater.setInput(inputBuf, 0, n)
                        while (!inflater.needsInput()) {
                            val count = inflater.inflate(outBuf)
                            if (count == 0) {
                                if (inflater.finished() || inflater.needsDictionary()) break
                            }
                            if (headerBytesRead < headerProbe.size) {
                                val toCopy = minOf(count, headerProbe.size - headerBytesRead)
                                System.arraycopy(outBuf, 0, headerProbe, headerBytesRead, toCopy)
                                headerBytesRead += toCopy
                            }
                            decompressedBytes += count
                            if (decompressedBytes > MAX_DECOMPRESSED_BYTES) {
                                return false // Decompression bomb defense
                            }
                        }
                    }
                    if (decompressedBytes == 0L || !inflater.finished()) return false
                    verifySrsHeader(headerProbe, headerBytesRead)
                } finally {
                    inflater.end()
                }
            }
        } catch (_: Exception) {
            false
        }
        if (!structurallyValid) return false
        val valid = coreValidator.validate(file)
        if (valid) {
            validatedCache[cacheKey] = true
        }
        return valid
    }

    /**
     * Inspects the decompressed stream header against the sing-box binary
     * rule-set specification (common/srs/binary.go):
     * - Rule count encoded as uvarint (must be > 0 and <= 100,000)
     * - First rule type must be 0 (default) or 1 (logical)
     * - For default rules, first item type must be 0..23 or 0xFF (ruleItemFinal)
     */
    private fun verifySrsHeader(bytes: ByteArray, length: Int): Boolean {
        if (length < 3) return false
        var offset = 0
        var ruleCount: Long = 0
        var shift = 0
        var foundVarint = false
        while (offset < length && offset < 10) {
            val b = bytes[offset++].toInt() and 0xFF
            ruleCount = ruleCount or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) {
                foundVarint = true
                break
            }
            shift += 7
        }
        if (!foundVarint || ruleCount < 1L || ruleCount > 100_000L) return false
        if (offset >= length) return false
        val ruleType = bytes[offset++].toInt() and 0xFF
        if (ruleType != 0 && ruleType != 1) return false
        if (ruleType == 0) {
            if (offset >= length) return false
            val firstItemType = bytes[offset].toInt() and 0xFF
            if (firstItemType !in 0..23 && firstItemType != 0xFF) return false
        }
        return true
    }

    fun interface CoreValidator {
        /** Return true if the core accepts and decodes [file] without error; false if rejected. */
        fun validate(file: File): Boolean
    }

    object LibboxCoreValidator : CoreValidator {
        override fun validate(file: File): Boolean {
            return try {
                val path = file.absolutePath.replace("\\", "\\\\").replace("\"", "\\\"")
                val probeConfig =
                    """{"outbounds":[{"type":"direct","tag":"direct"}],"route":{"rules":[{"rule_set":["probe"],"outbound":"direct"}],"rule_set":[{"type":"local","tag":"probe","format":"binary","path":"$path"}]}}"""
                Libbox.checkConfig(probeConfig)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Fail-closed on ANY error (Exception, LinkageError, native crash/error)
                false
            }
        }
    }

    /** Move [tmp] onto [target] without ever leaving a partial file under
     *  the final name: renameTo first (atomic on the same volume), then a
     *  strict ATOMIC_MOVE fallback — never a byte-wise copy over the live
     *  file. On failure the existing target, the last known good copy, is
     *  left untouched. */
    private fun installAtomically(tmp: File, target: File): Boolean {
        if (tmp.renameTo(target)) return true
        return try {
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            true
        } catch (e: AtomicMoveNotSupportedException) {
            false
        } catch (e: IOException) {
            false
        }
    }

    /** Copy the bundled asset to [target] atomically; false when the tag
     *  isn't shipped. A partial copy never reaches the final name. */
    private fun seedFromBundle(tag: String, target: File): Boolean {
        val input = bundled.open(tag) ?: return false
        val tmp = File.createTempFile("$tag-", ".srs.seed", dir)
        try {
            input.use { inp -> tmp.outputStream().use { out -> inp.copyTo(out) } }
            // A broken asset must not seed the store either.
            if (!isValidSrs(tmp)) return false
            if (!installAtomically(tmp, target)) return false
            target.setLastModified(System.currentTimeMillis())
            return true
        } finally {
            tmp.delete()
        }
    }

    /** Fetches [tag] into a temp file, validates it, and hands it to
     *  [install]. `execute()` ignores coroutine cancellation, so a watcher
     *  cancels the call to free the IO thread when the caller is cancelled. */
    private suspend fun download(tag: String, install: suspend (File) -> Boolean) {
        coroutineScope {
            val call = client.newCall(Request.Builder().url(urlFor(tag)).build())
            val watcher = launch(Dispatchers.IO) {
                try {
                    awaitCancellation()
                } finally {
                    call.cancel()
                }
            }
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val body = response.body!!
                    if (body.contentLength() > MAX_RULE_SET_BYTES) {
                        throw IOException("rule set too large")
                    }
                    // Unique temp per download — even if the tag lock were bypassed,
                    // two writers can never interleave into one file.
                    val tmp = File.createTempFile("$tag-", ".srs.tmp", dir)
                    try {
                        var total = 0L
                        body.byteStream().use { input ->
                            tmp.outputStream().use { out ->
                                val buf = ByteArray(8192)
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    total += n
                                    if (total > MAX_RULE_SET_BYTES) {
                                        throw IOException("rule set too large")
                                    }
                                    out.write(buf, 0, n)
                                }
                            }
                        }
                        if (tmp.length() == 0L) throw IOException("empty rule set")
                        // Deep integrity gate: .srs files start with "SRS\x01" and
                        // contain an RFC 1950 zlib-compressed payload that must decompress
                        // fully. A poisoned 200 (captive portal, truncated mirror) must
                        // not replace a working copy — the old file survives untouched.
                        if (!isValidSrs(tmp)) throw IOException("corrupted or incomplete rule set")
                        // A failed install leaves the old target (the last known
                        // good copy) untouched.
                        if (!install(tmp)) throw IOException("atomic install failed")
                    } finally {
                        tmp.delete()
                    }
                }
            } catch (e: IOException) {
                // A call cancelled by the watcher surfaces as IOException;
                // report the cancellation, not a download failure.
                coroutineContext.ensureActive()
                throw e
            } finally {
                watcher.cancel()
            }
        }
    }

    private fun place(tmp: File, target: File): Boolean {
        if (!installAtomically(tmp, target)) return false
        target.setLastModified(System.currentTimeMillis())
        return true
    }

    private fun urlFor(tag: String): String =
        if (tag.startsWith("geoip-")) "$GEOIP_RS_BASE/$tag.srs" else "$GEOSITE_RS_BASE/$tag.srs"

    private companion object {
        const val TAG = "RuleSetStore"

        /** A copy older than this is refreshed in the background — never
         *  on the connect path, where any valid copy is used as-is. */
        const val STALE_MS = 24L * 60 * 60 * 1000

        /** Bound on the only network wait a connect can still incur: no
         *  usable local copy and no bundled baseline. */
        const val BLOCKING_DOWNLOAD_TIMEOUT_MS = 20_000L

        /** Hard cap on a downloaded rule set — the real files are ~5 MB
         *  max; anything bigger is a hostile or broken endpoint. */
        const val MAX_RULE_SET_BYTES = 32L * 1024 * 1024

        /** Hard cap on decompressed rule-set size (64 MB) — defense against
         *  zlib decompression bombs. */
        const val MAX_DECOMPRESSED_BYTES = 64L * 1024 * 1024

        /** sing-box binary rule-set magic ("SRS\x01"). */
        val SRS_MAGIC = byteArrayOf(0x53, 0x52, 0x53, 0x01)
        // SagerNet rule-set branches (binary .srs format). Tag == basename.
        const val GEOIP_RS_BASE =
            "https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set"
        const val GEOSITE_RS_BASE =
            "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set"
    }
}

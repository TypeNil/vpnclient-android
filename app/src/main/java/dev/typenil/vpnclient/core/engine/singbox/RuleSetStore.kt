package dev.typenil.vpnclient.core.engine.singbox

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.typenil.vpnclient.core.engine.EngineError
import dev.typenil.vpnclient.core.engine.RouteMode
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

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
 * fresh install connects offline), a size cap + `SSR\x01` magic check on
 * every downloaded and stored file (a captive-portal 200 or a truncated
 * copy can't poison the store), atomic installs (a partial file never
 * reaches the final name), and last-known-good fallback — a failed
 * refresh never destroys a working copy.
 */
@Singleton
class RuleSetStore @Inject constructor(
    @ApplicationContext context: Context,
    private val client: OkHttpClient,
    private val bundled: BundledRuleSets,
) {
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

    private fun ensureFile(tag: String): File {
        val target = File(dir, "$tag.srs")
        val fresh = hasValidMagic(target) &&
            System.currentTimeMillis() - target.lastModified() < STALE_MS
        if (fresh) return target
        // Seed from the bundled baseline and use it immediately — a fresh
        // install must connect even with the CDN unreachable, and must not
        // stall the connect on a download attempt. The seed is marked fresh,
        // so revalidation happens on a later ensureReady once it goes stale.
        // A present-but-corrupt file (bad magic) is not a seed blocker —
        // seeding overwrites it atomically.
        if (!hasValidMagic(target)) {
            if (seedFromBundle(tag, target)) return target
        }
        try {
            download(tag, target)
        } catch (e: Exception) {
            // A stale copy beats none — routing may be slightly outdated but
            // the connect still succeeds. Only a structurally valid (magic)
            // file qualifies; a corrupt file must fail the connect instead
            // of silently degrading routing.
            if (hasValidMagic(target)) return target
            throw EngineError.StartFailed("routing lists unavailable")
        }
        return target
    }

    /** A stored file is usable only when it still starts with the .srs
     *  magic — a truncated or poisoned file must never pass as fresh or
     *  as the last-known-good fallback. */
    private fun hasValidMagic(file: File): Boolean {
        if (!file.isFile) return false
        file.inputStream().use { probe ->
            val magic = ByteArray(SRS_MAGIC.size)
            return probe.read(magic) == SRS_MAGIC.size && magic.contentEquals(SRS_MAGIC)
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
            if (!hasValidMagic(tmp)) return false
            if (!installAtomically(tmp, target)) return false
            target.setLastModified(System.currentTimeMillis())
            return true
        } finally {
            tmp.delete()
        }
    }

    private fun download(tag: String, target: File) {
        val request = Request.Builder().url(urlFor(tag)).build()
        client.newCall(request).execute().use { response ->
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
                // Cheap integrity gate: .srs files start with "SSR\x01".
                // A poisoned 200 (captive portal, truncated mirror) must not
                // replace a working copy — the old file survives untouched.
                if (!hasValidMagic(tmp)) throw IOException("not a binary rule set")
                if (!installAtomically(tmp, target)) {
                    // The old target (if any) was not touched — it stays
                    // available for the last-known-good fallback.
                    throw IOException("atomic install failed")
                }
                target.setLastModified(System.currentTimeMillis())
            } finally {
                tmp.delete()
            }
        }
    }

    private fun urlFor(tag: String): String =
        if (tag.startsWith("geoip-")) "$GEOIP_RS_BASE/$tag.srs" else "$GEOSITE_RS_BASE/$tag.srs"

    private companion object {
        /** Revalidate after a day — geosite churn is slow and a failed
         *  revalidation silently keeps the stale copy anyway. */
        const val STALE_MS = 24L * 60 * 60 * 1000

        /** Hard cap on a downloaded rule set — the real files are ~5 MB
         *  max; anything bigger is a hostile or broken endpoint. */
        const val MAX_RULE_SET_BYTES = 32L * 1024 * 1024

        /** sing-box binary rule-set magic ("SSR\x01"). */
        val SRS_MAGIC = byteArrayOf(0x53, 0x53, 0x52, 0x01)
        // SagerNet rule-set branches (binary .srs format). Tag == basename.
        const val GEOIP_RS_BASE =
            "https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set"
        const val GEOSITE_RS_BASE =
            "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set"
    }
}

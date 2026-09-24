package dev.typenil.vpnclient.core.engine.singbox

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.typenil.vpnclient.core.engine.EngineError
import dev.typenil.vpnclient.core.engine.RouteMode
import java.io.File
import java.io.IOException
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
 * fresh install connects offline), a size cap + non-empty + `SSR\x01`
 * magic check on downloads (a captive-portal 200 can't poison the store),
 * and last-known-good fallback — a failed refresh never destroys a
 * working copy.
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
        val fresh = target.isFile && target.length() > 0 &&
            System.currentTimeMillis() - target.lastModified() < STALE_MS
        if (fresh) return target
        // Seed from the bundled baseline and use it immediately — a fresh
        // install must connect even with the CDN unreachable, and must not
        // stall the connect on a download attempt. The seed is marked fresh,
        // so revalidation happens on a later ensureReady once it goes stale.
        if (!target.isFile || target.length() == 0L) {
            if (seedFromBundle(tag, target)) return target
        }
        try {
            download(tag, target)
        } catch (e: Exception) {
            // A stale copy beats none — routing may be slightly outdated but
            // the connect still succeeds.
            if (target.isFile && target.length() > 0) return target
            throw EngineError.StartFailed("routing lists unavailable")
        }
        return target
    }

    /** Copy the bundled asset to [target] atomically; false when the tag
     *  isn't shipped. A partial copy never reaches the final name. */
    private fun seedFromBundle(tag: String, target: File): Boolean {
        val input = bundled.open(tag) ?: return false
        val tmp = File.createTempFile("$tag-", ".srs.seed", dir)
        try {
            input.use { inp -> tmp.outputStream().use { out -> inp.copyTo(out) } }
            if (tmp.length() == 0L) return false
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
            }
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
                tmp.inputStream().use { probe ->
                    val magic = ByteArray(SRS_MAGIC.size)
                    if (probe.read(magic) != SRS_MAGIC.size ||
                        !magic.contentEquals(SRS_MAGIC)
                    ) {
                        throw IOException("not a binary rule set")
                    }
                }
                // renameTo is unreliable across Windows/Android edge cases.
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
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

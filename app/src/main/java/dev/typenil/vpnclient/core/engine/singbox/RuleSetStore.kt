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
 */
@Singleton
class RuleSetStore @Inject constructor(
    @ApplicationContext context: Context,
    private val client: OkHttpClient,
) {
    private val dir = File(context.filesDir, "rule_sets")

    /** Absolute .srs path per tag required by [mode]; downloads or
     *  revalidates as needed. Empty for modes without rule sets. */
    suspend fun ensureReady(mode: RouteMode): Map<String, String> =
        withContext(Dispatchers.IO) {
            if (mode.ruleSetTags.isEmpty()) return@withContext emptyMap()
            dir.mkdirs()
            mode.ruleSetTags.associateWith { ensureFile(it).absolutePath }
        }

    private fun ensureFile(tag: String): File {
        val target = File(dir, "$tag.srs")
        val fresh = target.isFile && target.length() > 0 &&
            System.currentTimeMillis() - target.lastModified() < STALE_MS
        if (fresh) return target
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

    private fun download(tag: String, target: File) {
        val request = Request.Builder().url(urlFor(tag)).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val tmp = File(dir, "$tag.srs.tmp")
            response.body!!.byteStream().use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            }
            // renameTo is unreliable across Windows/Android edge cases.
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            target.setLastModified(System.currentTimeMillis())
        }
    }

    private fun urlFor(tag: String): String =
        if (tag.startsWith("geoip-")) "$GEOIP_RS_BASE/$tag.srs" else "$GEOSITE_RS_BASE/$tag.srs"

    private companion object {
        /** Revalidate after a day — geosite churn is slow and a failed
         *  revalidation silently keeps the stale copy anyway. */
        const val STALE_MS = 24L * 60 * 60 * 1000

        // SagerNet rule-set branches (binary .srs format). Tag == basename.
        const val GEOIP_RS_BASE =
            "https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set"
        const val GEOSITE_RS_BASE =
            "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set"
    }
}

package dev.typenil.vpnclient.core.vpn

import android.net.DnsResolver
import android.os.Build
import android.os.CancellationSignal
import android.system.ErrnoException
import android.system.OsConstants
import androidx.annotation.RequiresApi
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** Outcome of the app-side DNS probe. Carries no names, addresses or error text. */
enum class DnsCheckResult { Answered, Timeout, Failed, NotRun }

/**
 * One A query for a fixed neutral name on the process default network. Our own
 * package rides the TUN (see PerAppPolicy), so this goes app -> TUN ->
 * hijack-dns -> engine resolver; the Android per-network cache is bypassed.
 * It says nothing about egress and not which engine resolver answered.
 * Below API 29 there is no cancellable, cache-free API: [DnsCheckResult.NotRun].
 * The caller bounds the wait; cancellation cancels the query.
 */
@Singleton
class DnsProbe @Inject constructor() {
    suspend fun check(): DnsCheckResult =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) query() else DnsCheckResult.NotRun

    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun query(): DnsCheckResult = suspendCancellableCoroutine { continuation ->
        val signal = CancellationSignal()
        continuation.invokeOnCancellation { signal.cancel() }
        DnsResolver.getInstance().query(
            null, PROBE_NAME, DnsResolver.TYPE_A,
            DnsResolver.FLAG_NO_CACHE_LOOKUP or DnsResolver.FLAG_NO_CACHE_STORE,
            Runnable::run, signal,
            object : DnsResolver.Callback<List<InetAddress>> {
                override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                    continuation.resume(if (rcode == 0 && answer.isNotEmpty()) DnsCheckResult.Answered else DnsCheckResult.Failed)
                }

                override fun onError(error: DnsResolver.DnsException) {
                    val timedOut = (error.cause as? ErrnoException)?.errno == OsConstants.ETIMEDOUT
                    continuation.resume(if (timedOut) DnsCheckResult.Timeout else DnsCheckResult.Failed)
                }
            },
        )
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 4_000L
        private const val PROBE_NAME = "example.com"
    }
}

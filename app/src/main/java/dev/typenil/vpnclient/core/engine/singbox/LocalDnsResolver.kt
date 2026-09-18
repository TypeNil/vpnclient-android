package dev.typenil.vpnclient.core.engine.singbox

import android.net.DnsResolver
import android.os.Build
import android.os.CancellationSignal
import android.system.ErrnoException
import androidx.annotation.RequiresApi
import io.nekohasekai.libbox.ExchangeContext
import io.nekohasekai.libbox.LocalDNSTransport
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.runBlocking

/**
 * Routes the core's local DNS lookups through Android's resolver on the
 * current default network (same approach as sing-box-for-android).
 */
class LocalDnsResolver(
    private val networkMonitor: NetworkMonitor,
) : LocalDNSTransport {

    private class CancelFunc(private val block: () -> Unit) : io.nekohasekai.libbox.Func {
        override fun invoke() = block()
    }

    override fun raw(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun exchange(ctx: ExchangeContext, message: ByteArray) {
        val network = networkMonitor.defaultNetwork ?: error("missing default interface")
        runBlocking {
            suspendCoroutine { continuation ->
                val signal = CancellationSignal()
                val resumed = AtomicBoolean(false)
                ctx.onCancel(CancelFunc {
                    if (resumed.compareAndSet(false, true)) {
                        signal.cancel()
                        continuation.resumeWithException(CancellationException())
                    }
                })
                val callback = object : DnsResolver.Callback<ByteArray> {
                    override fun onAnswer(answer: ByteArray, rcode: Int) {
                        if (resumed.compareAndSet(false, true)) {
                            if (rcode == 0) ctx.rawSuccess(answer) else ctx.errorCode(rcode)
                            continuation.resume(Unit)
                        }
                    }

                    override fun onError(error: DnsResolver.DnsException) {
                        if (resumed.compareAndSet(false, true)) {
                            val errno = (error.cause as? ErrnoException)?.errno
                            if (errno != null) {
                                ctx.errnoCode(errno)
                                continuation.resume(Unit)
                            } else {
                                continuation.resumeWithException(error)
                            }
                        }
                    }
                }
                DnsResolver.getInstance().rawQuery(
                    network,
                    message,
                    DnsResolver.FLAG_NO_RETRY,
                    Dispatchers.IO.asExecutor(),
                    signal,
                    callback,
                )
            }
        }
    }

    override fun lookup(ctx: ExchangeContext, network: String, domain: String) {
        val defaultNetwork = networkMonitor.defaultNetwork ?: error("missing default interface")
        runBlocking {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                lookupModern(ctx, network, domain, defaultNetwork)
            } else {
                val answer = try {
                    @Suppress("DEPRECATION")
                    defaultNetwork.getAllByName(domain)
                } catch (_: UnknownHostException) {
                    ctx.errorCode(RCODE_NXDOMAIN)
                    return@runBlocking
                }
                ctx.success(answer.joinToString("\n") { it.hostAddress.orEmpty() })
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun lookupModern(
        ctx: ExchangeContext,
        network: String,
        domain: String,
        defaultNetwork: android.net.Network,
    ) = suspendCoroutine { continuation ->
        val signal = CancellationSignal()
        val resumed = AtomicBoolean(false)
        ctx.onCancel(CancelFunc {
            if (resumed.compareAndSet(false, true)) {
                signal.cancel()
                continuation.resumeWithException(CancellationException())
            }
        })
        val callback = object : DnsResolver.Callback<Collection<InetAddress>> {
            override fun onAnswer(answer: Collection<InetAddress>, rcode: Int) {
                if (resumed.compareAndSet(false, true)) {
                    if (rcode == 0) {
                        ctx.success(answer.joinToString("\n") { it.hostAddress.orEmpty() })
                    } else {
                        ctx.errorCode(rcode)
                    }
                    continuation.resume(Unit)
                }
            }

            override fun onError(error: DnsResolver.DnsException) {
                if (resumed.compareAndSet(false, true)) {
                    val errno = (error.cause as? ErrnoException)?.errno
                    if (errno != null) {
                        ctx.errnoCode(errno)
                        continuation.resume(Unit)
                    } else {
                        continuation.resumeWithException(error)
                    }
                }
            }
        }
        val type = when {
            network.endsWith("4") -> DnsResolver.TYPE_A
            network.endsWith("6") -> DnsResolver.TYPE_AAAA
            else -> null
        }
        if (type != null) {
            DnsResolver.getInstance().query(
                defaultNetwork, domain, type, DnsResolver.FLAG_NO_RETRY,
                Dispatchers.IO.asExecutor(), signal, callback,
            )
        } else {
            DnsResolver.getInstance().query(
                defaultNetwork, domain, DnsResolver.FLAG_NO_RETRY,
                Dispatchers.IO.asExecutor(), signal, callback,
            )
        }
    }

    private companion object {
        const val RCODE_NXDOMAIN = 3
    }
}

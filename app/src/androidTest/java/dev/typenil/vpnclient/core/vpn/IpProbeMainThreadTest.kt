package dev.typenil.vpnclient.core.vpn

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.InetAddress
import java.security.KeyFactory
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device regression: [IpProbe.check] finally-evicts its dedicated
 * connection pool on the caller's dispatcher. Called from [Dispatchers.Main]
 * (the diagnostics screen and the post-start health probe both do), evicting
 * the pooled idle HTTPS connection ran `SSLSocket.close()` — whose TLS
 * close_notify write trips Android's main-thread network policy —
 * NetworkOnMainThreadException: a crash for the manual check and a falsified
 * "network" degradation for the post-start probe. The pool now evicts on
 * Dispatchers.IO.
 *
 * Note the TLS server: plain `Socket.close()` is not a guarded network op on
 * Android, so only an HTTPS connection reproduces the real-world stack
 * (`RealConnectionPool.evictAll -> ConnectionPool.evictAll -> IpProbe.check`).
 * A raw [SSLServerSocket] serves one keep-alive numeric-IP response so a real
 * connected socket is parked idle in the pool when evictAll() runs.
 * Localhost only; no internet, no credentials, no subscription. The keypair
 * is a throwaway generated for this test only — it protects nothing.
 */
@RunWith(AndroidJUnit4::class)
class IpProbeMainThreadTest {

    private lateinit var serverSocket: SSLServerSocket
    private lateinit var serverThread: Thread
    private lateinit var responseWritten: CountDownLatch
    private lateinit var clientClosed: CountDownLatch

    @Volatile
    private var accepted: SSLSocket? = null

    @Volatile
    private var partialResponse = false

    @Before
    fun startServer() {
        responseWritten = CountDownLatch(1)
        clientClosed = CountDownLatch(1)
        serverSocket = serverSslContext().serverSocketFactory.createServerSocket(
            0, 1, InetAddress.getByName("127.0.0.1"),
        ) as SSLServerSocket
        serverThread = Thread {
            val socket = serverSocket.accept() as SSLSocket
            accepted = socket
            socket.startHandshake()
            // Drain the request head, then answer with a keep-alive body so
            // the client returns the connection to the pool instead of
            // closing it itself.
            val input = socket.getInputStream()
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) {
                val b = input.read()
                if (b < 0) break
                head.append(b.toChar())
            }
            val body = "192.0.2.1" // TEST-NET-1, RFC 5737 — no real host
            if (partialResponse) {
                // Advertise 64 bytes but send only a prefix — the client's
                // body read stays in flight for the cancellation test.
                socket.getOutputStream().write(
                    (
                        "HTTP/1.1 200 OK\r\n" +
                            "Content-Length: 64\r\n" +
                            "Connection: keep-alive\r\n" +
                            "\r\n" + body.take(4)
                        ).toByteArray(Charsets.US_ASCII),
                )
                socket.getOutputStream().flush()
                responseWritten.countDown()
                // The cancelled call must close this socket — block until it does.
                runCatching { while (input.read() >= 0) Unit }
                clientClosed.countDown()
            } else {
                socket.getOutputStream().write(
                    (
                        "HTTP/1.1 200 OK\r\n" +
                            "Content-Length: ${body.length}\r\n" +
                            "Connection: keep-alive\r\n" +
                            "\r\n" + body
                        ).toByteArray(Charsets.US_ASCII),
                )
                socket.getOutputStream().flush()
                // Do NOT close: the pooled client socket stays connected and idle.
            }
        }
        serverThread.isDaemon = true
        serverThread.start()
    }

    @After
    fun stopServer() {
        runCatching { accepted?.close() }
        runCatching { serverSocket.close() }
        serverThread.join(2_000)
    }

    @Test
    fun check_onMainDispatcher_poolEviction_doesNotTripAndroidNetworkPolicy() =
        runBlocking {
            val url = "https://127.0.0.1:${serverSocket.localPort}/"
            // Pre-fix this await() rethrew NetworkOnMainThreadException from
            // the pool eviction on the main thread.
            val result = withTimeout(10_000) {
                async(Dispatchers.Main) { IpProbe(trustAllClient()).check(url) }.await()
            }
            assertTrue(result.ok)
            assertEquals("192.0.2.1", result.ip)
        }

    @Test
    fun cancel_onMainDispatcher_midBodyRead_closesSocketOffMainPolicy() =
        runBlocking {
            partialResponse = true
            // EventListener.canceled fires synchronously inside call.cancel()
            // — the thread name it records is the thread the socket close ran on.
            val cancelThread = AtomicReference<String>()
            val cancelObserved = CountDownLatch(1)
            val client = trustAllClient().newBuilder()
                .eventListener(object : EventListener() {
                    override fun canceled(call: Call) {
                        cancelThread.set(Thread.currentThread().name)
                        cancelObserved.countDown()
                    }
                })
                .build()
            val url = "https://127.0.0.1:${serverSocket.localPort}/"
            val job = async(Dispatchers.Main) { IpProbe(client).check(url) }
            // The probe coroutine is suspended while the OkHttp callback
            // thread is mid-read of a never-completing body.
            assertTrue(responseWritten.await(10, TimeUnit.SECONDS))
            // invokeOnCancellation runs call.cancel() on the cancelling
            // thread. Pre-fix a Main cancel ran SSLSocket.close() on Main —
            // a socket write under Android's main-thread network policy —
            // surfacing as NetworkOnMainThreadException inside the handler
            // (CompletionHandlerException / process crash).
            withContext(Dispatchers.Main) { job.cancel() }
            val outcome = runCatching { withTimeout(10_000) { job.await() } }
            assertNotNull(outcome.exceptionOrNull())
            assertTrue(outcome.exceptionOrNull() is CancellationException)
            // The cancel was dispatched to IO — never the main thread.
            assertTrue(cancelObserved.await(10, TimeUnit.SECONDS))
            assertNotNull(cancelThread.get())
            assertNotEquals("main", cancelThread.get())
            // The socket actually closed — the server saw the connection die.
            assertTrue(clientClosed.await(10, TimeUnit.SECONDS))
        }

    private fun trustAllClient(): OkHttpClient {
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit

            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(trustManager), null)
        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    private fun serverSslContext(): SSLContext {
        fun pemBody(pem: String) = pem.lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("---") }
            .joinToString("")

        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(SERVER_CERT_PEM.byteInputStream()) as X509Certificate
        val key = KeyFactory.getInstance("RSA").generatePrivate(
            PKCS8EncodedKeySpec(Base64.getDecoder().decode(pemBody(SERVER_KEY_PEM))),
        )
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            load(null)
            setKeyEntry("server", key, charArrayOf(), arrayOf(cert))
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, charArrayOf())
        return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
    }

    companion object {
        // Throwaway self-signed keypair generated for this test only
        // (openssl req -x509 -newkey rsa:2048 -nodes -subj "/CN=localhost").
        private const val SERVER_CERT_PEM = """
-----BEGIN CERTIFICATE-----
MIIDCzCCAfOgAwIBAgIUYY/cZtkZrPclvw+RxewUQs9FmxUwDQYJKoZIhvcNAQEL
BQAwFDESMBAGA1UEAwwJbG9jYWxob3N0MCAXDTI2MDkzMDE3NTQ0OVoYDzIxMjYw
OTA2MTc1NDQ5WjAUMRIwEAYDVQQDDAlsb2NhbGhvc3QwggEiMA0GCSqGSIb3DQEB
AQUAA4IBDwAwggEKAoIBAQDrImDLuMOdygMx336r/+LDXWo5WJWAPI0xwLKV6jUV
sFq5z0TBo2GYOmF/MIe9FRh+dOeHGSo42fJnXkqJTECGhv2mdwnt7jRupKhtpx8v
Y4tvzl34JkluScEHSdbmvl9LSF5Ya8LEea0YCN5SsODjpR0KGwCnKTfJGVHuBtDU
1rPyF15BJtUYNay3BH6WwMQs3hq1exYLZhUG92Zn2IDyuqv/Xpwh8wqhjsZiZXPB
TRmz3ufEra2ETF7YVrzow9FWv9TshJsTDKnOEPUN+131Pvv8AwUWVLDK8H/lbVC4
PArInaVPzuGh71Mn8BGHMhoUSJhxv+0gIZU5EjSnhYphAgMBAAGjUzBRMB0GA1Ud
DgQWBBS8FH7RYnpuDf4ETkEniV6puqMYLDAfBgNVHSMEGDAWgBS8FH7RYnpuDf4E
TkEniV6puqMYLDAPBgNVHRMBAf8EBTADAQH/MA0GCSqGSIb3DQEBCwUAA4IBAQBw
92YqlUgqcrcVaYjNTTr8ps07TtM4Cz3fnj4cQi30byuX3G03iz7Z9yd1DOrgBDsb
FMmLfwkRrhtMLdEKAk8DaR2JmUJRP8YABCd+9UL9JsaAj94lRdjusI+JqX1GKmVs
+kfUFjernNUkPFu1nkxoCBqgtdgsBRIKqrDWfuX5vQdbz8nyiJkgiWvnqlJzFUL1
LBvYmw8kugkluxvYAk1bcsbxE3+yDFjeFwkeZv/7z0JOreu/AQnlBBBXDshZPhhs
b1qHVuc4LQlfmghUf1iNadUW/HCG/EfXrsNwMXNqmwxs0KArbxD+lJb+mjDw8Ntv
m1q6HYDVRqbtl9qqlmOy
-----END CERTIFICATE-----
"""
        private const val SERVER_KEY_PEM = """
-----BEGIN PRIVATE KEY-----
MIIEvAIBADANBgkqhkiG9w0BAQEFAASCBKYwggSiAgEAAoIBAQDrImDLuMOdygMx
336r/+LDXWo5WJWAPI0xwLKV6jUVsFq5z0TBo2GYOmF/MIe9FRh+dOeHGSo42fJn
XkqJTECGhv2mdwnt7jRupKhtpx8vY4tvzl34JkluScEHSdbmvl9LSF5Ya8LEea0Y
CN5SsODjpR0KGwCnKTfJGVHuBtDU1rPyF15BJtUYNay3BH6WwMQs3hq1exYLZhUG
92Zn2IDyuqv/Xpwh8wqhjsZiZXPBTRmz3ufEra2ETF7YVrzow9FWv9TshJsTDKnO
EPUN+131Pvv8AwUWVLDK8H/lbVC4PArInaVPzuGh71Mn8BGHMhoUSJhxv+0gIZU5
EjSnhYphAgMBAAECggEAAXQwL3lli1r/aPLBmWtTSfOCeIVpzztJgRkjXf1ELooJ
1ZJIn/7GELVzs8O8uHTpRNw7hV0alLq+CUqyS4Jd98Uykr/skMsN7X78skjfeX1t
FEnJl5+JbOtCtw/SX8oz/qNIBoCaS1x76A/N+eNcF7p39n96Po5azID3BQ4V9LIL
rX3SU+M8uh1ZFFizGZCNCqn4/RabgHcPgIo/yBtbI90wWiSqy9NnQRswohADufHM
tvzvKgQfOOqgqSSXzBGHqux+quc0s+QwCwjOBpkFmVUnr6rP7ZiG/ue2O9p1GphH
j3ireP0D2EiIN52zAxemBX5U8KV/bc9BwNmPxxd+HQKBgQD89HAVwy1SjtuzEh9i
bl8c9O3vbB6PCQcrDGrh2diWxc5yFBR8rn9H/rAYghIT0mHYY0lXKo+dkSV094h5
/4PSIl8RuDCSFU7LRJ2+D/DvyzGcOe9x2QGc3kNZhUxZ/LmhbV/oHHcKekWE3ECr
ADYKc2PT8yCIMvMc8/HfcpjJ1wKBgQDt9wU/aPsEUJY4T3ea5xxEz2jFvx9dsGEK
YVSmccO+Bj+NGb8JBznwgJ1sJ6fP2XVDNCqh5rrL+iA7PQWK3S4xmTcdSya6T8xe
7E9gpO8AFevhGUta03bIwOtTJ1Nj/hewDzcbIFz/9cwqcBlHSCoJbo0r57VRSdOQ
I5W5GiZ2hwKBgFa+VDmCRuaKythrnIuaoc9CRt3Vy9ztEaI3jeeJVvbNOnBwZl4j
UM6Vjjm5UQ2vFZKo6ZuUos872QZ1ZD3B26iR1Nw0t6NA31ZhX16wBMWWfpq+W1hF
PIJYzevDBF3PhrDO2xazvDbBm1lmVl7NobqPu2oRc9SA9FG3bfhcSfzdAoGAMolJ
RgQneu0aWe8WeYEnUb2yhHxoTt9MXIX7EjYK2eo9yNt05JfySA8oX3W0f8Gw04ra
mvODLBp8idgVuz3pt4LQX5o8KUkVH/uTh/S/BQeixnU2uZ07FrtRvqEVZqDpquww
ScR/u6QZrdGMrMS4mLQvqulUamUBVxUXkO3qyTUCgYBZ540VXRP9ulgDR2lOVmX/
rxwyZ3sxtML8qfvXsTiurtOb/NCEfAxyiP5mYPb487V32hFzg8ap6ozaeYFWVi0T
K+HW8yBUM6SxYllyGe26Z2/OTOIaPXdtHGvKZYRebOntR4I2OTA1pkYrbundAeDy
SATASH1NaP9lnOvK5A9wYw==
-----END PRIVATE KEY-----
"""
    }
}

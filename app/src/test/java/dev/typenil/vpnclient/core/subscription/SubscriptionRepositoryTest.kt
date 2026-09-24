package dev.typenil.vpnclient.core.subscription

import dev.typenil.vpnclient.core.engine.EngineError
import dev.typenil.vpnclient.core.subscription.model.ProxyNode
import dev.typenil.vpnclient.core.subscription.model.SubscriptionError
import dev.typenil.vpnclient.data.db.DbTransactionRunner
import dev.typenil.vpnclient.data.db.NodeDao
import dev.typenil.vpnclient.data.db.NodeEntity
import dev.typenil.vpnclient.data.db.SubscriptionDao
import dev.typenil.vpnclient.data.db.SubscriptionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Refresh-ordering tests: a candidate is committed only after fetch → parse →
 * engine validation succeed; failures preserve last-known-good rows.
 * Real fetcher/classifier/dispatcher over MockWebServer; fake DAOs.
 */
class SubscriptionRepositoryTest {

    private val server = MockWebServer()

    private class FakeSubscriptionDao : SubscriptionDao {
        val subs = mutableMapOf<Long, SubscriptionEntity>()
        var successCalls = 0
        var attemptCalls = 0
        var lastAttemptError: String? = null

        override fun observeAll() = flowOf(subs.values.toList())
        override suspend fun get(id: Long) = subs[id]
        override suspend fun getAll() = subs.values.toList()
        override suspend fun insert(entity: SubscriptionEntity): Long {
            subs[entity.id] = entity
            return entity.id
        }
        override suspend fun update(entity: SubscriptionEntity) {
            subs[entity.id] = entity
        }
        override suspend fun delete(id: Long) { subs.remove(id) }
        override suspend fun markAttempt(id: Long, attemptAt: Long, error: String?) {
            attemptCalls++
            lastAttemptError = error
        }
        override suspend fun markSuccess(
            id: Long,
            updatedAt: Long,
            attemptAt: Long,
            userInfoJson: String?,
            supportUrl: String?,
            updateIntervalMinutes: Int?,
            announce: String?,
            updateAlways: Boolean,
            fallbackUrl: String?,
        ) {
            successCalls++
            subs[id]?.let { subs[id] = it.copy(
                lastUpdatedAtEpochMs = updatedAt,
                lastAttemptAtEpochMs = attemptAt,
                lastError = null,
                userInfoJson = userInfoJson,
                supportUrl = supportUrl,
                updateIntervalMinutes = updateIntervalMinutes,
                announce = announce,
                updateAlways = updateAlways,
                fallbackUrl = fallbackUrl,
            ) }
        }
    }

    private class FakeNodeDao : NodeDao() {
        val nodes = mutableMapOf<String, NodeEntity>()
        var replaceCalls = 0

        override suspend fun forSubscription(subscriptionId: Long) =
            nodes.values.filter { it.subscriptionId == subscriptionId }
        override fun observeEnabled(): Flow<List<NodeEntity>> = flowOf(nodes.values.toList())
        override suspend fun getEnabled() = nodes.values.toList()
        override suspend fun get(id: String) = nodes[id]
        override suspend fun upsertAll(new: List<NodeEntity>) {
            new.forEach { nodes[it.id] = it }
        }
        override suspend fun deleteForSubscription(subscriptionId: Long) {
            nodes.values.removeAll { it.subscriptionId == subscriptionId }
        }
        override suspend fun countForSubscription(subscriptionId: Long): Int =
            nodes.values.count { it.subscriptionId == subscriptionId }
        override suspend fun replaceForSubscription(
            subscriptionId: Long,
            nodes: List<NodeEntity>,
        ) {
            replaceCalls++
            super.replaceForSubscription(subscriptionId, nodes)
        }
    }

    private class FakeValidator(
        private val nodeDao: FakeNodeDao,
    ) : SubscriptionCandidateValidator {
        var failure: EngineError? = null
        var calls = 0
        var replaceCallsAtValidate = -1
        override suspend fun validate(nodes: List<ProxyNode>) {
            calls++
            // Ordering contract: validation must happen before any DB write.
            replaceCallsAtValidate = nodeDao.replaceCalls
            failure?.let { throw it }
        }
    }

    private inner class FakeTransactions : DbTransactionRunner {
        /** scheduler.cancel calls observed while a transaction block ran. */
        var cancelledInsideBlock = -1
        override suspend fun <T> run(block: suspend () -> T): T {
            cancelledInsideBlock = scheduler.cancelled.size
            return block()
        }
    }

    private class FakeSettings : SubscriptionSettings {
        val selected = MutableStateFlow<String?>(null)
        val autoRefresh = MutableStateFlow(0)
        override suspend fun getOrCreateHwid() = "00000000-0000-0000-0000-000000000000"
        override val selectedNodeId: Flow<String?> get() = selected
        override suspend fun setSelectedNodeId(id: String?) { selected.value = id }
        override suspend fun clearSelectedNodeIdIf(expected: String) {
            if (selected.value == expected) selected.value = null
        }
        override val autoRefreshMinutes: Flow<Int> get() = autoRefresh
        val alerted = MutableStateFlow<Set<String>>(emptySet())
        override val expiryAlerted: Flow<Set<String>> get() = alerted
        override suspend fun markExpiryAlerted(key: String) {
            alerted.value = alerted.value + key
        }
    }

    private class FakeScheduler : SubscriptionRefreshScheduler {
        data class Call(
            val id: Long,
            val providerMinutes: Int?,
            val userOverride: Int,
            val enabled: Boolean,
        )
        val scheduled = mutableListOf<Call>()
        val cancelled = mutableListOf<Long>()
        override suspend fun schedule(
            subscriptionId: Long,
            providerMinutes: Int?,
            userOverrideMinutes: Int,
            enabled: Boolean,
        ) {
            scheduled.add(Call(subscriptionId, providerMinutes, userOverrideMinutes, enabled))
        }
        override fun cancel(subscriptionId: Long) { cancelled.add(subscriptionId) }
        override suspend fun reconcile(activeSubscriptionIds: Set<Long>) = Unit
    }

    private class FakeExpiryNotifier : SubscriptionExpiryNotifier {
        data class Call(val id: Long, val name: String, val expire: Long)
        val calls = mutableListOf<Call>()
        override fun notifyExpiring(
            subscriptionId: Long,
            subscriptionName: String,
            expireEpochSeconds: Long,
        ): Boolean {
            calls.add(Call(subscriptionId, subscriptionName, expireEpochSeconds))
            return posted
        }

        /** False simulates a denied POST_NOTIFICATIONS — nothing was shown. */
        var posted: Boolean = true
    }

    private lateinit var subscriptionDao: FakeSubscriptionDao
    private lateinit var nodeDao: FakeNodeDao
    private lateinit var validator: FakeValidator
    private lateinit var scheduler: FakeScheduler
    private lateinit var settings: FakeSettings
    private lateinit var expiryNotifier: FakeExpiryNotifier
    private lateinit var transactions: FakeTransactions
    private lateinit var repository: SubscriptionRepository

    private val uriParser = UriListParser()

    private fun uri(host: String, name: String) =
        "vless://11111111-2222-3333-4444-555555555555@$host:443" +
            "?security=reality&sni=www.example.org&fp=chrome&pbk=abc123pubkey&sid=01ab" +
            "&type=tcp&flow=xtls-rprx-vision#$name"

    private fun nodeEntity(uri: String, subId: Long): NodeEntity {
        val n = uriParser.parse(uri, subId).nodes.single()
        return NodeEntity(
            id = n.id,
            subscriptionId = subId,
            name = n.name,
            protocol = n.protocol.name,
            server = n.server,
            port = n.port,
            outboundJson = n.outboundJson,
            rawUri = n.rawUri,
            position = 0,
        )
    }


    private fun seedSubscription(id: Long, url: String, fallbackUrl: String? = null) {
        subscriptionDao.subs[id] = SubscriptionEntity(
            id = id,
            name = "sub",
            url = url,
            createdAtEpochMs = 1,
            lastUpdatedAtEpochMs = null,
            lastAttemptAtEpochMs = null,
            lastError = null,
            enabled = true,
            userInfoJson = null,
            supportUrl = null,
            updateIntervalMinutes = null,
            announce = null,
            fallbackUrl = fallbackUrl,
            allowInsecureHttp = true,
        )
    }

    private fun seedSubscription(id: Long = 1L) {
        seedSubscription(id, server.url("/sub").toString())
    }

    @Before
    fun setUp() {
        subscriptionDao = FakeSubscriptionDao()
        nodeDao = FakeNodeDao()
        validator = FakeValidator(nodeDao)
        settings = FakeSettings()
        scheduler = FakeScheduler()
        transactions = FakeTransactions()
        expiryNotifier = FakeExpiryNotifier()
        repository = SubscriptionRepository(
            subscriptionDao = subscriptionDao,
            nodeDao = nodeDao,
            fetcher = SubscriptionFetcher(OkHttpClient()),
            classifier = SubscriptionClassifier(),
            dispatcher = SubscriptionParserDispatcher(
                uriParser, SingBoxJsonParser(), ClashYamlParser(),
            ),
            validator = validator,
            transactions = transactions,
            scheduler = scheduler,
            settings = settings,
            expiryNotifier = expiryNotifier,
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `refresh validates before committing nodes`() = runTest {
        seedSubscription()
        server.enqueue(MockResponse().setBody(uri("a.example.com", "A")))

        val result = repository.refresh(1)

        assertTrue(result.isSuccess)
        assertEquals(1, validator.calls)
        assertEquals(0, validator.replaceCallsAtValidate)
        assertEquals(1, nodeDao.replaceCalls)
        assertEquals(1, nodeDao.forSubscription(1).size)
        assertEquals(1, subscriptionDao.successCalls)
    }

    @Test
    fun `validation failure preserves last-known-good nodes`() = runTest {
        seedSubscription()
        val old = nodeEntity(uri("old.example.com", "Old"), 1)
        nodeDao.nodes[old.id] = old
        validator.failure = EngineError.InvalidConfig("rejected")
        server.enqueue(MockResponse().setBody(uri("b.example.com", "B")))

        val result = repository.refresh(1)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SubscriptionError.ConfigRejected)
        assertEquals(0, nodeDao.replaceCalls)
        assertEquals(listOf(old.id), nodeDao.forSubscription(1).map { it.id })
        assertEquals("rejected by engine", subscriptionDao.lastAttemptError)
    }

    @Test
    fun `unparseable body preserves last-known-good nodes`() = runTest {
        seedSubscription()
        val old = nodeEntity(uri("old.example.com", "Old"), 1)
        nodeDao.nodes[old.id] = old
        server.enqueue(MockResponse().setBody("definitely not a subscription body"))

        val result = repository.refresh(1)

        assertTrue(result.isFailure)
        assertEquals(0, validator.calls)
        assertEquals(0, nodeDao.replaceCalls)
        assertEquals(listOf(old.id), nodeDao.forSubscription(1).map { it.id })
    }

    @Test
    fun `selection is cleared when the selected node vanishes`() = runTest {
        seedSubscription()
        val old = nodeEntity(uri("old.example.com", "Old"), 1)
        nodeDao.nodes[old.id] = old
        settings.selected.value = old.id
        server.enqueue(MockResponse().setBody(uri("b.example.com", "B")))

        assertTrue(repository.refresh(1).isSuccess)
        assertNull(settings.selected.value)
    }

    @Test
    fun `selection is kept when the selected node survives`() = runTest {
        seedSubscription()
        val old = nodeEntity(uri("a.example.com", "A"), 1)
        nodeDao.nodes[old.id] = old
        settings.selected.value = old.id
        server.enqueue(MockResponse().setBody(uri("a.example.com", "A")))

        assertTrue(repository.refresh(1).isSuccess)
        assertEquals(old.id, settings.selected.value)
    }

    @Test
    fun `add rejects cleartext url without opt-in`() = runTest {
        val result = repository.add(server.url("/sub").toString(), null)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SubscriptionError.InsecureTransport)
        assertTrue(subscriptionDao.subs.isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `add accepts cleartext url with opt-in`() = runTest {
        server.enqueue(MockResponse().setBody(uri("a.example.com", "A")))

        val result = repository.add(
            server.url("/sub").toString(), null, allowInsecureHttp = true,
        )

        assertTrue(result.isSuccess)
        assertEquals(1, subscriptionDao.subs.size)
    }

    @Test
    fun `http failure preserves nodes and records attempt`() = runTest {
        seedSubscription()
        val old = nodeEntity(uri("old.example.com", "Old"), 1)
        nodeDao.nodes[old.id] = old
        server.enqueue(MockResponse().setResponseCode(500))

        val result = repository.refresh(1)

        assertTrue(result.isFailure)
        assertEquals(0, nodeDao.replaceCalls)
        assertEquals(listOf(old.id), nodeDao.forSubscription(1).map { it.id })
        assertEquals("HTTP 500", subscriptionDao.lastAttemptError)
    }

    @Test
    fun `successful refresh registers scheduled auto-update`() = runTest {
        seedSubscription()
        server.enqueue(
            MockResponse()
                .setBody(uri("a.example.com", "A"))
                .setHeader("profile-update-interval", "2"),
        )

        assertTrue(repository.refresh(1).isSuccess)
        assertEquals(1, scheduler.scheduled.size)
        val call = scheduler.scheduled.single()
        assertEquals(1L, call.id)
        assertEquals(120, call.providerMinutes)
        assertEquals(0, call.userOverride)
        assertTrue(call.enabled)
    }

    @Test
    fun `failed refresh does not touch the scheduler`() = runTest {
        seedSubscription()
        server.enqueue(MockResponse().setResponseCode(500))

        assertTrue(repository.refresh(1).isFailure)
        assertTrue(scheduler.scheduled.isEmpty())
    }

    @Test
    fun `remove deletes rows atomically then cancels the scheduled job`() = runTest {
        seedSubscription()
        nodeDao.nodes["n1"] = nodeEntity(uri("a.example.com", "A"), 1)

        repository.remove(1)

        assertTrue(subscriptionDao.subs.isEmpty())
        assertTrue(nodeDao.nodes.isEmpty())
        // Cancel ran after the delete transaction committed, not before it.
        assertEquals(0, transactions.cancelledInsideBlock)
        assertEquals(listOf(1L), scheduler.cancelled)
    }

    @Test
    fun `failed add still schedules when user override is set`() = runTest {
        settings.autoRefresh.value = 60
        server.enqueue(MockResponse().setResponseCode(500))

        val result = repository.add(
            server.url("/sub").toString(), null, allowInsecureHttp = true,
        )

        assertTrue(result.isFailure)
        val call = scheduler.scheduled.single()
        assertEquals(subscriptionDao.subs.keys.single(), call.id)
        assertNull(call.providerMinutes)
        assertEquals(60, call.userOverride)
        assertTrue(call.enabled)
    }

    @Test
    fun `failed add in provider mode stays unscheduled`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = repository.add(
            server.url("/sub").toString(), null, allowInsecureHttp = true,
        )

        assertTrue(result.isFailure)
        assertTrue(scheduler.scheduled.isEmpty())
    }

    @Test
    fun `network failure retries once against fallback url`() = runTest {
        val fallback = MockWebServer()
        try {
            fallback.start()
            seedSubscription(
                1,
                url = server.url("/sub").toString(),
                fallbackUrl = fallback.url("/sub").toString(),
            )
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            fallback.enqueue(MockResponse().setBody(uri("a.example.com", "A")))

            val result = repository.refresh(1)

            assertTrue(result.isSuccess)
            assertEquals(1, nodeDao.forSubscription(1).size)
        } finally {
            fallback.shutdown()
        }
    }

    @Test
    fun `http error does not retry fallback`() = runTest {
        val fallback = MockWebServer()
        try {
            fallback.start()
            seedSubscription(
                1,
                url = server.url("/sub").toString(),
                fallbackUrl = fallback.url("/sub").toString(),
            )
            server.enqueue(MockResponse().setResponseCode(500))

            assertTrue(repository.refresh(1).isFailure)
            assertEquals(0, fallback.requestCount)
        } finally {
            fallback.shutdown()
        }
    }

    @Test
    fun `moved-permanently-to migrates the stored url`() = runTest {
        seedSubscription()
        server.enqueue(
            MockResponse()
                .setBody(uri("a.example.com", "A"))
                .setHeader("moved-permanently-to", "https://new.example.com/sub"),
        )

        assertTrue(repository.refresh(1).isSuccess)
        assertEquals("https://new.example.com/sub", subscriptionDao.subs[1]?.url)
    }

    @Test
    fun `http migration target honored only under cleartext opt-in`() = runTest {
        // Opted-in subscription: an http migration target is permitted.
        seedSubscription()
        server.enqueue(
            MockResponse()
                .setBody(uri("a.example.com", "A"))
                .setHeader("moved-permanently-to", "http://public.example.com/sub"),
        )

        assertTrue(repository.refresh(1).isSuccess)
        assertEquals("http://public.example.com/sub", subscriptionDao.subs[1]?.url)
    }

    @Test
    fun `migration transport policy`() {
        // The fetch can't be exercised over HTTPS without okhttp-tls, so the
        // transport gate is a pure function: https always allowed, http only
        // under the per-subscription opt-in.
        assertTrue(isMigrationAllowed("https://new.example.com/sub", allowInsecureHttp = false))
        assertFalse(isMigrationAllowed("http://new.example.com/sub", allowInsecureHttp = false))
        assertTrue(isMigrationAllowed("http://new.example.com/sub", allowInsecureHttp = true))
        assertFalse(isMigrationAllowed("not-a-url", allowInsecureHttp = true))
    }

    @Test
    fun `successful fallback keeps the stored fallback url`() = runTest {
        val fallback = MockWebServer()
        try {
            fallback.start()
            seedSubscription(
                1,
                url = server.url("/sub").toString(),
                fallbackUrl = fallback.url("/sub").toString(),
            )
            // Fallback answers without its own fallback-url header — the
            // known-working endpoint must survive markSuccess.
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            fallback.enqueue(MockResponse().setBody(uri("a.example.com", "A")))

            assertTrue(repository.refresh(1).isSuccess)
            assertEquals(fallback.url("/sub").toString(), subscriptionDao.subs[1]?.fallbackUrl)
        } finally {
            fallback.shutdown()
        }
    }

    @Test
    fun `private new-url is ignored`() = runTest {
        seedSubscription()
        val original = subscriptionDao.subs[1]!!.url
        server.enqueue(
            MockResponse()
                .setBody(uri("a.example.com", "A"))
                .setHeader("new-url", "http://127.0.0.1:9/sub"),
        )

        assertTrue(repository.refresh(1).isSuccess)
        assertEquals(original, subscriptionDao.subs[1]?.url)
    }

    @Test
    fun `expiry inside the window posts one alert`() = runTest {
        seedSubscription()
        val expire = (System.currentTimeMillis() / 1000) + 3600
        server.enqueue(
            MockResponse()
                .setBody(uri("a.example.com", "A"))
                .setHeader("subscription-userinfo", "upload=0; download=0; total=0; expire=$expire"),
        )

        assertTrue(repository.refresh(1).isSuccess)
        assertEquals(1, expiryNotifier.calls.size)
        assertEquals("1:$expire" in settings.alerted.value, true)

        // Second refresh with the same expiry — already alerted, stays quiet.
        server.enqueue(
            MockResponse()
                .setBody(uri("a.example.com", "A"))
                .setHeader("subscription-userinfo", "upload=0; download=0; total=0; expire=$expire"),
        )
        assertTrue(repository.refresh(1).isSuccess)
        assertEquals(1, expiryNotifier.calls.size)
    }

    @Test
    fun `denied notification is not recorded as alerted`() = runTest {
        seedSubscription()
        expiryNotifier.posted = false
        val expire = (System.currentTimeMillis() / 1000) + 3600
        server.enqueue(
            MockResponse()
                .setBody(uri("a.example.com", "A"))
                .setHeader("subscription-userinfo", "upload=0; download=0; total=0; expire=$expire"),
        )

        assertTrue(repository.refresh(1).isSuccess)
        assertEquals(1, expiryNotifier.calls.size)
        // Nothing was posted — the key must stay unmarked so a later
        // refresh can alert once permission is granted.
        assertFalse("1:$expire" in settings.alerted.value)
    }

    @Test
    fun `persisted expiry scan alerts without a refresh`() = runTest {
        // The startup path: expiry already persisted in userInfoJson, no
        // fetch involved — checkPersistedExpiryAlerts must still fire.
        seedSubscription()
        val expire = (System.currentTimeMillis() / 1000) + 3600
        subscriptionDao.subs[1] = subscriptionDao.subs[1]!!.copy(
            userInfoJson = """{"uploadBytes":0,"downloadBytes":0,"totalBytes":0,"expireEpochSeconds":$expire}""",
        )

        repository.checkPersistedExpiryAlerts()

        assertEquals(1, expiryNotifier.calls.size)
        assertTrue(expiryAlertKey(1, expire) in settings.alerted.value)

        // Second scan — already alerted, stays quiet.
        repository.checkPersistedExpiryAlerts()
        assertEquals(1, expiryNotifier.calls.size)
    }
}

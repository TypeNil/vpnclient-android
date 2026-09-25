package dev.typenil.vpnclient.core.subscription

import dev.typenil.vpnclient.core.engine.EngineError
import dev.typenil.vpnclient.core.subscription.model.NodeSelection
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
        override suspend fun findIdByUrl(url: String): Long? =
            subs.values.firstOrNull { it.url == url }?.id
        override suspend fun getAll() = subs.values.toList()
        override suspend fun insert(entity: SubscriptionEntity): Long {
            subs[entity.id] = entity
            return entity.id
        }
        override suspend fun update(entity: SubscriptionEntity) {
            subs[entity.id] = entity
        }
        override suspend fun delete(id: Long) { subs.remove(id) }
        override suspend fun setEnabled(id: Long, enabled: Boolean) {
            subs[id]?.let { subs[id] = it.copy(enabled = enabled) }
        }
        override suspend fun updateName(id: Long, name: String) {
            subs[id]?.let { subs[id] = it.copy(name = name) }
        }
        var urlSuccessCalls = 0
        override suspend fun updateUrlAndMarkSuccess(
            id: Long,
            url: String,
            updatedAt: Long,
            attemptAt: Long,
            userInfoJson: String?,
            supportUrl: String?,
            updateIntervalMinutes: Int?,
            announce: String?,
            updateAlways: Boolean,
            fallbackUrl: String?,
        ) {
            urlSuccessCalls++
            subs[id]?.let { subs[id] = it.copy(
                url = url,
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
        override suspend fun upsert(node: NodeEntity) {
            nodes[node.id] = node
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
            uriListParser = uriParser,
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
    fun `auto selection is not treated as a vanished node`() = runTest {
        seedSubscription()
        settings.selected.value = NodeSelection.AUTO_ID
        server.enqueue(MockResponse().setBody(uri("b.example.com", "B")))

        assertTrue(repository.refresh(1).isSuccess)
        assertEquals(NodeSelection.AUTO_ID, settings.selected.value)
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

    // ---- share-link import (manual sentinel row) ----

    private suspend fun manualSubId(): Long =
        subscriptionDao.findIdByUrl(SubscriptionRepository.MANUAL_SUBSCRIPTION_URL)!!

    @Test
    fun `importShareLink creates the manual row and stores the node`() = runTest {
        val result = repository.importShareLink(uri("a.example.com", "A"))

        assertTrue(result.isSuccess)
        assertEquals(1, subscriptionDao.subs.size)
        val sub = subscriptionDao.subs.values.single()
        assertEquals(SubscriptionRepository.MANUAL_SUBSCRIPTION_URL, sub.url)
        assertEquals("Manual servers", sub.name)
        assertTrue(sub.enabled)
        assertEquals(1, nodeDao.forSubscription(sub.id).size)
        // Never a fetch, never a scheduled job.
        assertEquals(0, server.requestCount)
        assertTrue(scheduler.scheduled.isEmpty())
    }

    @Test
    fun `importShareLink appends to the existing manual row`() = runTest {
        repository.importShareLink(uri("a.example.com", "A"))
        repository.importShareLink(uri("b.example.com", "B"))

        assertEquals(1, subscriptionDao.subs.size)
        val nodes = nodeDao.forSubscription(manualSubId())
        assertEquals(2, nodes.size)
        // Appended, not replaced — positions bump monotonically.
        assertEquals(listOf(0, 1), nodes.sortedBy { it.position }.map { it.position })
    }

    @Test
    fun `importShareLink is idempotent on node id`() = runTest {
        repository.importShareLink(uri("a.example.com", "A"))
        repository.importShareLink(uri("a.example.com", "A"))

        assertEquals(1, nodeDao.forSubscription(manualSubId()).size)
    }

    @Test
    fun `importShareLink typed-fails on garbage and writes nothing`() = runTest {
        val result = repository.importShareLink("definitely not a share link")

        assertTrue(result.isFailure)
        // The sentinel row IS created before the parse (node identity is
        // salted with it) — but no node rows and no fetch happened.
        assertTrue(nodeDao.nodes.isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `refresh fails fast on the manual sentinel row`() = runTest {
        repository.importShareLink(uri("a.example.com", "A"))
        val id = manualSubId()

        val result = repository.refresh(id)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SubscriptionError.NotFound)
        assertEquals(0, server.requestCount)
        assertTrue(scheduler.scheduled.isEmpty())
    }

    @Test
    fun `remove is refused on the manual sentinel row`() = runTest {
        repository.importShareLink(uri("a.example.com", "A"))
        val id = manualSubId()

        repository.remove(id)

        assertEquals(1, subscriptionDao.subs.size)
        assertEquals(1, nodeDao.forSubscription(id).size)
        assertTrue(scheduler.cancelled.isEmpty())
    }

    // ---- enable/disable ----

    @Test
    fun `setEnabled false flips the flag and cancels the job`() = runTest {
        seedSubscription()
        nodeDao.nodes["n1"] = nodeEntity(uri("a.example.com", "A"), 1)
        settings.autoRefresh.value = 60

        repository.setEnabled(1, false)

        assertFalse(subscriptionDao.subs[1]!!.enabled)
        val call = scheduler.scheduled.single()
        assertEquals(1L, call.id)
        assertFalse(call.enabled)
    }

    @Test
    fun `setEnabled true re-registers the job`() = runTest {
        seedSubscription()
        repository.setEnabled(1, false)

        repository.setEnabled(1, true)

        assertTrue(subscriptionDao.subs[1]!!.enabled)
        assertTrue(scheduler.scheduled.last().enabled)
    }

    @Test
    fun `disabling clears the selection when it owns the selected node`() = runTest {
        seedSubscription()
        val node = nodeEntity(uri("a.example.com", "A"), 1)
        nodeDao.nodes[node.id] = node
        settings.selected.value = node.id

        repository.setEnabled(1, false)

        assertNull(settings.selected.value)
    }

    @Test
    fun `disabling keeps a selection owned by another subscription`() = runTest {
        seedSubscription()
        seedSubscription(id = 2, url = server.url("/other").toString())
        val other = nodeEntity(uri("other.example.com", "B"), 2)
        nodeDao.nodes[other.id] = other
        settings.selected.value = other.id

        repository.setEnabled(1, false)

        assertEquals(other.id, settings.selected.value)
    }

    // ---- rename ----

    @Test
    fun `rename stores the trimmed name`() = runTest {
        seedSubscription()
        repository.rename(1, "  My VPN  ")
        assertEquals("My VPN", subscriptionDao.subs[1]!!.name)
    }

    @Test
    fun `rename ignores blank input`() = runTest {
        seedSubscription()
        repository.rename(1, "   ")
        assertEquals("sub", subscriptionDao.subs[1]!!.name)
    }

    // ---- editUrl ----

    @Test
    fun `editUrl repoints the row and swaps nodes atomically`() = runTest {
        seedSubscription()
        val newServer = MockWebServer()
        try {
            newServer.start()
            val old = nodeEntity(uri("old.example.com", "Old"), 1)
            nodeDao.nodes[old.id] = old
            newServer.enqueue(MockResponse().setBody(uri("new.example.com", "New")))

            val result = repository.editUrl(1, newServer.url("/sub").toString())

            assertTrue(result.isSuccess)
            assertEquals(newServer.url("/sub").toString(), subscriptionDao.subs[1]!!.url)
            assertEquals(1, subscriptionDao.urlSuccessCalls)
            assertEquals(
                listOf("new.example.com"),
                nodeDao.forSubscription(1).map { it.server },
            )
            // The markSuccess path was not used — the URL must land in the
            // same write as the node swap.
            assertEquals(0, subscriptionDao.successCalls)
        } finally {
            newServer.shutdown()
        }
    }

    @Test
    fun `editUrl failure keeps the stored URL and nodes`() = runTest {
        seedSubscription()
        val old = nodeEntity(uri("old.example.com", "Old"), 1)
        nodeDao.nodes[old.id] = old
        val originalUrl = subscriptionDao.subs[1]!!.url
        server.enqueue(MockResponse().setResponseCode(500))

        val result = repository.editUrl(1, server.url("/broken").toString())

        assertTrue(result.isFailure)
        assertEquals(originalUrl, subscriptionDao.subs[1]!!.url)
        assertEquals(listOf(old.id), nodeDao.forSubscription(1).map { it.id })
        assertEquals(0, subscriptionDao.urlSuccessCalls)
    }

    @Test
    fun `editUrl rejects a cleartext url without the opt-in`() = runTest {
        seedSubscription()
        subscriptionDao.subs[1] = subscriptionDao.subs[1]!!.copy(allowInsecureHttp = false)
        val originalUrl = subscriptionDao.subs[1]!!.url

        val result = repository.editUrl(1, "http://cleartext.example.com/sub")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SubscriptionError.InsecureTransport)
        assertEquals(originalUrl, subscriptionDao.subs[1]!!.url)
        assertEquals(0, subscriptionDao.urlSuccessCalls)
    }

    @Test
    fun `editUrl with an unchanged url just refreshes`() = runTest {
        seedSubscription()
        server.enqueue(MockResponse().setBody(uri("a.example.com", "A")))

        val result = repository.editUrl(1, subscriptionDao.subs[1]!!.url)

        assertTrue(result.isSuccess)
        // Same-URL edit delegates to refresh — markSuccess, not the URL write.
        assertEquals(0, subscriptionDao.urlSuccessCalls)
        assertEquals(1, subscriptionDao.successCalls)
    }

    @Test
    fun `editUrl on a validation failure preserves nodes and url`() = runTest {
        seedSubscription()
        val old = nodeEntity(uri("old.example.com", "Old"), 1)
        nodeDao.nodes[old.id] = old
        val originalUrl = subscriptionDao.subs[1]!!.url
        validator.failure = EngineError.InvalidConfig("rejected")
        server.enqueue(MockResponse().setBody(uri("bad.example.com", "B")))

        val result = repository.editUrl(1, server.url("/other").toString())

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SubscriptionError.ConfigRejected)
        assertEquals(originalUrl, subscriptionDao.subs[1]!!.url)
        assertEquals(listOf(old.id), nodeDao.forSubscription(1).map { it.id })
    }
}

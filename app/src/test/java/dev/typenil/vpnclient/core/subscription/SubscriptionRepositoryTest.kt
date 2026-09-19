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
import org.junit.After
import org.junit.Assert.assertEquals
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
        ) {
            successCalls++
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

    private class FakeTransactions : DbTransactionRunner {
        override suspend fun <T> run(block: suspend () -> T): T = block()
    }

    private class FakeSettings : SubscriptionSettings {
        val selected = MutableStateFlow<String?>(null)
        val autoRefresh = MutableStateFlow(0)
        override suspend fun getOrCreateHwid() = "00000000-0000-0000-0000-000000000000"
        override val selectedNodeId: Flow<String?> get() = selected
        override suspend fun setSelectedNodeId(id: String?) { selected.value = id }
        override val autoRefreshMinutes: Flow<Int> get() = autoRefresh
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
    }

    private lateinit var subscriptionDao: FakeSubscriptionDao
    private lateinit var nodeDao: FakeNodeDao
    private lateinit var validator: FakeValidator
    private lateinit var settings: FakeSettings
    private lateinit var scheduler: FakeScheduler
    private lateinit var repository: SubscriptionRepository

    private val uriParser = UriListParser()

    private fun uri(host: String, name: String) =
        "vless://11111111-2222-3333-4444-555555555555@$host:443" +
            "?security=reality&sni=www.example.org&fp=chrome&pbk=abc123pubkey&sid=01ab" +
            "&type=tcp&flow=xtls-rprx-vision#$name"

    private fun nodeEntity(uri: String, subId: Long): NodeEntity {
        val n = uriParser.parse(uri, subId).single()
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

    private fun seedSubscription(id: Long = 1L) {
        subscriptionDao.subs[id] = SubscriptionEntity(
            id = id,
            name = "sub",
            url = server.url("/sub").toString(),
            createdAtEpochMs = 1,
            lastUpdatedAtEpochMs = null,
            lastAttemptAtEpochMs = null,
            lastError = null,
            enabled = true,
            userInfoJson = null,
            supportUrl = null,
            updateIntervalMinutes = null,
            // MockWebServer speaks plain HTTP — opt the fixture in.
            allowInsecureHttp = true,
        )
    }

    @Before
    fun setUp() {
        subscriptionDao = FakeSubscriptionDao()
        nodeDao = FakeNodeDao()
        validator = FakeValidator(nodeDao)
        settings = FakeSettings()
        scheduler = FakeScheduler()
        repository = SubscriptionRepository(
            subscriptionDao = subscriptionDao,
            nodeDao = nodeDao,
            fetcher = SubscriptionFetcher(OkHttpClient()),
            classifier = SubscriptionClassifier(),
            dispatcher = SubscriptionParserDispatcher(
                uriParser, SingBoxJsonParser(), ClashYamlParser(),
            ),
            validator = validator,
            transactions = FakeTransactions(),
            scheduler = scheduler,
            settings = settings,
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
    fun `remove cancels the scheduled job`() = runTest {
        seedSubscription()
        repository.remove(1)
        assertEquals(listOf(1L), scheduler.cancelled)
    }
}

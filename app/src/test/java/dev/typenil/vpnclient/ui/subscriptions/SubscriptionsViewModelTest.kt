package dev.typenil.vpnclient.ui.subscriptions

import dev.typenil.vpnclient.core.subscription.ImportUrlExtractor
import dev.typenil.vpnclient.ui.qrscan.QrScanViewModel
import dev.typenil.vpnclient.ui.qrscan.ScanOutcome
import dev.typenil.vpnclient.core.subscription.ClashYamlParser
import dev.typenil.vpnclient.core.subscription.SingBoxJsonParser
import dev.typenil.vpnclient.core.subscription.SubscriptionCandidateValidator
import dev.typenil.vpnclient.core.subscription.SubscriptionClassifier
import dev.typenil.vpnclient.core.subscription.SubscriptionExpiryNotifier
import dev.typenil.vpnclient.core.subscription.SubscriptionFetcher
import dev.typenil.vpnclient.core.subscription.SubscriptionParserDispatcher
import dev.typenil.vpnclient.core.subscription.SubscriptionRefreshScheduler
import dev.typenil.vpnclient.core.subscription.SubscriptionRepository
import dev.typenil.vpnclient.core.subscription.SubscriptionSettings
import dev.typenil.vpnclient.core.subscription.UriListParser
import dev.typenil.vpnclient.core.subscription.model.ProxyNode
import dev.typenil.vpnclient.core.subscription.model.RefreshPolicy
import dev.typenil.vpnclient.data.db.DbTransactionRunner
import dev.typenil.vpnclient.data.db.FakeNodePreferenceDao
import dev.typenil.vpnclient.data.db.NodeDao
import dev.typenil.vpnclient.data.db.NodeEntity
import dev.typenil.vpnclient.data.db.SubscriptionDao
import dev.typenil.vpnclient.data.db.SubscriptionEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Durable-message tests for [SubscriptionsViewModel]: failures land in
 * [SubscriptionsUiState.pendingMessage] — not a replay-0 event — so they
 * survive the destination not being composed, and clear only via
 * [SubscriptionsViewModel.acknowledgeMessage]. Real repository over fake
 * DAOs, same as [SubscriptionRepositoryTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val testScope = TestScope(dispatcher)

    private class FakeSubscriptionDao : SubscriptionDao {
        val subs = mutableMapOf<Long, SubscriptionEntity>()

        override fun observeAll() = flowOf(subs.values.toList())

        override suspend fun get(id: Long) = subs[id]

        override suspend fun findIdByUrl(url: String): Long? = subs.values.firstOrNull { it.url == url }?.id

        override suspend fun getAll() = subs.values.toList()

        override suspend fun insert(entity: SubscriptionEntity): Long {
            subs[entity.id] = entity
            return entity.id
        }

        override suspend fun update(entity: SubscriptionEntity) {
            subs[entity.id] = entity
        }

        override suspend fun delete(id: Long) {
            subs.remove(id)
        }

        override suspend fun setEnabled(
            id: Long,
            enabled: Boolean,
        ) = Unit

        override suspend fun updateName(
            id: Long,
            name: String,
        ) = Unit

        override suspend fun updateRefreshPolicy(
            id: Long,
            policy: String,
            fixedMinutes: Int?,
        ) {
            subs[id]?.let {
                subs[id] = it.copy(refreshPolicy = policy, refreshFixedMinutes = fixedMinutes)
            }
        }

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
        ) = Unit

        override suspend fun markAttempt(
            id: Long,
            attemptAt: Long,
            error: String?,
        ) = Unit

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
        ) = Unit
    }

    private class FakeNodeDao : NodeDao() {
        val nodes = mutableMapOf<String, NodeEntity>()

        /** Room re-emits on every table write; the fake mirrors that with a
         *  revision tick so post-mutation state is observable. */
        private val revision = MutableStateFlow(0)

        private fun bump() {
            revision.value++
        }

        override suspend fun forSubscription(subscriptionId: Long) = nodes.values.filter { it.subscriptionId == subscriptionId }

        override fun observeEnabled(): Flow<List<NodeEntity>> = revision.map { nodes.values.toList() }

        override suspend fun getEnabled() = nodes.values.toList()

        override fun observeUsable(): Flow<List<NodeEntity>> = observeEnabled()

        override suspend fun getUsable() = getEnabled()

        override suspend fun get(id: String) = nodes[id]

        override fun observeForSubscriptionUrl(url: String): Flow<List<NodeEntity>> = revision.map { nodes.values.toList() }

        override suspend fun delete(id: String) {
            nodes.remove(id)
            bump()
        }

        override suspend fun upsertAll(new: List<NodeEntity>) {
            new.forEach { nodes[it.id] = it }
            bump()
        }

        override suspend fun upsert(node: NodeEntity) {
            nodes[node.id] = node
            bump()
        }

        override suspend fun deleteForSubscription(subscriptionId: Long) {
            nodes.values.removeAll { it.subscriptionId == subscriptionId }
            bump()
        }

        override suspend fun countForSubscription(subscriptionId: Long): Int = nodes.values.count { it.subscriptionId == subscriptionId }
    }

    private class FakeSettings : SubscriptionSettings {
        override val hwidConsent = MutableStateFlow(dev.typenil.vpnclient.core.subscription.HwidConsent.Allowed)
        override suspend fun setHwidConsent(consent: dev.typenil.vpnclient.core.subscription.HwidConsent) {
            hwidConsent.value = consent
        }
        override suspend fun getOrCreateHwid() = "00000000-0000-0000-0000-000000000000"

        override val selectedNodeId: Flow<String?> = MutableStateFlow(null)

        override suspend fun setSelectedNodeId(id: String?) = Unit

        override suspend fun clearSelectedNodeIdIf(expected: String) = Unit

        override val autoRefreshMinutes: Flow<Int> = MutableStateFlow(0)
        override val expiryAlerted: Flow<Set<String>> = MutableStateFlow(emptySet())

        override suspend fun markExpiryAlerted(key: String) = Unit
    }

    private lateinit var viewModel: SubscriptionsViewModel
    private var validatorCalls = 0

    /** Shared by the repository and the view model — the real app has one DAO
     *  instance, and the manual-node list is read through the repository. */
    private val nodeDao = FakeNodeDao()
    private val subscriptionDao = FakeSubscriptionDao()
    /** Shared by repository and view model — one settings instance, same as
     *  the app graph's SubscriptionSettings binding. */
    private val settings = FakeSettings()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val repository =
            SubscriptionRepository(
                subscriptionDao = subscriptionDao,
                nodeDao = nodeDao,
                nodePreferenceDao = FakeNodePreferenceDao(),
                fetcher = SubscriptionFetcher(OkHttpClient()),
                classifier = SubscriptionClassifier(),
                dispatcher =
                    SubscriptionParserDispatcher(
                        UriListParser(),
                        SingBoxJsonParser(),
                        ClashYamlParser(),
                    ),
                validator =
                    object : SubscriptionCandidateValidator {
                        override suspend fun validate(nodes: List<ProxyNode>) {
                            validatorCalls++
                        }
                    },
                transactions =
                    object : DbTransactionRunner {
                        override suspend fun <T> run(block: suspend () -> T): T = block()
                    },
                scheduler =
                    object : SubscriptionRefreshScheduler {
                        override suspend fun schedule(
                            subscriptionId: Long,
                            providerMinutes: Int?,
                            policy: RefreshPolicy,
                            userOverrideMinutes: Int,
                            enabled: Boolean,
                        ) = Unit

                        override fun cancel(subscriptionId: Long) = Unit
                    },
                settings = settings,
                expiryNotifier =
                    object : SubscriptionExpiryNotifier {
                        override fun notifyExpiring(
                            subscriptionId: Long,
                            subscriptionName: String,
                            expireEpochSeconds: Long,
                        ): Boolean = true
                    },
                uriListParser = UriListParser(),
            )
        viewModel = SubscriptionsViewModel(repository, settings, nodeDao)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** uiState is WhileSubscribed — collect it for the test's duration. */
    private fun TestScope.collectUi() = backgroundScope.launch { viewModel.uiState.collect {} }

    @Test
    fun `manually imported nodes reach the ui state and can be deleted`() =
        testScope.runTest {
            // The sentinel row plus one imported node — the detail sheet lists
            // these so a bad paste can be undone. Seeded before the state is
            // collected: the DAO flow reads the table at emission time.
            subscriptionDao.subs[7] =
                SubscriptionEntity(
                    id = 7,
                    name = SubscriptionRepository.MANUAL_SUBSCRIPTION_NAME,
                    url = SubscriptionRepository.MANUAL_SUBSCRIPTION_URL,
                    createdAtEpochMs = 1,
                    lastUpdatedAtEpochMs = 1,
                    lastAttemptAtEpochMs = null,
                    lastError = null,
                    enabled = true,
                    userInfoJson = null,
                    supportUrl = null,
                    updateIntervalMinutes = null,
                    announce = null,
                    fallbackUrl = null,
                )
            nodeDao.nodes["manual-1"] =
                NodeEntity(
                    id = "manual-1",
                    subscriptionId = 7,
                    name = "ManualTest",
                    protocol = "VLESS",
                    server = "a.example.com",
                    port = 443,
                    outboundJson = "{}",
                    rawUri = null,
                    position = 0,
                )
            collectUi()
            advanceUntilIdle()

            assertEquals(
                listOf("manual-1"),
                viewModel.uiState.value.manualNodes
                    .map { it.id },
            )

            viewModel.removeManualNode("manual-1")
            advanceUntilIdle()

            assertTrue(
                viewModel.uiState.value.manualNodes
                    .isEmpty(),
            )
        }

    @Test
    fun `a failed add leaves a durable pending message`() =
        testScope.runTest {
            collectUi()

            viewModel.add("not a url", null)
            advanceUntilIdle()

            assertEquals(
                "bad url",
                viewModel.uiState.value.pendingMessage
                    ?.body?.fallback,
            )
        }

    @Test
    fun `acknowledgeMessage clears the pending message`() =
        testScope.runTest {
            collectUi()
            viewModel.add("not a url", null)
            advanceUntilIdle()

            viewModel.acknowledgeMessage(
                viewModel.uiState.value.pendingMessage!!
                    .id,
            )
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.pendingMessage)
        }

    @Test
    fun `a second failure replaces the pending message with a new id`() =
        testScope.runTest {
            collectUi()
            viewModel.add("not a url", null)
            advanceUntilIdle()
            val first = viewModel.uiState.value.pendingMessage!!

            viewModel.add("http://insecure.example/sub", null)
            advanceUntilIdle()

            val second = viewModel.uiState.value.pendingMessage!!
            assertNotEquals(first.id, second.id)
            assertEquals("insecure transport not allowed for this subscription", second.body.fallback)
        }

    @Test
    fun `a failed refresh surfaces a message and clears refreshingIds`() =
        testScope.runTest {
            collectUi()

            viewModel.refresh(42)
            advanceUntilIdle()

            assertEquals(
                "subscription no longer exists",
                viewModel.uiState.value.pendingMessage
                    ?.body?.fallback,
            )
            assertTrue(
                viewModel.uiState.value.refreshingIds
                    .isEmpty(),
            )
        }

    @Test
    fun `a successful add with skipped nodes surfaces a grouped summary`() =
        testScope.runTest {
            collectUi()
            val server = MockWebServer()
            try {
                server.start()
                // One good node + two unsupported ones — the add succeeds and
                // the snackbar reports the skips grouped by reason.
                server.enqueue(
                    MockResponse().setBody(
                        "vless://11111111-1111-1111-1111-111111111111@a.example.com:443?security=none&type=tcp#A\n" +
                            "snell://x@b.example.com:1#B\n" +
                            "snell://x@c.example.com:1#C\n",
                    ),
                )

                viewModel.add(server.url("/sub").toString(), null, allowInsecureHttp = true)
                // The fetch runs on a real IO thread — pump the test dispatcher
                // until the result lands back on Main.
                val deadline = System.currentTimeMillis() + 5_000
                while (viewModel.uiState.value.pendingMessage == null &&
                    System.currentTimeMillis() < deadline
                ) {
                    advanceUntilIdle()
                    Thread.sleep(20)
                }

                val text =
                    viewModel.uiState.value.pendingMessage
                        ?.body?.fallback
                assertTrue(text!!.startsWith("2 nodes skipped:"))
                assertTrue("unsupported protocol: snell (2)" in text)
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun `xray json failure is a localized format message not network failure`() =
        testScope.runTest {
            collectUi()
            val server = MockWebServer()
            try {
                server.start()
                server.enqueue(MockResponse().setBody("""{"outbounds":[{"protocol":"vless","settings":{}}]}"""))
                viewModel.add(server.url("/synthetic").toString(), null, allowInsecureHttp = true)
                val deadline = System.currentTimeMillis() + 5_000
                while (viewModel.uiState.value.pendingMessage == null && System.currentTimeMillis() < deadline) {
                    advanceUntilIdle()
                    Thread.sleep(20)
                }
                val message = viewModel.uiState.value.pendingMessage?.body
                assertTrue(message is dev.typenil.vpnclient.ui.common.UserMessage.Resource)
                assertEquals(
                    "Xray JSON subscriptions are not supported. In your panel, select sing-box, Clash, or base64/share links.",
                    message?.fallback,
                )
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun `paste QR and Share confirm the same node with one validation each`() =
        testScope.runTest {
            collectUi()
            val raw = "vless://11111111-1111-1111-1111-111111111111@a.example.com:443?security=none&type=tcp"
            val paste = ImportUrlExtractor.extract(null, raw, null)!!
            val qr = QrScanViewModel().onBarcode(raw, nowMs = 1_000) as ScanOutcome.Found
            val share = ImportUrlExtractor.extract("android.intent.action.SEND", null, raw)!!
            assertEquals(paste.url, qr.url)
            assertEquals(paste, share)
            assertEquals(0, validatorCalls)
            assertTrue(nodeDao.nodes.isEmpty()) // extraction never imports silently
            var firstId: String? = null
            for ((index, candidate) in listOf(paste.url, qr.url, share.url).withIndex()) {
                viewModel.add(candidate, null) // existing dialog confirmation
                val deadline = System.currentTimeMillis() + 5_000
                while (validatorCalls <= index && System.currentTimeMillis() < deadline) {
                    advanceUntilIdle()
                    Thread.sleep(20)
                }
                // Validation happens before DAO commit, so also await the node.
                while (nodeDao.nodes.isEmpty() && System.currentTimeMillis() < deadline) {
                    advanceUntilIdle()
                    Thread.sleep(20)
                }
                advanceUntilIdle()
                assertEquals(index + 1, validatorCalls)
                assertNull(viewModel.uiState.value.pendingMessage)
                assertEquals(1, nodeDao.nodes.size)
                val id = nodeDao.nodes.keys.single()
                if (firstId == null) firstId = id else assertEquals(firstId, id)
            }
        }

    @Test
    fun `trash empty input and an HTML page never validate or commit nodes`() =
        testScope.runTest {
            collectUi()
            assertNull(ImportUrlExtractor.extract(null, "  ", null))
            for (raw in listOf("plain text")) {
                viewModel.add(raw, null)
                advanceUntilIdle()
                assertTrue(viewModel.uiState.value.pendingMessage != null)
                viewModel.acknowledgeMessage(viewModel.uiState.value.pendingMessage!!.id)
                advanceUntilIdle()
            }
            val server = MockWebServer()
            try {
                server.start()
                server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html><body>Not a subscription</body></html>"))
                val candidate = ImportUrlExtractor.extract(null, server.url("/page").toString(), null)!!
                viewModel.add(candidate.url, null, allowInsecureHttp = true)
                val deadline = System.currentTimeMillis() + 5_000
                while (viewModel.uiState.value.pendingMessage == null && System.currentTimeMillis() < deadline) {
                    advanceUntilIdle()
                    Thread.sleep(20)
                }
                assertTrue(viewModel.uiState.value.pendingMessage != null)
                assertEquals(0, validatorCalls)
                assertTrue(nodeDao.nodes.isEmpty())
                // The repository retains a failed subscription row for retry;
                // no parsed node or engine candidate may be committed.
            } finally {
                server.shutdown()
            }
        }

    // ---- share-link routing ----

    @Test
    fun `a share link routes to manual import, not subscription add`() =
        testScope.runTest {
            collectUi()
            val nodeDao = FakeNodeDao()
            // Rebuild a VM whose DAO we can inspect.
            val repo = newRepository(nodeDao)
            viewModel = SubscriptionsViewModel(repo, FakeSettings(), nodeDao)

            viewModel.add(
                "vless://11111111-1111-1111-1111-111111111111@a.example.com:443" +
                    "?security=none&type=tcp#A",
                null,
            )
            // The import parses on Dispatchers.Default — advanceUntilIdle()
            // alone doesn't wait for that pool, so pump until it lands.
            val deadline = System.currentTimeMillis() + 5_000
            while (nodeDao.nodes.isEmpty() && System.currentTimeMillis() < deadline) {
                advanceUntilIdle()
                Thread.sleep(20)
            }

            // No error surfaced; the node landed under the manual sentinel row.
            assertNull(viewModel.uiState.value.pendingMessage)
            assertEquals(1, nodeDao.nodes.size)
        }

    @Test
    fun `an http url still goes through the subscription path`() =
        testScope.runTest {
            collectUi()
            val nodeDao = FakeNodeDao()
            viewModel = SubscriptionsViewModel(newRepository(nodeDao), FakeSettings(), nodeDao)

            // A http URL (not https) hits InsecureTransport — proving it took the
            // add() path, not importShareLink (which would fail differently).
            viewModel.add("http://insecure.example/sub", null)
            advanceUntilIdle()

            assertEquals(
                "insecure transport not allowed for this subscription",
                viewModel.uiState.value.pendingMessage
                    ?.body?.fallback,
            )
            assertTrue(nodeDao.nodes.isEmpty())
        }

    private fun newRepository(
        nodeDao: FakeNodeDao,
        subscriptionDao: SubscriptionDao = FakeSubscriptionDao(),
        scheduler: SubscriptionRefreshScheduler =
            object : SubscriptionRefreshScheduler {
                override suspend fun schedule(
                    subscriptionId: Long,
                    providerMinutes: Int?,
                    policy: RefreshPolicy,
                    userOverrideMinutes: Int,
                    enabled: Boolean,
                ) = Unit

                override fun cancel(subscriptionId: Long) = Unit
            },
    ): SubscriptionRepository =
        SubscriptionRepository(
            subscriptionDao = subscriptionDao,
            nodeDao = nodeDao,
            nodePreferenceDao = FakeNodePreferenceDao(),
            fetcher = SubscriptionFetcher(OkHttpClient()),
            classifier = SubscriptionClassifier(),
            dispatcher =
                SubscriptionParserDispatcher(
                    UriListParser(),
                    SingBoxJsonParser(),
                    ClashYamlParser(),
                ),
            validator =
                object : SubscriptionCandidateValidator {
                    override suspend fun validate(nodes: List<ProxyNode>) = Unit
                },
            transactions =
                object : DbTransactionRunner {
                    override suspend fun <T> run(block: suspend () -> T): T = block()
                },
            scheduler = scheduler,
            settings = FakeSettings(),
            expiryNotifier =
                object : SubscriptionExpiryNotifier {
                    override fun notifyExpiring(
                        subscriptionId: Long,
                        subscriptionName: String,
                        expireEpochSeconds: Long,
                    ): Boolean = true
                },
            uriListParser = UriListParser(),
        )

    @Test
    fun `a rejected policy save surfaces a message instead of looking saved`() =
        testScope.runTest {
            collectUi()
            val dao = FakeSubscriptionDao()
            dao.subs[1] =
                SubscriptionEntity(
                    id = 1,
                    name = "sub",
                    url = "https://sub.example.com/feed",
                    createdAtEpochMs = 1,
                    lastUpdatedAtEpochMs = null,
                    lastAttemptAtEpochMs = null,
                    lastError = null,
                    enabled = true,
                    userInfoJson = null,
                    supportUrl = null,
                    updateIntervalMinutes = null,
                    announce = null,
                    fallbackUrl = null,
                )
            val throwingScheduler =
                object : SubscriptionRefreshScheduler {
                    override suspend fun schedule(
                        subscriptionId: Long,
                        providerMinutes: Int?,
                        policy: RefreshPolicy,
                        userOverrideMinutes: Int,
                        enabled: Boolean,
                    ) {
                        throw IllegalStateException("workmanager down")
                    }

                    override fun cancel(subscriptionId: Long) = Unit
                }
            viewModel =
                SubscriptionsViewModel(
                    newRepository(nodeDao, dao, throwingScheduler),
                    FakeSettings(),
                    nodeDao,
                )

            viewModel.setRefreshPolicy(1, RefreshPolicy.Fixed(30))
            advanceUntilIdle()

            // The policy write landed — but the reschedule failed, and the UI
            // must say so instead of letting the picker look like it saved.
            assertEquals("fixed", (dao.subs[1] as SubscriptionEntity).refreshPolicy)
            assertEquals(
                "Failed to update subscription",
                viewModel.uiState.value.pendingMessage
                    ?.body?.fallback,
            )
        }

    @Test
    fun `a cancelled setter propagates without a fake failure snackbar`() =
        testScope.runTest {
            collectUi()
            val dao = FakeSubscriptionDao()
            dao.subs[1] =
                SubscriptionEntity(
                    id = 1,
                    name = "sub",
                    url = "https://sub.example.com/feed",
                    createdAtEpochMs = 1,
                    lastUpdatedAtEpochMs = null,
                    lastAttemptAtEpochMs = null,
                    lastError = null,
                    enabled = true,
                    userInfoJson = null,
                    supportUrl = null,
                    updateIntervalMinutes = null,
                    announce = null,
                    fallbackUrl = null,
                )
            // The reschedule throws CancellationException — the runCatching
            // failure path must rethrow it (cancelling the coroutine) instead
            // of posting a "Failed to update" snackbar for a call that was
            // never allowed to finish.
            val cancellingScheduler =
                object : SubscriptionRefreshScheduler {
                    override suspend fun schedule(
                        subscriptionId: Long,
                        providerMinutes: Int?,
                        policy: RefreshPolicy,
                        userOverrideMinutes: Int,
                        enabled: Boolean,
                    ): Unit = throw CancellationException()

                    override fun cancel(subscriptionId: Long) = Unit
                }
            viewModel =
                SubscriptionsViewModel(
                    newRepository(nodeDao, dao, cancellingScheduler),
                    FakeSettings(),
                    nodeDao,
                )

            viewModel.setEnabled(1, false)
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.pendingMessage)
        }
}

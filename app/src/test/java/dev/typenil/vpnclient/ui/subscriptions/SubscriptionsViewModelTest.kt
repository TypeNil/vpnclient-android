package dev.typenil.vpnclient.ui.subscriptions

import dev.typenil.vpnclient.core.subscription.ClashYamlParser
import dev.typenil.vpnclient.core.subscription.SingBoxJsonParser
import dev.typenil.vpnclient.core.subscription.SubscriptionCandidateValidator
import dev.typenil.vpnclient.core.subscription.SubscriptionClassifier
import dev.typenil.vpnclient.core.subscription.SubscriptionFetcher
import dev.typenil.vpnclient.core.subscription.SubscriptionParserDispatcher
import dev.typenil.vpnclient.core.subscription.SubscriptionRefreshScheduler
import dev.typenil.vpnclient.core.subscription.SubscriptionRepository
import dev.typenil.vpnclient.core.subscription.SubscriptionSettings
import dev.typenil.vpnclient.core.subscription.UriListParser
import dev.typenil.vpnclient.core.subscription.model.ProxyNode
import dev.typenil.vpnclient.data.db.DbTransactionRunner
import dev.typenil.vpnclient.data.db.NodeDao
import dev.typenil.vpnclient.data.db.NodeEntity
import dev.typenil.vpnclient.data.db.SubscriptionDao
import dev.typenil.vpnclient.data.db.SubscriptionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
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
        override suspend fun getAll() = subs.values.toList()
        override suspend fun insert(entity: SubscriptionEntity): Long {
            subs[entity.id] = entity
            return entity.id
        }
        override suspend fun update(entity: SubscriptionEntity) {
            subs[entity.id] = entity
        }
        override suspend fun delete(id: Long) { subs.remove(id) }
        override suspend fun markAttempt(id: Long, attemptAt: Long, error: String?) = Unit
        override suspend fun markSuccess(
            id: Long,
            updatedAt: Long,
            attemptAt: Long,
            userInfoJson: String?,
            supportUrl: String?,
            updateIntervalMinutes: Int?,
        ) = Unit
    }

    private class FakeNodeDao : NodeDao() {
        val nodes = mutableMapOf<String, NodeEntity>()
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
    }

    private class FakeSettings : SubscriptionSettings {
        override suspend fun getOrCreateHwid() = "00000000-0000-0000-0000-000000000000"
        override val selectedNodeId: Flow<String?> = MutableStateFlow(null)
        override suspend fun setSelectedNodeId(id: String?) = Unit
        override suspend fun clearSelectedNodeIdIf(expected: String) = Unit
        override val autoRefreshMinutes: Flow<Int> = MutableStateFlow(0)
    }

    private lateinit var viewModel: SubscriptionsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val repository = SubscriptionRepository(
            subscriptionDao = FakeSubscriptionDao(),
            nodeDao = FakeNodeDao(),
            fetcher = SubscriptionFetcher(OkHttpClient()),
            classifier = SubscriptionClassifier(),
            dispatcher = SubscriptionParserDispatcher(
                UriListParser(), SingBoxJsonParser(), ClashYamlParser(),
            ),
            validator = object : SubscriptionCandidateValidator {
                override suspend fun validate(nodes: List<ProxyNode>) = Unit
            },
            transactions = object : DbTransactionRunner {
                override suspend fun <T> run(block: suspend () -> T): T = block()
            },
            scheduler = object : SubscriptionRefreshScheduler {
                override suspend fun schedule(
                    subscriptionId: Long,
                    providerMinutes: Int?,
                    userOverrideMinutes: Int,
                    enabled: Boolean,
                ) = Unit
                override fun cancel(subscriptionId: Long) = Unit
            },
            settings = FakeSettings(),
        )
        viewModel = SubscriptionsViewModel(repository, FakeNodeDao())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** uiState is WhileSubscribed — collect it for the test's duration. */
    private fun TestScope.collectUi() =
        backgroundScope.launch { viewModel.uiState.collect {} }

    @Test
    fun `a failed add leaves a durable pending message`() = testScope.runTest {
        collectUi()

        viewModel.add("not a url", null)
        advanceUntilIdle()

        assertEquals("bad url", viewModel.uiState.value.pendingMessage?.text)
    }

    @Test
    fun `acknowledgeMessage clears the pending message`() = testScope.runTest {
        collectUi()
        viewModel.add("not a url", null)
        advanceUntilIdle()

        viewModel.acknowledgeMessage()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.pendingMessage)
    }

    @Test
    fun `a second failure replaces the pending message with a new id`() = testScope.runTest {
        collectUi()
        viewModel.add("not a url", null)
        advanceUntilIdle()
        val first = viewModel.uiState.value.pendingMessage!!

        viewModel.add("http://insecure.example/sub", null)
        advanceUntilIdle()

        val second = viewModel.uiState.value.pendingMessage!!
        assertNotEquals(first.id, second.id)
        assertEquals("insecure transport not allowed for this subscription", second.text)
    }

    @Test
    fun `a failed refresh surfaces a message and clears refreshingIds`() = testScope.runTest {
        collectUi()

        viewModel.refresh(42)
        advanceUntilIdle()

        assertEquals("subscription no longer exists", viewModel.uiState.value.pendingMessage?.text)
        assertTrue(viewModel.uiState.value.refreshingIds.isEmpty())
    }
}

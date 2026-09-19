package dev.typenil.vpnclient.core.subscription

import dev.typenil.vpnclient.core.common.log.SecureLog
import dev.typenil.vpnclient.core.engine.EngineError
import dev.typenil.vpnclient.core.subscription.model.SubscriptionError
import dev.typenil.vpnclient.core.subscription.model.SubscriptionProfile
import dev.typenil.vpnclient.core.subscription.model.SubscriptionUserInfo
import dev.typenil.vpnclient.data.db.DbTransactionRunner
import dev.typenil.vpnclient.data.db.NodeDao
import dev.typenil.vpnclient.data.db.NodeEntity
import dev.typenil.vpnclient.data.db.SubscriptionDao
import dev.typenil.vpnclient.data.db.SubscriptionEntity
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Orchestrates add/refresh/remove for subscriptions.
 *
 * Refresh commits only a fully validated candidate: fetch → classify → parse →
 * engine validation must all succeed before any DB row changes, and the node
 * swap + success metadata land in a single transaction. A failure anywhere
 * leaves the previously persisted nodes untouched (last-known-good).
 */
@Singleton
class SubscriptionRepository @Inject constructor(
    private val subscriptionDao: SubscriptionDao,
    private val nodeDao: NodeDao,
    private val fetcher: SubscriptionFetcher,
    private val classifier: SubscriptionClassifier,
    private val dispatcher: SubscriptionParserDispatcher,
    private val validator: SubscriptionCandidateValidator,
    private val transactions: DbTransactionRunner,
    private val settings: SubscriptionSettings,
) {

    private val refreshMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    val profiles: Flow<List<SubscriptionProfile>> =
        subscriptionDao.observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun add(url: String, requestedName: String?): Result<Long> {
        val trimmed = url.trim()
        val entity = SubscriptionEntity(
            name = requestedName?.trim().orEmpty().ifEmpty { deriveName(trimmed) },
            url = trimmed,
            createdAtEpochMs = Instant.now().toEpochMilli(),
            lastUpdatedAtEpochMs = null,
            lastAttemptAtEpochMs = null,
            lastError = null,
            enabled = true,
            userInfoJson = null,
            supportUrl = null,
            updateIntervalMinutes = null,
        )
        val id = subscriptionDao.insert(entity)
        return refresh(id).map { id }
    }

    suspend fun refresh(id: Long): Result<Unit> = refreshMutex.withLock {
        val sub = subscriptionDao.get(id)
            ?: return Result.failure(SubscriptionError.ParseFailed("subscription not found"))
        val attemptAt = Instant.now().toEpochMilli()
        return try {
            val hwid = settings.getOrCreateHwid()
            val fetched = fetcher.fetch(sub.url, hwid)
            val classified = classifier.classify(fetched.body, fetched.contentType)
            val nodes = dispatcher.parse(classified.format, classified.body, id)
            if (nodes.isEmpty()) throw SubscriptionError.EmptyResult()

            // Validate the candidate against the engine BEFORE touching the DB:
            // parsed-but-unusable nodes must never replace a working set.
            try {
                validator.validate(nodes)
            } catch (e: EngineError) {
                throw SubscriptionError.ConfigRejected
            }

            val entities = nodes.mapIndexed { index, n ->
                NodeEntity(
                    id = n.id,
                    subscriptionId = id,
                    name = n.name,
                    protocol = n.protocol.name,
                    server = n.server,
                    port = n.port,
                    outboundJson = n.outboundJson,
                    rawUri = n.rawUri,
                    position = index,
                )
            }
            // Node swap + success metadata commit atomically — a crash between
            // them can't leave nodes updated but the subscription flagged stale.
            transactions.run {
                nodeDao.replaceForSubscription(id, entities)
                subscriptionDao.markSuccess(
                    id = id,
                    updatedAt = Instant.now().toEpochMilli(),
                    attemptAt = attemptAt,
                    userInfoJson = fetched.userInfo?.let { json.encodeToString(it) },
                    supportUrl = fetched.supportUrl,
                    updateIntervalMinutes = fetched.updateIntervalMinutes,
                )
            }
            // If the selected node vanished in this refresh, drop the selection
            // so the next connect picks a sane default instead of failing.
            val selected = settings.selectedNodeId.first()
            if (selected != null && nodeDao.get(selected) == null) {
                settings.setSelectedNodeId(null)
                SecureLog.i(TAG, "cleared selection — selected node vanished in refresh")
            }
            // Apply profile-title only when the name is still the auto-derived
            // host — never overwrite a name the user typed.
            if (!fetched.profileTitle.isNullOrBlank() && sub.name == deriveName(sub.url)) {
                subscriptionDao.update(sub.copy(name = fetched.profileTitle))
            }
            SecureLog.i(TAG, "refreshed sub=$id nodes=${nodes.size} fmt=${classified.format}")
            Result.success(Unit)
        } catch (e: SubscriptionError) {
            SecureLog.w(TAG, "refresh failed sub=$id: ${e.safeMessage()}")
            subscriptionDao.markAttempt(id, attemptAt, e.safeMessage())
            Result.failure(e)
        } catch (e: Exception) {
            // e.message can embed the request URL — persist a fixed string
            // and redact what reaches logcat.
            SecureLog.w(TAG, "refresh failed sub=$id: ${e.javaClass.simpleName}", e)
            subscriptionDao.markAttempt(id, attemptAt, "unexpected error")
            Result.failure(SubscriptionError.ParseFailed(e.javaClass.simpleName))
        }
    }

    suspend fun remove(id: Long) {
        nodeDao.deleteForSubscription(id)
        subscriptionDao.delete(id)
    }

    private fun deriveName(url: String): String =
        runCatching { url.toHttpUrl().host }.getOrNull() ?: "subscription"

    private fun SubscriptionError.safeMessage(): String = when (this) {
        is SubscriptionError.Http -> "HTTP $code"
        is SubscriptionError.Network -> "network unavailable"
        is SubscriptionError.Timeout -> "request timed out"
        is SubscriptionError.TooLarge -> "response too large"
        is SubscriptionError.UnsupportedFormat -> "unsupported format"
        is SubscriptionError.ParseFailed -> "parse failed"
        is SubscriptionError.EmptyResult -> "no usable nodes"
        is SubscriptionError.ConfigRejected -> "rejected by engine"
        is SubscriptionError.DeviceLimitReached -> "device limit / HWID rejected"
        is SubscriptionError.RemnawaveError -> "panel status $statusCode"
    }

    private fun SubscriptionEntity.toDomain(): SubscriptionProfile = SubscriptionProfile(
        id = id,
        name = name,
        url = url,
        createdAt = Instant.ofEpochMilli(createdAtEpochMs),
        lastUpdatedAt = lastUpdatedAtEpochMs?.let(Instant::ofEpochMilli),
        lastAttemptAt = lastAttemptAtEpochMs?.let(Instant::ofEpochMilli),
        lastError = lastError,
        nodeCount = 0, // filled by UI via nodeDao count if needed
        enabled = enabled,
        userInfo = userInfoJson?.let { runCatching { json.decodeFromString<SubscriptionUserInfo>(it) }.getOrNull() },
        supportUrl = supportUrl,
        updateIntervalMinutes = updateIntervalMinutes,
    )

    private companion object {
        const val TAG = "SubscriptionRepository"
    }
}

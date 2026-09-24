package dev.typenil.vpnclient.core.subscription

import dev.typenil.vpnclient.core.common.log.SecureLog
import dev.typenil.vpnclient.core.subscription.model.RefreshOutcome
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
    private val scheduler: SubscriptionRefreshScheduler,
    private val settings: SubscriptionSettings,
    private val expiryNotifier: SubscriptionExpiryNotifier,
) {

    private val refreshMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    val profiles: Flow<List<SubscriptionProfile>> =
        subscriptionDao.observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun add(
        url: String,
        requestedName: String?,
        allowInsecureHttp: Boolean = false,
    ): Result<RefreshOutcome> {
        val trimmed = url.trim()
        val parsed = runCatching { trimmed.toHttpUrl() }.getOrNull()
            ?: return Result.failure(SubscriptionError.ParseFailed("bad url"))
        // Reject a cleartext URL before a row exists — a subscription that can
        // never fetch should not be persisted at all.
        if (!parsed.isHttps && !allowInsecureHttp) {
            return Result.failure(SubscriptionError.InsecureTransport)
        }
        val entity = SubscriptionEntity(
            name = requestedName?.trim().orEmpty().ifEmpty { deriveName(trimmed) },
            url = trimmed,
            // The flag is meaningless on https — don't persist dead state.
            allowInsecureHttp = allowInsecureHttp && !parsed.isHttps,
            createdAtEpochMs = Instant.now().toEpochMilli(),
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
        val id = subscriptionDao.insert(entity)
        val result = refresh(id)
        if (result.isFailure) {
            // A failed first refresh still gets a periodic job when the user
            // pinned a fixed interval — otherwise the subscription could never
            // recover on its own. Provider-following mode stays unscheduled:
            // there is no interval until a success supplies one.
            runCatching {
                val override = settings.autoRefreshMinutes.first()
                if (override > 0) {
                    scheduler.schedule(
                        subscriptionId = id,
                        providerMinutes = null,
                        userOverrideMinutes = override,
                        enabled = entity.enabled,
                    )
                }
            }.onFailure {
                SecureLog.w(TAG, "post-add scheduling failed sub=$id: ${it.javaClass.simpleName}")
            }
        }
        return result
    }

    suspend fun refresh(id: Long): Result<RefreshOutcome> = refreshMutex.withLock {
        val attemptAt = Instant.now().toEpochMilli()
        // `fetched` travels out of the try so post-commit bookkeeping can run
        // only on success — and can't falsify an already-committed refresh.
        var fetched: FetchedSubscription? = null
        val result = try {
            val sub = subscriptionDao.get(id) ?: throw SubscriptionError.NotFound
            val hwid = settings.getOrCreateHwid()
            var usedFallback = false
            val body = try {
                fetcher.fetch(sub.url, hwid, sub.allowInsecureHttp)
            } catch (e: SubscriptionError) {
                // Provider-published fallback URL: retry once on transport
                // failures only — HTTP/parse/policy errors are authoritative.
                val fallback = sub.fallbackUrl
                if ((e is SubscriptionError.Network || e is SubscriptionError.Timeout) &&
                    fallback != null
                ) {
                    SecureLog.i(TAG, "primary fetch failed sub=$id — trying fallback")
                    usedFallback = true
                    fetcher.fetch(fallback, hwid, sub.allowInsecureHttp)
                } else {
                    throw e
                }
            }
            fetched = body
            val classified = classifier.classify(body.body, body.contentType)
            // Parsing is CPU-bound over up to 8 MiB — keep it off the caller's
            // (often main) dispatcher. Duplicate node ids would emit duplicate
            // outbound tags — dedupe before validate + commit.
            val parsed = withContext(Dispatchers.Default) {
                dispatcher.parse(classified.format, classified.body, id)
            }
            val nodes = parsed.nodes.distinctBy { it.id }
            if (nodes.isEmpty()) throw SubscriptionError.EmptyResult()

            // Validate the candidate against the engine BEFORE touching the DB:
            // parsed-but-unusable nodes must never replace a working set.
            try {
                validator.validate(nodes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
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
                    userInfoJson = body.userInfo?.let { json.encodeToString(it) },
                    supportUrl = body.supportUrl,
                    updateIntervalMinutes = body.updateIntervalMinutes,
                    announce = body.announce,
                    updateAlways = body.updateAlways,
                    // A fallback response that doesn't re-publish its own
                    // fallback-url must not erase the endpoint that just
                    // worked — keep the stored one in that case.
                    fallbackUrl = body.fallbackUrl
                        ?: if (usedFallback) sub.fallbackUrl else null,
                )
            }
            SecureLog.i(TAG, "refreshed sub=$id nodes=${nodes.size} skipped=${parsed.skipped.size} fmt=${classified.format}")
            Result.success(RefreshOutcome(nodeCount = nodes.size, skipped = parsed.skipped))
        } catch (e: CancellationException) {
            throw e
        } catch (e: SubscriptionError) {
            SecureLog.w(TAG, "refresh failed sub=$id: ${e.safeMessage()}")
            runCatching { subscriptionDao.markAttempt(id, attemptAt, e.safeMessage()) }
            Result.failure(e)
        } catch (e: Exception) {
            // e.message can embed the request URL — persist a fixed string
            // and redact what reaches logcat.
            SecureLog.w(TAG, "refresh failed sub=$id: ${e.javaClass.simpleName}", e)
            runCatching { subscriptionDao.markAttempt(id, attemptAt, "unexpected error") }
            Result.failure(SubscriptionError.ParseFailed(e.javaClass.simpleName))
        }

        val committed = fetched
        if (result.isSuccess && committed != null) {
            // Post-commit bookkeeping must not flip a committed success into a
            // reported failure — log and move on.
            runCatching {
                var sub = subscriptionDao.get(id) ?: return@runCatching
                // Provider-declared migration: repoint the stored URL at the
                // new location. The header was already validated (public
                // http(s) URL / bare domain) at fetch time.
                val migrated = committed.movedPermanentlyTo ?: committed.newUrl
                    ?: committed.newDomain?.let { domain ->
                        runCatching {
                            sub.url.toHttpUrl().newBuilder().host(domain).build().toString()
                        }.getOrNull()
                    }
                if (migrated != null && migrated != sub.url &&
                    isMigrationAllowed(migrated, sub.allowInsecureHttp)
                ) {
                    subscriptionDao.update(sub.copy(url = migrated))
                    sub = subscriptionDao.get(id) ?: return@runCatching
                    SecureLog.i(TAG, "subscription migrated sub=$id")
                }
                // If the selected node vanished (disabled subs' nodes count as
                // unusable too), clear it so the next connect picks a sane
                // default. The conditional clear can't wipe a selection the
                // user made concurrently.
                val selected = settings.selectedNodeId.first()
                if (selected != null && nodeDao.getEnabled().none { it.id == selected }) {
                    settings.clearSelectedNodeIdIf(selected)
                    SecureLog.i(TAG, "cleared selection — selected node vanished in refresh")
                }
                // Apply profile-title only when the name is still the
                // auto-derived host — never overwrite a name the user typed.
                if (!committed.profileTitle.isNullOrBlank() &&
                    sub.name == deriveName(sub.url)
                ) {
                    subscriptionDao.update(sub.copy(name = committed.profileTitle))
                    sub = sub.copy(name = committed.profileTitle)
                }
                // (Re)register background refresh — the provider interval may
                // have changed, and a removed/re-added job must be reconciled.
                scheduler.schedule(
                    subscriptionId = id,
                    providerMinutes = committed.updateIntervalMinutes,
                    userOverrideMinutes = settings.autoRefreshMinutes.first(),
                    enabled = sub.enabled,
                )
                // Expiry alert: once per expiry value, inside the warning
                // window. The alerted-key embeds the expiry so a renewal
                // re-arms the alert.
                val expire = committed.userInfo?.expireEpochSeconds
                if (expiryAlertDue(
                        expire,
                        System.currentTimeMillis(),
                        alreadyAlerted = expire != null && expiryAlreadyAlerted(
                            settings.expiryAlerted.first(), id, expire,
                        ),
                    )
                ) {
                    if (expiryNotifier.notifyExpiring(id, sub.name, expire!!)) {
                        settings.markExpiryAlerted(expiryAlertKey(id, expire))
                    }
                }
            }.onFailure {
                SecureLog.w(TAG, "post-commit bookkeeping failed sub=$id: ${it.javaClass.simpleName}")
            }
        }
        return result
    }

    /**
     * Re-check persisted expiry on process start — the refresh path only
     * evaluates expiry after a successful fetch, so a manual-only or
     * long-interval subscription would never alert. Runs under
     * [refreshMutex]: the check→notify→mark sequence must be serialized
     * with refresh's own expiry check or both can notify for the same
     * expiry on startup.
     */
    suspend fun checkPersistedExpiryAlerts() = refreshMutex.withLock {
        subscriptionDao.getAll().forEach { sub ->
            val info = sub.userInfoJson
                ?.let { runCatching { json.decodeFromString<SubscriptionUserInfo>(it) }.getOrNull() }
            val expire = info?.expireEpochSeconds ?: return@forEach
            if (expiryAlertDue(
                    expire,
                    System.currentTimeMillis(),
                    alreadyAlerted = expiryAlreadyAlerted(
                        settings.expiryAlerted.first(), sub.id, expire,
                    ),
                )
            ) {
                if (expiryNotifier.notifyExpiring(sub.id, sub.name, expire)) {
                    settings.markExpiryAlerted(expiryAlertKey(sub.id, expire))
                }
            }
        }
    }

    suspend fun remove(id: Long) {
        // Serialized with refresh: a refresh that already fetched must commit
        // before the row disappears, not after — otherwise node rows would be
        // re-inserted against a deleted (later reusable) subscription id.
        refreshMutex.withLock {
            // Both deletes commit atomically — a crash between them would
            // orphan a subscription with no nodes and no scheduled recovery.
            // The work is cancelled only after commit so a failed delete
            // leaves the retry job in place.
            transactions.run {
                nodeDao.deleteForSubscription(id)
                subscriptionDao.delete(id)
            }
            scheduler.cancel(id)
        }
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
        is SubscriptionError.InsecureTransport -> "https required"
        is SubscriptionError.ForbiddenAddress -> "redirect to local address blocked"
        is SubscriptionError.DeviceLimitReached -> "device limit / HWID rejected"
        is SubscriptionError.NotFound -> "subscription removed"
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
        announce = announce,
        updateAlways = updateAlways,
        allowInsecureHttp = allowInsecureHttp,
    )

    private companion object {
        const val TAG = "SubscriptionRepository"
    }
}

/**
 * A provider-declared migration target is honored only when the stored
 * subscription's transport policy permits it: HTTPS always, cleartext HTTP
 * only under the per-subscription opt-in — otherwise the row would be
 * repointed somewhere the next fetch must reject, losing the working URL.
 */
internal fun isMigrationAllowed(migratedUrl: String, allowInsecureHttp: Boolean): Boolean {
    val url = runCatching { migratedUrl.toHttpUrl() }.getOrNull() ?: return false
    return url.isHttps || allowInsecureHttp
}

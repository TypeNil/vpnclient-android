package dev.typenil.vpnclient.core.subscription

import dev.typenil.vpnclient.core.common.log.SecureLog
import dev.typenil.vpnclient.core.subscription.model.NodeSelection
import dev.typenil.vpnclient.core.subscription.model.ProxyNode
import dev.typenil.vpnclient.core.subscription.model.RefreshOutcome
import dev.typenil.vpnclient.core.subscription.model.SkippedNode
import dev.typenil.vpnclient.core.subscription.model.SubscriptionError
import dev.typenil.vpnclient.core.subscription.model.SubscriptionFormat
import dev.typenil.vpnclient.core.subscription.model.SubscriptionProfile
import dev.typenil.vpnclient.core.subscription.model.SubscriptionUserInfo
import dev.typenil.vpnclient.data.db.DbTransactionRunner
import dev.typenil.vpnclient.data.db.NodeDao
import dev.typenil.vpnclient.data.db.NodeEntity
import dev.typenil.vpnclient.data.db.NodePreferenceDao
import dev.typenil.vpnclient.data.db.SubscriptionDao
import dev.typenil.vpnclient.data.db.SubscriptionEntity
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
 *
 * Locking: the slow phase of a refresh — network fetch, parse, engine
 * validation — runs WITHOUT any lock, so one subscription's slow provider
 * never blocks unrelated operations (another subscription's refresh, a pref
 * toggle, a remove). Everything else is guarded by per-subscription mutexes
 * plus three leaf mutexes:
 *
 *  - [subLocks] — one mutex per subscription id, serializing each row's
 *    commit-bearing operations ([refresh] phase 2, [editUrl], [remove],
 *    [setEnabled]). Entries are never removed: dropping one would let a
 *    fresh mutex race an in-flight commit for a recycled id, and the map is
 *    bounded by the subscriptions created in this process.
 *  - [prefsMutex] — node-preference invariants that span the node table:
 *    orphan pruning and every conditional selected-node clear. The clear
 *    itself is one atomic DataStore edit, so a concurrent user pick can't be
 *    wiped; the mutex keeps read-check-clear sequences from interleaving
 *    with commits.
 *  - [expiryMutex] — the check→notify→mark expiry sequence, shared by the
 *    refresh post-commit path and the startup scan.
 *  - [manualMutex] — manual sentinel row creation/deletion
 *    ([importShareLink], [removeManualNode]).
 *
 * Lock order — leaves never take subscription locks, so there are no cycles:
 * [lockFor] → [prefsMutex] / [expiryMutex], and [manualMutex] → [prefsMutex].
 */
@Singleton
class SubscriptionRepository
    @Inject
    constructor(
        private val subscriptionDao: SubscriptionDao,
        private val nodeDao: NodeDao,
        private val nodePreferenceDao: NodePreferenceDao,
        private val fetcher: SubscriptionFetcher,
        private val classifier: SubscriptionClassifier,
        private val dispatcher: SubscriptionParserDispatcher,
        private val validator: SubscriptionCandidateValidator,
        private val transactions: DbTransactionRunner,
        private val scheduler: SubscriptionRefreshScheduler,
        private val settings: SubscriptionSettings,
        private val expiryNotifier: SubscriptionExpiryNotifier,
        private val uriListParser: UriListParser,
    ) {
        /** Per-subscription commit locks — see the class kdoc for the scheme. */
        private val subLocks = ConcurrentHashMap<Long, Mutex>()

        /** Monotonic refresh-sequence per subscription id. Every [refresh]
         *  takes its ticket at call time; [commitRefresh] and [failRefresh]
         *  compare it under the row's lock, so a slow earlier attempt can
         *  never overwrite a newer success/failure for the same URL — the
         *  fetchedUrl check alone can't tell two same-URL refreshes apart. */
        private val refreshSeq = ConcurrentHashMap<Long, java.util.concurrent.atomic.AtomicLong>()

        private fun nextRefreshSeq(id: Long): Long =
            refreshSeq.computeIfAbsent(id) { java.util.concurrent.atomic.AtomicLong() }
                .incrementAndGet()

        private fun isStaleAttempt(
            id: Long,
            seq: Long,
        ): Boolean =
            (refreshSeq[id]?.get() ?: seq) != seq

        private val prefsMutex = Mutex()
        private val expiryMutex = Mutex()
        private val manualMutex = Mutex()

        private val json = Json { ignoreUnknownKeys = true }

        private fun lockFor(id: Long): Mutex = subLocks.computeIfAbsent(id) { Mutex() }

        val profiles: Flow<List<SubscriptionProfile>> =
            subscriptionDao.observeAll().map { list -> list.map { it.toDomain() } }

        /** Nodes of the manual/local sentinel row in display order — drives
         *  the detail sheet's per-node delete list. Empty until the first
         *  import creates the row. */
        val manualNodes: Flow<List<NodeEntity>> =
            nodeDao.observeForSubscriptionUrl(MANUAL_SUBSCRIPTION_URL)

        suspend fun add(
            url: String,
            requestedName: String?,
            allowInsecureHttp: Boolean = false,
        ): Result<RefreshOutcome> {
            val trimmed = url.trim()
            val parsed =
                runCatching { trimmed.toHttpUrl() }.getOrNull()
                    ?: return Result.failure(SubscriptionError.ParseFailed("bad url"))
            // Reject a cleartext URL before a row exists — a subscription that can
            // never fetch should not be persisted at all.
            if (!parsed.isHttps && !allowInsecureHttp) {
                return Result.failure(SubscriptionError.InsecureTransport)
            }
            val entity =
                SubscriptionEntity(
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

        /**
         * Refresh one subscription in two phases: everything slow (fetch,
         * parse, engine validation) runs outside any lock in
         * [prepareRefresh]; only the commit + post-commit bookkeeping hold
         * this subscription's lock in [commitRefresh]. A slow provider
         * therefore never blocks other subscriptions or user operations.
         */
        suspend fun refresh(id: Long): Result<RefreshOutcome> {
            val attemptAt = Instant.now().toEpochMilli()
            val seq = nextRefreshSeq(id)
            // Phase 1 — no lock held. The fetch is cancellable (OkHttp call
            // cancellation propagates), so a cancelled caller frees nothing
            // because it never held anything.
            val prepared =
                try {
                    prepareRefresh(id, attemptAt)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Stale attempt: a newer refresh already ran — don't
                    // overwrite its lastError with this one's late failure.
                    return if (isStaleAttempt(id, seq)) {
                        Result.failure(e as? SubscriptionError
                            ?: SubscriptionError.ParseFailed(e.javaClass.simpleName))
                    } else {
                        lockFor(id).withLock {
                            if (isStaleAttempt(id, seq)) {
                                Result.failure(e as? SubscriptionError
                                    ?: SubscriptionError.ParseFailed(e.javaClass.simpleName))
                            } else {
                                Result.failure(failRefresh(id, attemptAt, e))
                            }
                        }
                    }
                }
            // Phase 2 — commit under this subscription's own lock. The catch
            // keeps the Result contract: a DB failure must surface as a typed
            // failure, not escape as an exception.
            return lockFor(id).withLock {
                if (isStaleAttempt(id, seq)) {
                    // A newer refresh is in flight (or already committed) —
                    // this candidate must not overwrite its node set.
                    SecureLog.i(TAG, "dropping stale refresh result sub=$id")
                    return@withLock Result.failure(SubscriptionError.NotFound)
                }
                try {
                    commitRefresh(id, prepared)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(failRefresh(id, prepared.attemptAt, e))
                }
            }
        }

        /**
         * Refresh with [lockFor] already held — the stale-candidate re-pass
         * of [commitRefresh] and [editUrl]'s same-URL delegation land here.
         * Failure logging stays "refresh failed" in both callers.
         */
        private suspend fun refreshLocked(id: Long): Result<RefreshOutcome> {
            val attemptAt = Instant.now().toEpochMilli()
            return try {
                commitRefresh(id, prepareRefresh(id, attemptAt))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(failRefresh(id, attemptAt, e))
            }
        }

        /**
         * Phase 1 of [refresh]: row lookup, HWID, the network fetch (with the
         * provider fallback retried once on transport failures), and the full
         * parse + engine validation. No DB writes — the only side effect is
         * `getOrCreateHwid()` creating the device id on first use, which is a
         * single atomic DataStore edit and needs no lock.
         */
        private suspend fun prepareRefresh(
            id: Long,
            attemptAt: Long,
        ): PreparedRefresh {
            val sub = subscriptionDao.get(id) ?: throw SubscriptionError.NotFound
            // The manual sentinel row is local-only — it must never
            // be fetched: no refresh job is enqueued for it and
            // updateAlways is never set, so reaching here is a
            // caller bug. Fail fast rather than feed the fetcher a
            // non-URL.
            if (isManualSubscription(sub.url)) throw SubscriptionError.NotFound
            val hwid = settings.getOrCreateHwid()
            var usedFallback = false
            val body =
                try {
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
            val candidate = parseAndValidate(id, body.body, body.contentType)
            return PreparedRefresh(
                // The row URL the body was fetched from — commitRefresh re-reads
                // the row and re-fetches when an editUrl changed it mid-flight.
                fetchedUrl = sub.url,
                attemptAt = attemptAt,
                usedFallback = usedFallback,
                body = body,
                candidate = candidate,
            )
        }

        /**
         * Classify → parse (off the caller's dispatcher) → dedupe → engine
         * validation. Shared by [refresh] and [editUrl]; performs no DB
         * writes, so it is safe to run outside any lock.
         */
        private suspend fun parseAndValidate(
            id: Long,
            body: ByteArray,
            contentType: String?,
        ): ParsedCandidate {
            val classified = classifier.classify(body, contentType)
            // Parsing is CPU-bound over up to 8 MiB — keep it off the caller's
            // (often main) dispatcher. Duplicate node ids would emit duplicate
            // outbound tags — dedupe before validate + commit.
            val parsed =
                withContext(Dispatchers.Default) {
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

            return ParsedCandidate(
                entities =
                    nodes.mapIndexed { index, n ->
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
                    },
                skipped = parsed.skipped,
                format = classified.format,
            )
        }

        /**
         * Phase 2 of [refresh]: assumes [lockFor] is held. Re-reads the row
         * first — if it vanished, nothing is written; if an [editUrl] changed
         * the URL while phase 1 was in flight, the prepared candidate is
         * stale and the whole refresh re-runs under the held lock (editUrl
         * holds the same lock across its own fetch + commit, so the URL
         * cannot change again). Throws on failure; callers map to Result.
         */
        private suspend fun commitRefresh(
            id: Long,
            prepared: PreparedRefresh,
        ): Result<RefreshOutcome> {
            val current = subscriptionDao.get(id) ?: throw SubscriptionError.NotFound
            if (current.url != prepared.fetchedUrl) {
                SecureLog.i(TAG, "url changed mid-refresh sub=$id — re-fetching under lock")
                return refreshLocked(id)
            }
            // Node swap + success metadata commit atomically — a crash between
            // them can't leave nodes updated but the subscription flagged stale.
            transactions.run {
                nodeDao.replaceForSubscription(id, prepared.candidate.entities)
                subscriptionDao.markSuccess(
                    id = id,
                    updatedAt = Instant.now().toEpochMilli(),
                    attemptAt = prepared.attemptAt,
                    userInfoJson = prepared.body.userInfo?.let { json.encodeToString(it) },
                    supportUrl = prepared.body.supportUrl,
                    updateIntervalMinutes = prepared.body.updateIntervalMinutes,
                    announce = prepared.body.announce,
                    updateAlways = prepared.body.updateAlways,
                    // A fallback response that doesn't re-publish its own
                    // fallback-url must not erase the endpoint that just
                    // worked — keep the stored one in that case.
                    fallbackUrl =
                        prepared.body.fallbackUrl
                            ?: if (prepared.usedFallback) current.fallbackUrl else null,
                )
            }
            SecureLog.i(
                TAG,
                "refreshed sub=$id nodes=${prepared.candidate.entities.size}" +
                    " skipped=${prepared.candidate.skipped.size} fmt=${prepared.candidate.format}",
            )
            postCommitRefresh(id, prepared.body)
            return Result.success(
                RefreshOutcome(
                    nodeCount = prepared.candidate.entities.size,
                    skipped = prepared.candidate.skipped,
                ),
            )
        }

        /**
         * Post-commit bookkeeping for [refresh] — runs while the subscription
         * lock is still held and must not flip a committed success into a
         * reported failure, so every failure is logged and swallowed.
         */
        private suspend fun postCommitRefresh(
            id: Long,
            committed: FetchedSubscription,
        ) {
            runCatching {
                var sub = subscriptionDao.get(id) ?: return@runCatching
                // Provider-declared migration: repoint the stored URL at the
                // new location. The header was already validated (public
                // http(s) URL / bare domain) at fetch time.
                val migrated =
                    committed.movedPermanentlyTo ?: committed.newUrl
                        ?: committed.newDomain?.let { domain ->
                            runCatching {
                                sub.url
                                    .toHttpUrl()
                                    .newBuilder()
                                    .host(domain)
                                    .build()
                                    .toString()
                            }.getOrNull()
                        }
                if (migrated != null && migrated != sub.url &&
                    isMigrationAllowed(migrated, sub.allowInsecureHttp)
                ) {
                    subscriptionDao.update(sub.copy(url = migrated))
                    sub = subscriptionDao.get(id) ?: return@runCatching
                    SecureLog.i(TAG, "subscription migrated sub=$id")
                }
                prefsMutex.withLock {
                    // A refresh can retire node ids (credential rotation gives a
                    // node a new id). Prefs are never migrated across ids — the
                    // match would be a guess — but orphaned rows are pruned so
                    // the table can't grow unboundedly.
                    nodePreferenceDao.deleteOrphans()
                    // If the selected node vanished (disabled subs' nodes count as
                    // unusable too), clear it so the next connect picks a sane
                    // default. The conditional clear can't wipe a selection the
                    // user made concurrently. The Auto sentinel has no node row —
                    // it must never be treated as a vanished selection.
                    val selected = settings.selectedNodeId.first()
                    // Usable = enabled-sub AND not pref-disabled — a selection
                    // pointing at a user-disabled node must fall back too.
                    if (selected != null && selected != NodeSelection.AUTO_ID &&
                        nodeDao.getUsable().none { it.id == selected }
                    ) {
                        settings.clearSelectedNodeIdIf(selected)
                        SecureLog.i(TAG, "cleared selection — selected node vanished in refresh")
                    }
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
                // re-arms the alert. The check→notify→mark sequence is
                // serialized with the startup scan via [expiryMutex].
                expiryMutex.withLock {
                    val expire = committed.userInfo?.expireEpochSeconds
                    if (expiryAlertDue(
                            expire,
                            System.currentTimeMillis(),
                            alreadyAlerted =
                                expire != null &&
                                    expiryAlreadyAlerted(
                                        settings.expiryAlerted.first(),
                                        id,
                                        expire,
                                    ),
                        )
                    ) {
                        if (expiryNotifier.notifyExpiring(id, sub.name, expire!!)) {
                            settings.markExpiryAlerted(expiryAlertKey(id, expire))
                        }
                    }
                }
            }.onFailure {
                SecureLog.w(TAG, "post-commit bookkeeping failed sub=$id: ${it.javaClass.simpleName}")
            }
        }

        /**
         * Import pasted share links (`vless://…`, `vmess://…`, …) as manual
         * nodes. The nodes are stored under the reserved
         * [MANUAL_SUBSCRIPTION_URL] row — a sentinel that is never fetched
         * (refresh() fails fast on it, no refresh job is ever enqueued for
         * it), so its node rows are append/upsert only, not
         * replace-per-refresh.
         *
         * The same last-known-good contract as [refresh] applies: the parse
         * AND the engine validation must pass before anything is committed,
         * and a multi-line paste imports every usable link. A sentinel row
         * created for a failed first import is removed again so an empty
         * "Manual servers" entry never lingers.
         *
         * Idempotent on node id: node ids are content hashes salted with the
         * manual row's id, so re-importing a known link upserts the same row
         * in place and the same link under a remote subscription stays a
         * distinct row. The parse runs off the calling dispatcher.
         *
         * Guarded by [manualMutex] end to end: two concurrent imports must
         * not both create a sentinel row, and [removeManualNode] must not
         * drop the row between this lookup and the commit.
         */
        suspend fun importShareLink(uri: String): Result<RefreshOutcome> =
            manualMutex.withLock {
                var subId: Long? = null
                var createdRow = false
                var committed = false
                try {
                    subId = subscriptionDao.findIdByUrl(MANUAL_SUBSCRIPTION_URL)
                    if (subId == null) {
                        // Insert the sentinel row first — node identity is
                        // salted with the subscription id, so the parse must
                        // run against the id that will persist the node.
                        subId = subscriptionDao.insert(manualSubscriptionEntity())
                        createdRow = true
                    }
                    val parsed =
                        withContext(Dispatchers.Default) {
                            uriListParser.parse(uri.trim(), subId)
                        }
                    // Nothing commits before the engine accepts the candidate —
                    // a parser-valid link whose outbound sing-box rejects would
                    // otherwise break the compiled config of every other
                    // subscription too.
                    try {
                        validator.validate(parsed.nodes)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        throw SubscriptionError.ConfigRejected
                    }
                    // Append/upsert: a re-import REPLACEs the same primary
                    // key in place; ids are deduped by construction. Every
                    // parsed node is kept — the paste was the user's intent.
                    val basePosition =
                        nodeDao
                            .forSubscription(subId)
                            .maxOfOrNull { it.position + 1 } ?: 0
                    val entities =
                        parsed.nodes.mapIndexed { index, node ->
                            node.toEntity(subId, basePosition + index)
                        }
                    transactions.run { nodeDao.upsertAll(entities) }
                    committed = true
                    SecureLog.i(TAG, "share-link imported sub=$subId nodes=${entities.size}")
                    Result.success(
                        RefreshOutcome(nodeCount = entities.size, skipped = parsed.skipped),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SubscriptionError) {
                    SecureLog.w(TAG, "share-link import failed: ${e.safeMessage()}")
                    Result.failure(e)
                } catch (e: Exception) {
                    // e.message can embed the pasted URI — log the type only.
                    SecureLog.w(TAG, "share-link import failed: ${e.javaClass.simpleName}", e)
                    Result.failure(SubscriptionError.ParseFailed(e.javaClass.simpleName))
                } finally {
                    // In `finally` so the cancellation path is covered too —
                    // an aborted first import must not leave an empty
                    // "Manual servers" row behind. NonCancellable: the write
                    // has to outlive the cancelled caller.
                    if (!committed) {
                        withContext(NonCancellable) { dropEmptyManualRow(subId, createdRow) }
                    }
                }
            }

        /**
         * Remove the manual row when this call created it and nothing was
         * committed into it — a failed first import must not leave an empty
         * "Manual servers" entry behind. Best-effort: the row is recreated
         * on the next import anyway.
         */
        private suspend fun dropEmptyManualRow(subId: Long?, createdRow: Boolean) {
            if (!createdRow || subId == null) return
            runCatching {
                if (nodeDao.countForSubscription(subId) == 0) {
                    subscriptionDao.delete(subId)
                }
            }.onFailure {
                SecureLog.w(TAG, "manual row rollback failed: ${it.javaClass.simpleName}")
            }
        }

        private fun ProxyNode.toEntity(
            subscriptionId: Long,
            position: Int,
        ) = NodeEntity(
            id = id,
            subscriptionId = subscriptionId,
            name = name,
            protocol = protocol.name,
            server = server,
            port = port,
            outboundJson = outboundJson,
            rawUri = rawUri,
            position = position,
        )

        private fun manualSubscriptionEntity() =
            SubscriptionEntity(
                name = MANUAL_SUBSCRIPTION_NAME,
                url = MANUAL_SUBSCRIPTION_URL,
                createdAtEpochMs = Instant.now().toEpochMilli(),
                // Populated at import so the card doesn't claim the row was
                // never refreshed.
                lastUpdatedAtEpochMs = Instant.now().toEpochMilli(),
                lastAttemptAtEpochMs = null,
                lastError = null,
                enabled = true,
                userInfoJson = null,
                supportUrl = null,
                updateIntervalMinutes = null,
                announce = null,
                fallbackUrl = null,
                allowInsecureHttp = false,
            )

        /**
         * Re-check persisted expiry on process start — the refresh path only
         * evaluates expiry after a successful fetch, so a manual-only or
         * long-interval subscription would never alert. Runs under
         * [expiryMutex]: the check→notify→mark sequence must be serialized
         * with refresh's own expiry check or both can notify for the same
         * expiry on startup.
         */
        suspend fun checkPersistedExpiryAlerts() =
            expiryMutex.withLock {
                subscriptionDao.getAll().forEach { sub ->
                    val info =
                        sub.userInfoJson
                            ?.let { runCatching { json.decodeFromString<SubscriptionUserInfo>(it) }.getOrNull() }
                    val expire = info?.expireEpochSeconds ?: return@forEach
                    if (expiryAlertDue(
                            expire,
                            System.currentTimeMillis(),
                            alreadyAlerted =
                                expiryAlreadyAlerted(
                                    settings.expiryAlerted.first(),
                                    sub.id,
                                    expire,
                                ),
                        )
                    ) {
                        if (expiryNotifier.notifyExpiring(sub.id, sub.name, expire)) {
                            settings.markExpiryAlerted(expiryAlertKey(sub.id, expire))
                        }
                    }
                }
            }

        /**
         * Flip the enabled flag and reconcile the world around it:
         *  - the periodic refresh job is re-resolved (schedule() cancels the
         *    job when `enabled = false`, re-enqueues when re-enabled under the
         *    current override/provider interval);
         *  - a selected node owned by a now-disabled subscription is cleared
         *    with the same conditional clear refresh uses — the selection can't
         *    silently point at a node the compile path no longer emits.
         */
        suspend fun setEnabled(
            id: Long,
            enabled: Boolean,
        ) = lockFor(id).withLock {
            val sub = subscriptionDao.get(id) ?: return@withLock
            subscriptionDao.setEnabled(id, enabled)
            if (!enabled) {
                prefsMutex.withLock {
                    val selected = settings.selectedNodeId.first()
                    if (selected != null &&
                        nodeDao.forSubscription(id).any { it.id == selected }
                    ) {
                        settings.clearSelectedNodeIdIf(selected)
                    }
                }
            }
            runCatching {
                scheduler.schedule(
                    subscriptionId = id,
                    providerMinutes = sub.updateIntervalMinutes,
                    userOverrideMinutes = settings.autoRefreshMinutes.first(),
                    enabled = enabled,
                )
            }.onFailure {
                SecureLog.w(TAG, "reschedule after toggle failed sub=$id: ${it.javaClass.simpleName}")
            }
        }

        /** User-visible name; blank input keeps the current one. */
        suspend fun rename(
            id: Long,
            name: String,
        ) {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) return
            subscriptionDao.updateName(id, trimmed)
        }

        /**
         * Repoint the subscription at [newUrl]: the candidate is fetched,
         * classified, parsed, and engine-validated through the same pipeline as
         * [refresh] BEFORE anything commits. On success the URL repoint, the
         * node swap, and the success metadata land in a single transaction — a
         * failure anywhere leaves the stored URL and the last-known-good nodes
         * untouched.
         *
         * The whole fetch + commit runs under this subscription's lock: a
         * concurrent [refresh] of the same row either commits first (this
         * edit then proceeds normally) or sees the new URL in its phase-2
         * re-read and re-fetches — a stale candidate is never committed.
         */
        suspend fun editUrl(
            id: Long,
            newUrl: String,
        ): Result<RefreshOutcome> =
            lockFor(id).withLock {
                val trimmed = newUrl.trim()
                val attemptAt = Instant.now().toEpochMilli()
                val result =
                    try {
                        val sub = subscriptionDao.get(id) ?: throw SubscriptionError.NotFound
                        val candidate =
                            runCatching { trimmed.toHttpUrl() }.getOrNull()
                                ?: throw SubscriptionError.ParseFailed("bad url")
                        // The transport gate is the stored per-subscription opt-in,
                        // same as refresh() — an https row pointed at cleartext is
                        // rejected unless the user opted in.
                        if (!candidate.isHttps && !sub.allowInsecureHttp) {
                            throw SubscriptionError.InsecureTransport
                        }
                        if (trimmed == sub.url) {
                            return@withLock refreshLocked(id)
                        }
                        val body = fetcher.fetch(trimmed, settings.getOrCreateHwid(), sub.allowInsecureHttp)
                        val parsed = parseAndValidate(id, body.body, body.contentType)
                        // URL repoint + node swap + success metadata atomically.
                        transactions.run {
                            nodeDao.replaceForSubscription(id, parsed.entities)
                            subscriptionDao.updateUrlAndMarkSuccess(
                                id = id,
                                url = trimmed,
                                updatedAt = Instant.now().toEpochMilli(),
                                attemptAt = attemptAt,
                                userInfoJson = body.userInfo?.let { json.encodeToString(it) },
                                supportUrl = body.supportUrl,
                                updateIntervalMinutes = body.updateIntervalMinutes,
                                announce = body.announce,
                                updateAlways = body.updateAlways,
                                fallbackUrl = body.fallbackUrl,
                            )
                        }
                        SecureLog.i(TAG, "edited url sub=$id nodes=${parsed.entities.size} skipped=${parsed.skipped.size} fmt=${parsed.format}")
                        Result.success(
                            RefreshOutcome(nodeCount = parsed.entities.size, skipped = parsed.skipped),
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: SubscriptionError) {
                        SecureLog.w(TAG, "editUrl failed sub=$id: ${e.safeMessage()}")
                        runCatching { subscriptionDao.markAttempt(id, attemptAt, e.safeMessage()) }
                        Result.failure(e)
                    } catch (e: Exception) {
                        SecureLog.w(TAG, "editUrl failed sub=$id: ${e.javaClass.simpleName}", e)
                        runCatching { subscriptionDao.markAttempt(id, attemptAt, "unexpected error") }
                        Result.failure(SubscriptionError.ParseFailed(e.javaClass.simpleName))
                    }

                if (result.isSuccess) {
                    // A replaced node set can drop the selected node — same
                    // conditional cleanup refresh runs post-commit.
                    runCatching {
                        prefsMutex.withLock {
                            val selected = settings.selectedNodeId.first()
                            // AUTO_ID has no node row — never a vanished selection.
                            // Same usable-set rule as refresh: a pref-disabled
                            // selected node must fall back, not compile-fail.
                            if (selected != null && selected != NodeSelection.AUTO_ID &&
                                nodeDao.getUsable().none { it.id == selected }
                            ) {
                                settings.clearSelectedNodeIdIf(selected)
                            }
                        }
                        val sub = subscriptionDao.get(id)
                        if (sub != null) {
                            scheduler.schedule(
                                subscriptionId = id,
                                providerMinutes = sub.updateIntervalMinutes,
                                userOverrideMinutes = settings.autoRefreshMinutes.first(),
                                enabled = sub.enabled,
                            )
                        }
                    }.onFailure {
                        SecureLog.w(TAG, "post-editUrl bookkeeping failed sub=$id: ${it.javaClass.simpleName}")
                    }
                }
                return result
            }

        /**
         * Delete one manually imported node. The manual row's nodes are
         * append-only (no refresh replaces them), so this is the only way to
         * undo a bad paste. The row itself goes once its last node does — an
         * empty "Manual servers" entry has nothing to show and would be
         * recreated on the next import anyway.
         */
        suspend fun removeManualNode(nodeId: String) =
            manualMutex.withLock {
                val node = nodeDao.get(nodeId) ?: return@withLock
                val sub = subscriptionDao.get(node.subscriptionId) ?: return@withLock
                // Only the manual sentinel is per-node removable — a remote
                // subscription's nodes are owned by its refresh.
                if (!isManualSubscription(sub.url)) return@withLock
                prefsMutex.withLock {
                    // One transaction: the row cleanup must not be a second
                    // write that re-emits the node set and triggers a duplicate
                    // tunnel rebuild for a single logical change.
                    transactions.run {
                        nodeDao.delete(nodeId)
                        // The node's user prefs die with it — manual nodes are
                        // never re-created, so a pref row could only linger as
                        // an orphan.
                        nodePreferenceDao.delete(nodeId)
                        // Nothing left to show — the sentinel row goes too, so an
                        // empty "Manual servers" entry never lingers.
                        if (nodeDao.countForSubscription(node.subscriptionId) == 0) {
                            subscriptionDao.delete(node.subscriptionId)
                        }
                    }
                    // A selection can't outlive its node — same conditional clear
                    // the refresh path uses (a concurrent pick isn't wiped).
                    settings.clearSelectedNodeIdIf(nodeId)
                }
                SecureLog.i(TAG, "manual node removed sub=${node.subscriptionId}")
            }

        suspend fun remove(id: Long) {
            // Serialized with this row's refresh commit: a refresh that already
            // fetched must commit before the row disappears, not after —
            // otherwise node rows would be re-inserted against a deleted
            // (later reusable) subscription id. The slow fetch phase stays
            // outside the lock, so remove() is never blocked by a hung fetch;
            // the committing refresh re-reads the row and reports NotFound.
            lockFor(id).withLock {
                // The manual sentinel row is structural — the UI hides its
                // delete affordance; refuse a stray call so the row can't
                // vanish and orphan its node rows' owner name.
                val sub = subscriptionDao.get(id) ?: return@withLock
                if (isManualSubscription(sub.url)) {
                    SecureLog.w(TAG, "refused to remove manual sentinel sub=$id")
                    return@withLock
                }
                prefsMutex.withLock {
                    // Both deletes commit atomically — a crash between them would
                    // orphan a subscription with no nodes and no scheduled recovery.
                    // The work is cancelled only after commit so a failed delete
                    // leaves the retry job in place.
                    transactions.run {
                        // Prefs go first while the node rows still exist — the
                        // DAO prunes by sub-select on nodes, so ordering matters.
                        nodePreferenceDao.deleteForSubscription(id)
                        nodeDao.deleteForSubscription(id)
                        subscriptionDao.delete(id)
                    }
                }
                scheduler.cancel(id)
            }
        }

        // ---- per-node user preferences (slice: favorites → node control) ----

        /**
         * Toggle a node's favorite flag. Guarded by [prefsMutex] — a commit
         * can't observe a half-applied prefs write, and the toggle never
         * waits on an unrelated subscription's refresh. Prefs are stored by
         * node id and survive node-table replacement.
         */
        suspend fun setNodeFavorite(
            nodeId: String,
            favorite: Boolean,
        ) = prefsMutex.withLock {
            if (nodeDao.get(nodeId) == null) return@withLock
            nodePreferenceDao.setFavorite(nodeId, favorite)
        }

        /** Local display name override — blank clears back to provider name. */
        suspend fun setNodeCustomName(
            nodeId: String,
            customName: String?,
        ) = prefsMutex.withLock {
            if (nodeDao.get(nodeId) == null) return@withLock
            nodePreferenceDao.setCustomName(nodeId, customName?.takeIf { it.isNotBlank() })
        }

        /** Hidden nodes disappear from user lists but stay in the effective
         *  node set (hiding is presentation; disabling is routing). */
        suspend fun setNodeHidden(
            nodeId: String,
            hidden: Boolean,
        ) = prefsMutex.withLock {
            if (nodeDao.get(nodeId) == null) return@withLock
            nodePreferenceDao.setHidden(nodeId, hidden)
        }

        /**
         * Enable/disable a node for VPN use. Disabling the currently selected
         *  node falls back the selection (same conditional clear refresh uses
         *  — a concurrent user pick isn't wiped). Node-set fingerprint changes
         *  follow automatically via observeUsable, rebuilding a live session.
         */
        suspend fun setNodeEnabled(
            nodeId: String,
            enabled: Boolean,
        ) = prefsMutex.withLock {
            if (nodeDao.get(nodeId) == null) return@withLock
            nodePreferenceDao.setEnabled(nodeId, enabled)
            if (!enabled) {
                val selected = settings.selectedNodeId.first()
                if (selected == nodeId) {
                    settings.clearSelectedNodeIdIf(nodeId)
                }
            }
        }

        /**
         * Record a failed attempt and return the typed failure to report.
         * Non-subscription throwables are collapsed to a fixed string — their
         * messages can embed request URLs. Marking a row that no longer
         * exists is a harmless no-op.
         */
        private suspend fun failRefresh(
            id: Long,
            attemptAt: Long,
            e: Exception,
        ): SubscriptionError {
            val typed = e as? SubscriptionError ?: SubscriptionError.ParseFailed(e.javaClass.simpleName)
            val persisted =
                if (e is SubscriptionError) {
                    SecureLog.w(TAG, "refresh failed sub=$id: ${e.safeMessage()}")
                    e.safeMessage()
                } else {
                    SecureLog.w(TAG, "refresh failed sub=$id: ${e.javaClass.simpleName}", e)
                    "unexpected error"
                }
            runCatching { subscriptionDao.markAttempt(id, attemptAt, persisted) }
            return typed
        }

        /** Phase-1 output of [refresh]: everything needed to commit, captured
         *  before the subscription lock is taken. */
        private data class PreparedRefresh(
            val fetchedUrl: String,
            val attemptAt: Long,
            val usedFallback: Boolean,
            val body: FetchedSubscription,
            val candidate: ParsedCandidate,
        )

        /** Parse + validation output shared by [refresh] and [editUrl]. */
        private data class ParsedCandidate(
            val entities: List<NodeEntity>,
            val skipped: List<SkippedNode>,
            val format: SubscriptionFormat,
        )

        private fun deriveName(url: String): String = runCatching { url.toHttpUrl().host }.getOrNull() ?: "subscription"

        private fun SubscriptionError.safeMessage(): String =
            when (this) {
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

        private fun SubscriptionEntity.toDomain(): SubscriptionProfile =
            SubscriptionProfile(
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

        companion object {
            /**
             * Reserved `url` for the manual/local subscription row. The `manual://`
             * scheme is deliberately not http(s): fetchers only accept http(s),
             * so any path that treated the row as fetchable would fail — this
             * check just makes the failure fast and explicit. `url` is never
             * rendered raw (UI shows a redacted host or a flag check).
             */
            const val MANUAL_SUBSCRIPTION_URL = "manual://local"
            const val MANUAL_SUBSCRIPTION_NAME = "Manual servers"

            /** True for the manual row's [SubscriptionProfile] — not removable,
             *  not refreshable, not rendered as a normal subscription. */
            fun isManualSubscription(url: String): Boolean = url == MANUAL_SUBSCRIPTION_URL

            private const val TAG = "SubscriptionRepository"
        }
    }

/**
 * A provider-declared migration target is honored only when the stored
 * subscription's transport policy permits it: HTTPS always, cleartext HTTP
 * only under the per-subscription opt-in — otherwise the row would be
 * repointed somewhere the next fetch must reject, losing the working URL.
 */
internal fun isMigrationAllowed(
    migratedUrl: String,
    allowInsecureHttp: Boolean,
): Boolean {
    val url = runCatching { migratedUrl.toHttpUrl() }.getOrNull() ?: return false
    return url.isHttps || allowInsecureHttp
}

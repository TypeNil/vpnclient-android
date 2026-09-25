package dev.typenil.vpnclient.ui.subscriptions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.typenil.vpnclient.core.subscription.SubscriptionRepository
import dev.typenil.vpnclient.core.subscription.model.SkippedNode
import dev.typenil.vpnclient.core.subscription.model.SubscriptionProfile
import dev.typenil.vpnclient.data.db.NodeDao
import dev.typenil.vpnclient.data.db.NodeEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A snackbar message waiting to be shown — held in state so it survives
 *  the destination not being composed when the failure lands. [id] lets the
 *  screen re-trigger its effect when one message replaces another. */
data class PendingMessage(
    val id: Long,
    val text: String,
)

data class SubscriptionsUiState(
    val profiles: List<SubscriptionProfile> = emptyList(),
    /** subscriptionId → enabled node count (computed from observeEnabled). */
    val nodeCounts: Map<Long, Int> = emptyMap(),
    /** Manually imported nodes in display order — the manual row's detail
     *  sheet lists them so a bad paste can be undone. */
    val manualNodes: List<NodeEntity> = emptyList(),
    val refreshingIds: Set<Long> = emptySet(),
    val pendingMessage: PendingMessage? = null,
)

@HiltViewModel
class SubscriptionsViewModel
    @Inject
    constructor(
        private val repository: SubscriptionRepository,
        nodeDao: NodeDao,
    ) : ViewModel() {
        private val pendingMessage = MutableStateFlow<PendingMessage?>(null)

        private val refreshing = MutableStateFlow<Set<Long>>(emptySet())

        val uiState: StateFlow<SubscriptionsUiState> =
            combine(
                repository.profiles,
                nodeDao.observeEnabled(),
                refreshing,
                pendingMessage,
                repository.manualNodes,
            ) { profiles, nodes, refreshingIds, message, manualNodes ->
                SubscriptionsUiState(
                    profiles = profiles,
                    nodeCounts = nodes.groupingBy { it.subscriptionId }.eachCount(),
                    manualNodes = manualNodes,
                    refreshingIds = refreshingIds,
                    pendingMessage = message,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = SubscriptionsUiState(),
            )

        /** Queue a snackbar message; a newer failure replaces an unshown one.
         *  IDs come from a monotonic counter — a cleared message can't reuse a
         *  predecessor's id and lose its LaunchedEffect trigger. */
        private var nextMessageId = 0L

        private fun postMessage(text: String) {
            pendingMessage.update { PendingMessage(id = ++nextMessageId, text = text) }
        }

        /** The screen consumed the message — clear it only if it's still the
         *  one that was displayed (a newer message may have replaced it). */
        fun acknowledgeMessage(id: Long) {
            pendingMessage.update { if (it?.id == id) null else it }
        }

        private fun skippedSummary(skipped: List<SkippedNode>): String {
            val grouped = skipped.groupingBy { it.reason.take(MAX_REASON_LEN) }.eachCount()
            val shown =
                grouped.entries
                    .take(MAX_REASON_GROUPS)
                    .joinToString(", ") { (reason, count) -> "$reason ($count)" }
            val rest = grouped.size - MAX_REASON_GROUPS
            return "${skipped.size} nodes skipped: $shown" +
                if (rest > 0) ", +$rest more" else ""
        }

        fun add(
            url: String,
            requestedName: String?,
            allowInsecureHttp: Boolean = false,
        ) {
            viewModelScope.launch {
                val trimmed = url.trim()
                // A non-http(s) input that carries a share-link scheme is a single
                // node import, not a subscription — route it to the manual-import
                // path. Anything else keeps the existing add() flow and its error.
                val isSubscriptionUrl =
                    trimmed.startsWith("https://", ignoreCase = true) ||
                        trimmed.startsWith("http://", ignoreCase = true)
                val result =
                    if (!isSubscriptionUrl && isShareLink(trimmed)) {
                        repository.importShareLink(trimmed)
                    } else {
                        repository.add(trimmed, requestedName?.trim()?.ifEmpty { null }, allowInsecureHttp)
                    }
                result
                    .onSuccess { outcome ->
                        if (outcome.skipped.isNotEmpty()) {
                            postMessage(skippedSummary(outcome.skipped))
                        }
                    }.onFailure { postMessage(it.message ?: "Failed to add") }
            }
        }

        /**
         * `scheme://` where scheme is one UriListParser accepts — the signal the
         * pasted text is a node share link. Pure string check (no parsing) so it
         * stays cheap on the main thread; the real parse runs in the repository
         * off-dispatcher and reports skipped/typed errors.
         */
        private fun isShareLink(text: String): Boolean {
            val scheme =
                text
                    .substringBefore("://", missingDelimiterValue = "")
                    .lowercase()
            return scheme in SHARE_LINK_SCHEMES
        }

        fun refresh(id: Long) {
            viewModelScope.launch {
                refreshing.update { it + id }
                try {
                    repository
                        .refresh(id)
                        .onSuccess { outcome ->
                            if (outcome.skipped.isNotEmpty()) {
                                postMessage(skippedSummary(outcome.skipped))
                            }
                        }.onFailure { postMessage(it.message ?: "Refresh failed") }
                } finally {
                    refreshing.update { it - id }
                }
            }
        }

        /**
         * Enable/disable — the repository flips the flag, reconciles the
         * periodic job, and clears the selection when the disabled subscription
         * owned the selected node. `refreshing` isn't touched: the toggle is
         * instant, not a fetch.
         */
        fun setEnabled(
            id: Long,
            enabled: Boolean,
        ) {
            viewModelScope.launch {
                runCatching { repository.setEnabled(id, enabled) }
                    .onFailure { postMessage("Failed to update subscription") }
            }
        }

        fun rename(
            id: Long,
            name: String,
        ) {
            viewModelScope.launch {
                runCatching { repository.rename(id, name) }
                    .onFailure { postMessage("Rename failed") }
            }
        }

        /**
         * Repoint the subscription URL — the repository fetches, parses, and
         * validates the candidate before committing anything, so a broken URL
         * can't clobber a working subscription (last-known-good). Runs under
         * `refreshing` so the UI shows it as in-flight.
         */
        fun editUrl(
            id: Long,
            newUrl: String,
        ) {
            viewModelScope.launch {
                refreshing.update { it + id }
                try {
                    repository
                        .editUrl(id, newUrl)
                        .onFailure { postMessage(it.message ?: "URL update failed") }
                } finally {
                    refreshing.update { it - id }
                }
            }
        }

        fun remove(id: Long) {
            viewModelScope.launch { repository.remove(id) }
        }

        /**
         * Delete one manually imported node — the only way to undo a bad paste,
         * since the manual row has no refresh to replace its nodes. The row
         * itself goes with its last node.
         */
        fun removeManualNode(nodeId: String) {
            viewModelScope.launch {
                runCatching { repository.removeManualNode(nodeId) }
                    .onFailure { postMessage("Failed to remove server") }
            }
        }

        private companion object {
            /** Provider-controlled tokens inside skip reasons are capped so a
             *  hostile subscription can't push a megabyte snackbar. */
            const val MAX_REASON_LEN = 48
            const val MAX_REASON_GROUPS = 4

            /** Schemes UriListParser.parseLine accepts — keep in sync. */
            val SHARE_LINK_SCHEMES =
                setOf(
                    "vless",
                    "vmess",
                    "trojan",
                    "ss",
                    "hysteria2",
                    "hy2",
                    "tuic",
                    "anytls",
                    "wireguard",
                    "wg",
                    "socks",
                    "socks5",
                )
        }
    }

package dev.typenil.vpnclient.ui.subscriptions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.typenil.vpnclient.core.subscription.SubscriptionRepository
import dev.typenil.vpnclient.core.subscription.model.SkippedNode
import dev.typenil.vpnclient.core.subscription.model.SubscriptionProfile
import dev.typenil.vpnclient.data.db.NodeDao
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A snackbar message waiting to be shown — held in state so it survives
 *  the destination not being composed when the failure lands. [id] lets the
 *  screen re-trigger its effect when one message replaces another. */
data class PendingMessage(val id: Long, val text: String)

data class SubscriptionsUiState(
    val profiles: List<SubscriptionProfile> = emptyList(),
    /** subscriptionId → enabled node count (computed from observeEnabled). */
    val nodeCounts: Map<Long, Int> = emptyMap(),
    val refreshingIds: Set<Long> = emptySet(),
    val pendingMessage: PendingMessage? = null,
)

@HiltViewModel
class SubscriptionsViewModel @Inject constructor(
    private val repository: SubscriptionRepository,
    nodeDao: NodeDao,
) : ViewModel() {

    private val pendingMessage = MutableStateFlow<PendingMessage?>(null)

    private val refreshing = MutableStateFlow<Set<Long>>(emptySet())

    val uiState: StateFlow<SubscriptionsUiState> = combine(
        repository.profiles,
        nodeDao.observeEnabled(),
        refreshing,
        pendingMessage,
    ) { profiles, nodes, refreshingIds, message ->
        SubscriptionsUiState(
            profiles = profiles,
            nodeCounts = nodes.groupingBy { it.subscriptionId }.eachCount(),
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
        val shown = grouped.entries.take(MAX_REASON_GROUPS)
            .joinToString(", ") { (reason, count) -> "$reason ($count)" }
        val rest = grouped.size - MAX_REASON_GROUPS
        return "${skipped.size} nodes skipped: $shown" +
            if (rest > 0) ", +$rest more" else ""
    }

    fun add(url: String, requestedName: String?, allowInsecureHttp: Boolean = false) {
        viewModelScope.launch {
            repository.add(url, requestedName?.trim()?.ifEmpty { null }, allowInsecureHttp)
                .onSuccess { outcome ->
                    if (outcome.skipped.isNotEmpty()) {
                        postMessage(skippedSummary(outcome.skipped))
                    }
                }
                .onFailure { postMessage(it.message ?: "Failed to add subscription") }
        }
    }

    fun refresh(id: Long) {
        viewModelScope.launch {
            refreshing.update { it + id }
            try {
                repository.refresh(id)
                    .onSuccess { outcome ->
                        if (outcome.skipped.isNotEmpty()) {
                            postMessage(skippedSummary(outcome.skipped))
                        }
                    }
                    .onFailure { postMessage(it.message ?: "Refresh failed") }
            } finally {
                refreshing.update { it - id }
            }
        }
    }

    fun remove(id: Long) {
        viewModelScope.launch { repository.remove(id) }
    }

    private companion object {
        /** Provider-controlled tokens inside skip reasons are capped so a
         *  hostile subscription can't push a megabyte snackbar. */
        const val MAX_REASON_LEN = 48
        const val MAX_REASON_GROUPS = 4
    }
}

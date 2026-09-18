package dev.typenil.vpnclient.ui.subscriptions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.typenil.vpnclient.core.subscription.SubscriptionRepository
import dev.typenil.vpnclient.core.subscription.model.SubscriptionProfile
import dev.typenil.vpnclient.data.db.NodeDao
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SubscriptionsUiState(
    val profiles: List<SubscriptionProfile> = emptyList(),
    /** subscriptionId → enabled node count (computed from observeEnabled). */
    val nodeCounts: Map<Long, Int> = emptyMap(),
    val refreshingIds: Set<Long> = emptySet(),
)

@HiltViewModel
class SubscriptionsViewModel @Inject constructor(
    private val repository: SubscriptionRepository,
    nodeDao: NodeDao,
) : ViewModel() {

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    /** One-shot snackbar messages (add/refresh failures). */
    val messages: SharedFlow<String> = _messages

    private val refreshing = MutableStateFlow<Set<Long>>(emptySet())

    val uiState: StateFlow<SubscriptionsUiState> = combine(
        repository.profiles,
        nodeDao.observeEnabled(),
        refreshing,
    ) { profiles, nodes, refreshingIds ->
        SubscriptionsUiState(
            profiles = profiles,
            nodeCounts = nodes.groupingBy { it.subscriptionId }.eachCount(),
            refreshingIds = refreshingIds,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SubscriptionsUiState(),
    )

    fun add(url: String, requestedName: String?) {
        viewModelScope.launch {
            repository.add(url, requestedName?.trim()?.ifEmpty { null })
                .onFailure { _messages.emit(it.message ?: "Failed to add subscription") }
        }
    }

    fun refresh(id: Long) {
        viewModelScope.launch {
            refreshing.update { it + id }
            try {
                repository.refresh(id)
                    .onFailure { _messages.emit(it.message ?: "Refresh failed") }
            } finally {
                refreshing.update { it - id }
            }
        }
    }

    fun remove(id: Long) {
        viewModelScope.launch { repository.remove(id) }
    }
}

package dev.typenil.vpnclient.data.db

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** In-memory [NodeLatencyDao] for unit tests. The SQL join/sweep against the
 *  nodes table is covered on a real Room database (androidTest); here
 *  [liveNodeIds], when set, plays the role of the `nodes` table. */
class FakeNodeLatencyDao : NodeLatencyDao() {
    private val state = MutableStateFlow<Map<String, NodeLatencyEntity>>(emptyMap())

    /** Null = every row is visible and nothing is swept. */
    var liveNodeIds: Set<String>? = null

    val rows: Map<String, NodeLatencyEntity> get() = state.value

    override fun observeLive(): Flow<List<NodeLatencyEntity>> =
        state.map { all -> all.values.filter { liveNodeIds?.contains(it.nodeId) ?: true } }

    override suspend fun upsertAll(rows: List<NodeLatencyEntity>) {
        state.value = state.value + rows.associateBy { it.nodeId }
    }

    override suspend fun deleteOrphans() {
        val live = liveNodeIds ?: return
        state.value = state.value.filterKeys { it in live }
    }
}

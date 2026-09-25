package dev.typenil.vpnclient.data.db

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** In-memory [NodePreferenceDao] for unit tests — mirrors the upsert/clear
 *  semantics of the SQL statements without a database. */
class FakeNodePreferenceDao : NodePreferenceDao() {
    val prefs = mutableMapOf<String, NodePreferenceEntity>()

    override fun observeAll(): Flow<List<NodePreferenceEntity>> = flowOf(prefs.values.toList())

    override suspend fun get(nodeId: String): NodePreferenceEntity? = prefs[nodeId]

    override suspend fun setFavorite(
        nodeId: String,
        favorite: Boolean,
    ) {
        val cur = prefs[nodeId]
        prefs[nodeId] =
            cur?.copy(isFavorite = favorite)
                ?: NodePreferenceEntity(nodeId = nodeId, isFavorite = favorite)
    }

    override suspend fun setEnabled(
        nodeId: String,
        enabled: Boolean,
    ) {
        val cur = prefs[nodeId]
        prefs[nodeId] =
            cur?.copy(isEnabled = enabled)
                ?: NodePreferenceEntity(nodeId = nodeId, isEnabled = enabled)
    }

    override suspend fun setCustomName(
        nodeId: String,
        customName: String?,
    ) {
        val cur = prefs[nodeId]
        prefs[nodeId] =
            cur?.copy(customName = customName)
                ?: NodePreferenceEntity(nodeId = nodeId, customName = customName)
    }

    override suspend fun setHidden(
        nodeId: String,
        hidden: Boolean,
    ) {
        val cur = prefs[nodeId]
        prefs[nodeId] =
            cur?.copy(isHidden = hidden)
                ?: NodePreferenceEntity(nodeId = nodeId, isHidden = hidden)
    }

    override suspend fun delete(nodeId: String) {
        prefs.remove(nodeId)
    }

    override suspend fun deleteForSubscription(subscriptionId: Long) {
        // The SQL prunes by sub-select on nodes; the fake tracks nodeIds per
        // subscription explicitly for tests.
        prefs.keys.removeAll { prefsBySub.remove(it) == subscriptionId }
    }

    /** Test hook: record which subscription a node belongs to so
     *  [deleteForSubscription] can match the SQL sub-select semantics. */
    fun linkToSubscription(
        nodeId: String,
        subscriptionId: Long,
    ) {
        prefsBySub[nodeId] = subscriptionId
    }

    private val prefsBySub = mutableMapOf<String, Long>()

    override suspend fun deleteOrphans() {
        // Can't sub-select nodes in a fake — tests that need orphan pruning
        // seed [liveNodeIds].
        prefs.keys.removeAll { it !in liveNodeIds }
    }

    /** Node ids that "exist" for [deleteOrphans] — tests set this to the
     *  current fake node table contents. */
    var liveNodeIds: Set<String> = emptySet()
}

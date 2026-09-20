package dev.typenil.vpnclient.data

import dev.typenil.vpnclient.core.engine.EngineConfig
import dev.typenil.vpnclient.core.engine.singbox.ConfigCompiler
import dev.typenil.vpnclient.core.subscription.model.ProtocolType
import dev.typenil.vpnclient.core.subscription.model.ProxyNode
import dev.typenil.vpnclient.core.vpn.NodeConfigProvider
import dev.typenil.vpnclient.data.db.NodeDao
import dev.typenil.vpnclient.data.db.NodeEntity
import dev.typenil.vpnclient.data.settings.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

@Singleton
class NodeConfigProviderImpl @Inject constructor(
    private val nodeDao: NodeDao,
    private val settings: SettingsRepository,
    private val compiler: ConfigCompiler,
) : NodeConfigProvider {

    override suspend fun compileSelected(): EngineConfig? {
        val nodes = nodeDao.getEnabled().map { it.toDomain() }
        if (nodes.isEmpty()) return null
        return compiler.compile(
            nodes = nodes,
            selectedNodeId = settings.selectedNodeId.first(),
            ipv6Enabled = settings.ipv6Enabled.first(),
            routeMode = settings.routeMode.first(),
        )
    }
}

fun NodeEntity.toDomain(): ProxyNode = ProxyNode(
    id = id,
    subscriptionId = subscriptionId,
    name = name,
    protocol = runCatching { ProtocolType.valueOf(protocol) }.getOrDefault(ProtocolType.OTHER),
    server = server,
    port = port,
    outboundJson = outboundJson,
    rawUri = rawUri,
)

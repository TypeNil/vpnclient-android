package dev.typenil.vpnclient.core.engine.singbox

import dev.typenil.vpnclient.core.engine.EngineConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Context for short credentials and unlabelled endpoint/name occurrences. */
internal fun coreLogSecrets(config: EngineConfig): List<String> = buildList {
    add(config.node.name)
    add(config.node.server)
    fun visit(value: JsonElement, sensitive: Boolean = false) {
        when (value) {
            is JsonObject -> value.forEach { (key, child) ->
                visit(child, key in setOf(
                    "server", "server_name", "password", "uuid", "token", "auth", "auth_str",
                    "public_key", "private_key", "pre_shared_key", "short_id", "username",
                ))
            }
            is JsonArray -> value.forEach { visit(it, sensitive) }
            is JsonPrimitive -> if (sensitive && value.isString) add(value.content)
        }
    }
    visit(Json.parseToJsonElement(config.configJson))
}.filter { it.isNotBlank() }.distinct()

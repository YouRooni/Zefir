package dev.rooni.zefir.app

import kotlinx.serialization.json.*
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.representer.Representer
import org.yaml.snakeyaml.nodes.Tag
import org.yaml.snakeyaml.resolver.Resolver
import java.util.regex.Pattern
import java.time.Duration
import java.time.LocalTime

const val NIGHT_START_HOUR = 23
const val NIGHT_END_HOUR = 7

fun msUntilNight(now: LocalTime): Long {
    if (now.hour >= NIGHT_START_HOUR || now.hour < NIGHT_END_HOUR) return 0
    return Duration.between(now, LocalTime.of(NIGHT_START_HOUR, 0)).toMillis()
}

private class JsonLikeResolver : Resolver() {
    override fun addImplicitResolvers() {
        addImplicitResolver(Tag.BOOL, Pattern.compile("^(?:true|True|TRUE|false|False|FALSE)$"), "tTfF")
        addImplicitResolver(Tag.INT, Pattern.compile("^-?(?:0|[1-9][0-9]*)$"), "-0123456789")
        addImplicitResolver(Tag.FLOAT, Pattern.compile("^-?(?:0|[1-9][0-9]*)\\.[0-9]+(?:[eE][-+]?[0-9]+)?$"), "-0123456789")
        addImplicitResolver(Tag.MERGE, MERGE, "<")
        addImplicitResolver(Tag.NULL, NULL, "~nN\u0000")
        addImplicitResolver(Tag.NULL, EMPTY, null)
    }
}

private fun parseConfig(config: String): Map<*, *>? {
    val options = LoaderOptions().apply { maxAliasesForCollections = 1000 }
    val yaml = Yaml(SafeConstructor(options), Representer(DumperOptions()), DumperOptions(), options, JsonLikeResolver())
    return yaml.load<Any?>(config) as? Map<*, *>
}

fun clashProxies(text: String): List<JsonObject> {
    val options = LoaderOptions().apply { maxAliasesForCollections = 1000 }
    val yaml = Yaml(SafeConstructor(options), Representer(DumperOptions()), DumperOptions(), options, JsonLikeResolver())
    val root = runCatching { yaml.load<Any?>(text) }.getOrNull()
    val items = when (root) {
        is Map<*, *> -> (root["proxies"] as? List<*>) ?: listOf(root).takeIf { root["type"] != null }
        is List<*> -> root
        else -> null
    }.orEmpty()
    return items.mapNotNull { item -> (yamlToJson(item) as? JsonObject)?.takeIf { it["type"] != null && it["server"] != null } }
}

private fun yamlToJson(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is Map<*, *> -> JsonObject(value.entries.associate { (key, item) -> key.toString() to yamlToJson(item) })
    is Iterable<*> -> JsonArray(value.map(::yamlToJson))
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    else -> JsonPrimitive(value.toString())
}

private val NO_TFO_TYPES = setOf("hysteria", "hysteria2", "tuic", "wireguard", "direct", "dns")

fun tuneProfile(config: String, minInterval: Int?, chains: Map<String, String>, fastOpen: Boolean = false): String? {
    val root = runCatching { parseConfig(config) }.getOrNull() ?: return null
    var changed = false
    fun raised(block: Map<*, *>): Map<*, *> {
        minInterval ?: return block
        val interval = block["interval"]?.toString()?.toIntOrNull() ?: return block
        if (interval !in 1 until minInterval) return block
        changed = true
        return block + ("interval" to minInterval)
    }
    val groups = (root["proxy-groups"] as? List<*>)?.map { if (it is Map<*, *>) raised(it) else it }
    val providers = (root["proxy-providers"] as? Map<*, *>)?.mapValues { (_, provider) ->
        val block = provider as? Map<*, *> ?: return@mapValues provider
        val check = block["health-check"] as? Map<*, *>
        var next: Map<*, *> = if (check == null) block else block + ("health-check" to raised(check))
        if (fastOpen) {
            val override = block["override"] as? Map<*, *> ?: emptyMap<Any, Any>()
            if (override["tfo"] == null) {
                changed = true
                next = next + ("override" to override + ("tfo" to true))
            }
        }
        next
    }
    val known = buildSet {
        (root["proxies"] as? List<*>)?.forEach { (it as? Map<*, *>)?.get("name")?.let { name -> add(name.toString()) } }
        groups?.forEach { (it as? Map<*, *>)?.get("name")?.let { name -> add(name.toString()) } }
    }
    val links = buildMap {
        (root["proxies"] as? List<*>)?.forEach { item ->
            val proxy = item as? Map<*, *> ?: return@forEach
            val name = proxy["name"]?.toString() ?: return@forEach
            proxy["dialer-proxy"]?.toString()?.let { put(name, it) }
        }
    }.toMutableMap()
    val accepted = chains.filter { (name, via) ->
        val ok = via in known && via != name && !chainCreatesLoop(name, via, links - name)
        if (ok) links[name] = via
        ok
    }
    val proxies = (root["proxies"] as? List<*>)?.map { item ->
        var proxy = item as? Map<*, *> ?: return@map item
        if (fastOpen && proxy["tfo"] == null && proxy["type"]?.toString()?.lowercase() !in NO_TFO_TYPES) {
            changed = true
            proxy = proxy + ("tfo" to true)
        }
        val via = accepted[proxy["name"]?.toString()] ?: return@map proxy
        changed = true
        proxy + ("dialer-proxy" to via)
    }
    if (!changed) return null
    return yamlToJson(root + listOfNotNull(groups?.let { "proxy-groups" to it }, providers?.let { "proxy-providers" to it }, proxies?.let { "proxies" to it })).toString()
}

fun chainCreatesLoop(name: String, via: String, links: Map<String, String>): Boolean {
    var current: String? = via
    val seen = mutableSetOf<String>()
    while (current != null) {
        if (current == name) return true
        if (!seen.add(current)) return false
        current = links[current]
    }
    return false
}

private fun sameServerKey(proxy: Map<*, *>): String = canonical(yamlToJson(proxy - "name"))

private fun canonical(value: JsonElement): String = when (value) {
    is JsonObject -> value.entries.sortedBy { it.key }.joinToString(",", "{", "}") { (key, item) -> "\"$key\":" + canonical(item) }
    is JsonArray -> value.joinToString(",", "[", "]") { canonical(it) }
    else -> value.toString()
}

class MergedConfig(val config: String, val renames: List<Map<String, String>>)

fun mergeProfileConfigs(configs: List<String>): MergedConfig {
    val names = mutableSetOf(AUTO_GROUP, SERVERS_GROUP)
    val proxies = mutableListOf<JsonElement>()
    val providers = linkedMapOf<String, JsonElement>()
    val seen = mutableMapOf<String, String>()
    val renames = configs.map { config ->
        val root = parseConfig(config) ?: throw IllegalArgumentException("not a config")
        val renamed = mutableMapOf<String, String>()
        (root["proxies"] as? List<*>)?.filterIsInstance<Map<*, *>>()?.forEach { proxy ->
            val original = proxy["name"]?.toString() ?: return@forEach
            val key = sameServerKey(proxy)
            seen[key]?.let {
                renamed[original] = it
                return@forEach
            }
            var name = original
            var n = 2
            while (!names.add(name)) name = "$original ${n++}"
            renamed[original] = name
            seen[key] = name
            proxies += yamlToJson(proxy + ("name" to name))
        }
        (root["proxy-providers"] as? Map<*, *>)?.forEach { (key, provider) ->
            if (provider !is Map<*, *>) return@forEach
            var name = key.toString()
            var n = 2
            while (name in providers) name = "$key ${n++}"
            providers[name] = yamlToJson(provider - "path")
        }
        renamed
    }
    require(proxies.isNotEmpty() || providers.isNotEmpty()) { "no servers" }
    val serverNames = JsonArray(proxies.map { it.jsonObject.getValue("name") })
    val shared = JsonArray(providers.keys.map(::JsonPrimitive))
    val config = buildJsonObject {
        if (proxies.isNotEmpty()) put("proxies", JsonArray(proxies))
        if (providers.isNotEmpty()) put("proxy-providers", JsonObject(providers))
        putJsonArray("proxy-groups") {
            addJsonObject {
                put("name", SERVERS_GROUP)
                put("type", "select")
                put("proxies", JsonArray(listOf(JsonPrimitive(AUTO_GROUP)) + serverNames))
                if (providers.isNotEmpty()) put("use", shared)
            }
            addJsonObject {
                put("name", AUTO_GROUP)
                put("type", "url-test")
                put("url", "https://www.gstatic.com/generate_204")
                put("interval", 600)
                put("tolerance", 50)
                if (proxies.isNotEmpty()) put("proxies", serverNames)
                if (providers.isNotEmpty()) put("use", shared)
            }
        }
        putJsonArray("rules") { add("MATCH,$SERVERS_GROUP") }
    }.toString()
    return MergedConfig(config, renames)
}

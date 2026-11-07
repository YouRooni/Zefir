package dev.rooni.zefir.app

import kotlinx.serialization.json.*
import java.net.URLDecoder
import java.util.Base64

private val WG_SCHEMES = setOf("wireguard", "wg", "awg")

private enum class AwgKind { Number, Text, Flag }

private val AWG_NUMBERS = listOf("version", "jc", "jmin", "jmax", "s1", "s2", "s3", "s4", "itime")
private val AWG_TEXTS = listOf("h1", "h2", "h3", "h4", "i1", "i2", "i3", "i4", "i5", "j1", "j2", "j3")

private val AWG_V3_TEXTS = listOf(
    "header-protection-key", "content-padding-addition", "rekey-after-time", "rekey-timeout",
    "reject-after-time", "keepalive-timeout", "max-handshake-attempts",
)
private val AWG_V3_FLAGS = listOf("random-trailers", "disable-cookies")

private fun awgNorm(key: String) = key.lowercase().filter { it.isLetterOrDigit() }

private val AWG_KEYS: Map<String, Pair<String, AwgKind>> = buildMap {
    AWG_NUMBERS.forEach { put(awgNorm(it), it to AwgKind.Number) }
    (AWG_TEXTS + AWG_V3_TEXTS).forEach { put(awgNorm(it), it to AwgKind.Text) }
    AWG_V3_FLAGS.forEach { put(awgNorm(it), it to AwgKind.Flag) }
}

fun amneziaOptions(entries: Iterable<Pair<String, String>>): JsonObject {
    val out = linkedMapOf<String, JsonElement>()
    for ((rawKey, rawValue) in entries) {
        val (key, kind) = AWG_KEYS[awgNorm(rawKey)] ?: continue
        val value = rawValue.trim()
        if (value.isEmpty() || key in out) continue
        when (kind) {
            AwgKind.Number -> value.toLongOrNull()?.let { out[key] = JsonPrimitive(it) }
            AwgKind.Text -> out[key] = JsonPrimitive(value)
            AwgKind.Flag -> out[key] = JsonPrimitive(value.lowercase() in setOf("1", "true", "yes", "on"))
        }
    }
    if ("version" !in out && out.keys.any { it in AWG_V3_TEXTS || it in AWG_V3_FLAGS }) out["version"] = JsonPrimitive(3)
    return JsonObject(out)
}

fun amneziaConfKey(key: String) = key.split('-').joinToString("") { part -> part.replaceFirstChar { it.uppercase() } }

fun isWireguardLink(line: String) = line.substringBefore("://", "").lowercase() in WG_SCHEMES

fun isWireguardConf(text: String) = Regex("(?im)^\\s*\\[interface]").containsMatchIn(text) && Regex("(?im)^\\s*\\[peer]").containsMatchIn(text)

private fun decodeValue(value: String) = runCatching { URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }.getOrDefault(value)

private fun parseReserved(raw: String): JsonArray? {
    val numbers = raw.split(',').map { it.trim().toIntOrNull() }
    val bytes = if (numbers.all { it != null }) {
        numbers.filterNotNull()
    } else {
        runCatching { Base64.getDecoder().decode(raw.trim()).map { it.toInt() and 0xFF } }.getOrNull() ?: return null
    }
    return JsonArray(bytes.map(::JsonPrimitive))
}

private fun splitHostPort(endpoint: String): Pair<String, Int>? {
    val text = endpoint.trim()
    val colon = text.lastIndexOf(':')
    if (colon <= 0) return null
    val port = text.substring(colon + 1).toIntOrNull() ?: return null
    return text.substring(0, colon).trim('[', ']').ifEmpty { return null } to port
}

private fun buildProxy(name: String, host: String, port: Int, values: Map<String, String>): JsonObject? {
    fun pick(vararg keys: String) = keys.firstNotNullOfOrNull { values[it]?.trim()?.ifEmpty { null } }
    val privateKey = pick("privatekey", "private_key", "private-key", "pk") ?: return null
    val publicKey = pick("publickey", "public_key", "public-key", "peer_pub", "peer_public_key", "peerpublickey") ?: return null
    val addresses = pick("address", "addresses", "ip", "local_address", "localaddress")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
    val ipv4 = addresses.firstOrNull { ':' !in it }
    val ipv6 = addresses.firstOrNull { ':' in it }
    if (ipv4 == null && ipv6 == null) return null
    return buildJsonObject {
        put("name", name)
        put("type", "wireguard")
        put("server", host)
        put("port", port)
        put("udp", true)
        put("private-key", privateKey)
        put("public-key", publicKey)
        ipv4?.let { put("ip", it) }
        ipv6?.let { put("ipv6", it) }
        pick("presharedkey", "preshared_key", "pre_shared_key", "pre-shared-key", "psk")?.let { put("pre-shared-key", it) }
        pick("mtu")?.toIntOrNull()?.let { put("mtu", it) }
        pick("persistentkeepalive", "keepalive", "persistent-keepalive")?.toIntOrNull()?.let { put("persistent-keepalive", it) }
        pick("reserved")?.let(::parseReserved)?.let { put("reserved", it) }
        pick("allowedips", "allowed_ips", "allowed-ips")?.let { raw ->
            put("allowed-ips", JsonArray(raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map(::JsonPrimitive)))
        }
        val amnezia = amneziaOptions(values.entries.map { it.key to it.value })
        if (amnezia.isNotEmpty()) put("amnezia-wg-option", amnezia)
    }
}

fun parseWireguardLink(line: String): JsonObject? {
    val body = line.substringAfter("://")
    val name = body.substringAfter('#', "").let(::decodeValue)
    val beforeName = body.substringBefore('#')
    val values = beforeName.substringAfter('?', "").split('&').filter { '=' in it }
        .associate { it.substringBefore('=').lowercase() to decodeValue(it.substringAfter('=')) }.toMutableMap()
    val authority = beforeName.substringBefore('?').trimEnd('/')
    if ('@' in authority) values.putIfAbsent("privatekey", decodeValue(authority.substringBeforeLast('@')))
    val (host, port) = splitHostPort(authority.substringAfterLast('@')) ?: return null
    return buildProxy(name.ifBlank { host }, host, port, values)
}

fun parseWireguardConf(text: String): JsonObject? {
    val values = mutableMapOf<String, String>()
    var section = ""
    var peers = 0
    text.lineSequence().map { it.trim() }.forEach { line ->
        when {
            line.isEmpty() || line.startsWith('#') || line.startsWith(';') -> Unit
            line.startsWith('[') -> {
                section = line.trim('[', ']').lowercase()
                if (section == "peer") peers++
            }
            '=' in line && (section == "interface" || (section == "peer" && peers == 1)) ->
                values.putIfAbsent(line.substringBefore('=').trim().lowercase(), line.substringAfter('=').trim())
        }
    }
    val (host, port) = values["endpoint"]?.let(::splitHostPort) ?: return null
    val name = Regex("(?im)^\\s*#\\s*name\\s*=\\s*(.+)$").find(text)?.groupValues?.get(1)?.trim()
    return buildProxy(name ?: host, host, port, values)
}

fun wireguardConfName(text: String): String? = parseWireguardConf(text)?.get("name")?.jsonPrimitive?.contentOrNull

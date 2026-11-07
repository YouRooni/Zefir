package dev.rooni.zefir.app

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.net.URLDecoder
import java.util.Base64

data class SubscriptionMeta(
    val title: String? = null,
    val used: Long = 0,
    val total: Long = 0,
    val expire: Long = 0,
    val updateHours: Int? = null,
    val supportUrl: String? = null,
    val webUrl: String? = null,
    val announce: String? = null,
    val info: SubscriptionInfo? = null,
    val newUrl: String? = null,
    val newDomain: String? = null,
    val fallbackUrl: String? = null,
    val routing: SubscriptionRouting? = null,
)

@Serializable
data class SubscriptionInfo(val text: String, val color: String? = null, val buttonText: String? = null, val buttonLink: String? = null)

@Serializable
data class SubscriptionRouting(
    val name: String? = null,
    val globalProxy: Boolean = true,
    val directSites: List<String> = emptyList(),
    val directIp: List<String> = emptyList(),
    val proxySites: List<String> = emptyList(),
    val proxyIp: List<String> = emptyList(),
    val blockSites: List<String> = emptyList(),
    val blockIp: List<String> = emptyList(),
) {
    val size get() = directSites.size + directIp.size + proxySites.size + proxyIp.size + blockSites.size + blockIp.size
}

enum class SubscriptionClient(val id: String, val label: String, val userAgent: String?) {
    Auto("auto", "", null),
    Plain("plain", "", "clash.meta"),
    Happ("happ", "Happ", "Happ/3.15.0"),
    FlClash("flclash", "FlClash", "FlClash/v0.8.92 clash-verge Platform/android"),
    FlClashX("flclashx", "FlClash X", "FlClashX/v0.3.2 clash-verge Platform/android"),
    V2RayTun("v2raytun", "v2RayTun", "v2RayTun/5.20.60"),
    V2RayNg("v2rayng", "v2rayNG", "v2rayNG/1.10.16"),
    Streisand("streisand", "Streisand", "Streisand/1.6.61"),
    Shadowrocket("shadowrocket", "Shadowrocket", "Shadowrocket/2.2.70"),
    V2Box("v2box", "V2Box", "V2Box/9.5.0"),
    Hiddify("hiddify", "Hiddify", "HiddifyNext/2.5.7"),
    NekoBox("nekobox", "NekoBox", "NekoBox/Android/1.3.9"),
    Karing("karing", "Karing", "Karing/1.2.5 android"),
    ClashVerge("clashverge", "Clash Verge", "clash-verge/v2.2.3"),
    MihomoParty("mihomoparty", "Mihomo Party", "mihomo.party/v1.7.6"),
    Stash("stash", "Stash", "Stash/2.7.8"),
    Custom("custom", "", null);

    companion object {
        fun of(id: String) = entries.firstOrNull { it.id == id } ?: Auto

        val autoOrder = listOf(Plain, FlClash, Happ, V2RayTun, V2RayNg, Streisand, Shadowrocket, V2Box, Hiddify)
    }
}

private val STUB_HOSTS = setOf("0.0.0.0", "127.0.0.1", "::", "::1", "localhost")

fun isStubServer(proxy: JsonObject): Boolean {
    val host = (proxy["server"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()
    val port = (proxy["port"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
    return host in STUB_HOSTS || (port != null && port <= 1)
}

private val SAFE_LINK = Regex("^(https?|tg)://\\S+$", RegexOption.IGNORE_CASE)
private val HTTPS_LINK = Regex("^https://\\S+$", RegexOption.IGNORE_CASE)
private val BODY_PARAM = Regex("^#\\s*([a-z-]+)\\s*:\\s*(.*)$")
private const val ROUTING_PREFIX = "happ://routing/"

fun decodeBase64Text(text: String): String {
    val clean = text.filterNot { it.isWhitespace() }.replace('-', '+').replace('_', '/').trimEnd('=')
    return Base64.getDecoder().decode(clean.padEnd((clean.length + 3) / 4 * 4, '=')).decodeToString()
}

private fun textValue(raw: String?): String? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (!value.startsWith("base64:", true)) return value
    return runCatching { decodeBase64Text(value.substring(7)) }.getOrNull()?.trim()?.ifEmpty { null }
}

fun safeLink(raw: String?): String? = raw?.trim()?.takeIf { SAFE_LINK.matches(it) }

private const val UNLIMITED_BYTES = 1L shl 50
private const val FAR_FUTURE_SECONDS = 4_102_444_800L

fun normalizeTotal(total: Long) = if (total >= UNLIMITED_BYTES || total < 0) 0 else total

fun normalizeExpire(expire: Long): Long {
    val seconds = if (expire > 100_000_000_000L) expire / 1000 else expire
    return if (seconds >= FAR_FUTURE_SECONDS || seconds < 0) 0 else seconds
}

fun parseUserInfo(raw: String?): Triple<Long, Long, Long>? {
    raw ?: return null
    val map = raw.split(';').mapNotNull {
        val parts = it.trim().split('=')
        if (parts.size == 2) parts[0].trim() to (parts[1].trim().toDoubleOrNull()?.toLong() ?: 0L) else null
    }.toMap()
    if (map.isEmpty()) return null
    return Triple((map["upload"] ?: 0) + (map["download"] ?: 0), normalizeTotal(map["total"] ?: 0), normalizeExpire(map["expire"] ?: 0))
}

fun parseSubscriptionMeta(header: (String) -> String?, body: String): SubscriptionMeta {
    val fromBody = mutableMapOf<String, String>()
    var routingLine: String? = null
    body.lineSequence().map { it.trim().removePrefix("﻿") }.forEach { line ->
        if (line.startsWith(ROUTING_PREFIX, true)) routingLine = line
        BODY_PARAM.find(line)?.let { fromBody.putIfAbsent(it.groupValues[1], it.groupValues[2]) }
    }
    fun param(name: String) = header(name)?.takeIf { it.isNotBlank() } ?: fromBody[name]

    val userInfo = parseUserInfo(param("subscription-userinfo"))
    val infoText = textValue(param("sub-info-text"))?.takeIf { it != "0" }
    val routingEnabled = param("routing-enable")?.trim()?.lowercase().let { it == null || it == "true" || it == "1" }
    return SubscriptionMeta(
        title = textValue(param("profile-title")),
        used = userInfo?.first ?: 0,
        total = userInfo?.second ?: 0,
        expire = userInfo?.third ?: 0,
        updateHours = param("profile-update-interval")?.trim()?.toIntOrNull()?.takeIf { it > 0 },
        supportUrl = safeLink(param("support-url")),
        webUrl = safeLink(param("profile-web-page-url")),
        announce = textValue(param("announce"))?.take(200),
        info = infoText?.let {
            SubscriptionInfo(
                it,
                param("sub-info-color")?.trim()?.lowercase(),
                textValue(param("sub-info-button-text")),
                safeLink(param("sub-info-button-link")),
            )
        },
        newUrl = param("new-url")?.trim()?.takeIf { HTTPS_LINK.matches(it) },
        newDomain = param("new-domain")?.trim()?.removePrefix("https://")?.substringBefore('/')?.takeIf { Regex("^[A-Za-z0-9.-]+(:\\d+)?$").matches(it) },
        fallbackUrl = param("fallback-url")?.trim()?.takeIf { HTTPS_LINK.matches(it) },
        routing = if (routingEnabled) parseRoutingLink(param("routing") ?: routingLine) else null,
    )
}

fun parseRoutingLink(link: String?): SubscriptionRouting? {
    val rest = link?.trim()?.takeIf { it.startsWith(ROUTING_PREFIX, true) }?.substring(ROUTING_PREFIX.length) ?: return null
    val payload = rest.substringAfter('/', "").ifEmpty { return null }
    val root = runCatching { Json.parseToJsonElement(decodeBase64Text(payload)).jsonObject }.getOrNull() ?: return null
    fun list(key: String): List<String> = when (val value = root[key]) {
        is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.ifEmpty { null } }
        else -> emptyList()
    }
    val global = (root["GlobalProxy"] as? JsonPrimitive)?.let { it.booleanOrNull ?: (it.content == "true") } ?: true
    return SubscriptionRouting(
        name = (root["Name"] as? JsonPrimitive)?.contentOrNull,
        globalProxy = global,
        directSites = list("DirectSites"),
        directIp = list("DirectIp"),
        proxySites = list("ProxySites"),
        proxyIp = list("ProxyIp"),
        blockSites = list("BlockSites"),
        blockIp = list("BlockIp"),
    )
}

private fun siteRule(entry: String, target: String): String? {
    val lower = entry.lowercase()
    fun value(prefix: String) = entry.substring(prefix.length)
    return when {
        lower.startsWith("geosite:") -> "GEOSITE,${value("geosite:")},$target"
        lower.startsWith("domain:") -> "DOMAIN-SUFFIX,${value("domain:")},$target"
        lower.startsWith("full:") -> "DOMAIN,${value("full:")},$target"
        lower.startsWith("regexp:") -> "DOMAIN-REGEX,${value("regexp:")},$target"
        lower.startsWith("keyword:") -> "DOMAIN-KEYWORD,${value("keyword:")},$target"
        entry.contains(':') -> null
        entry.contains('.') -> "DOMAIN-SUFFIX,$entry,$target"
        else -> "DOMAIN-KEYWORD,$entry,$target"
    }
}

private fun ipRule(entry: String, target: String): String? {
    val lower = entry.lowercase()
    return when {
        lower == "geoip:private" -> "GEOIP,lan,$target,no-resolve"
        lower.startsWith("geoip:") -> "GEOIP,${entry.substring(6)},$target,no-resolve"
        entry.contains(':') -> "IP-CIDR6,${if ('/' in entry) entry else "$entry/128"},$target,no-resolve"
        entry.firstOrNull()?.isDigit() == true -> "IP-CIDR,${if ('/' in entry) entry else "$entry/32"},$target,no-resolve"
        else -> null
    }
}

fun SubscriptionRouting.toRules(mainGroup: String?): List<String> = buildList {
    fun add(entries: List<String>, target: String?, make: (String, String) -> String?) {
        target ?: return
        entries.filter { ',' !in it && '\n' !in it }.forEach { entry -> make(entry, target)?.let(::add) }
    }
    add(blockSites, "REJECT", ::siteRule)
    add(blockIp, "REJECT", ::ipRule)
    add(proxySites, mainGroup, ::siteRule)
    add(proxyIp, mainGroup, ::ipRule)
    add(directSites, "DIRECT", ::siteRule)
    add(directIp, "DIRECT", ::ipRule)
    if (!globalProxy) add("MATCH,DIRECT")
}

private val LINK_EXTRAS = Regex("\\?(serverDescription|fragment|resolve-address|host|noises)=")

data class CleanLink(val link: String, val name: String?, val description: String?)

fun cleanShareLink(line: String): CleanLink {
    val hash = line.indexOf('#')
    if (hash < 0) return CleanLink(line, null, null)
    val fragment = line.substring(hash + 1)
    val extras = LINK_EXTRAS.find(fragment)
    val rawName = if (extras != null) fragment.substring(0, extras.range.first) else fragment
    val name = runCatching { URLDecoder.decode(rawName.replace("+", "%2B"), "UTF-8") }.getOrDefault(rawName)
    val query = extras?.let { fragment.substring(it.range.first + 1) }.orEmpty()
    val description = query.split('&').firstOrNull { it.startsWith("serverDescription=") }?.removePrefix("serverDescription=")
        ?.let { runCatching { decodeBase64Text(URLDecoder.decode(it, "UTF-8")) }.getOrNull() }?.take(30)?.ifBlank { null }
    return CleanLink(line.substring(0, hash + 1) + rawName, name, description)
}

fun isXrayJson(text: String): Boolean {
    val start = text.trimStart().firstOrNull() ?: return false
    return (start == '[' || start == '{') && "\"outbounds\"" in text
}

data class XrayBalancer(val type: String, val url: String?, val strategy: String?)

data class XrayServer(
    val proxy: JsonObject,
    val description: String?,
    val members: List<JsonObject> = emptyList(),
    val balancer: XrayBalancer? = null,
) {
    val name: String get() = proxy["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
}

fun parseXraySubscription(text: String): List<XrayServer> {
    val root = runCatching { Json.parseToJsonElement(text.trim()) }.getOrNull() ?: return emptyList()
    val configs = when (root) {
        is JsonArray -> root.mapNotNull { it as? JsonObject }
        is JsonObject -> listOf(root)
        else -> emptyList()
    }
    return configs.mapIndexedNotNull { index, config -> xrayServer(config, index) }
}

private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.ifEmpty { null }
private fun JsonObject.obj(key: String) = this[key] as? JsonObject
private fun JsonObject.first(key: String) = (this[key] as? JsonArray)?.firstOrNull() as? JsonObject

private val XRAY_PROTOCOLS = setOf("vless", "vmess", "trojan", "shadowsocks", "hysteria")

private fun xrayServer(config: JsonObject, index: Int): XrayServer? {
    val outbounds = (config["outbounds"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        .filter { it.str("protocol") in XRAY_PROTOCOLS }
    val description = config.obj("meta")?.str("serverDescription")?.take(30)
    xrayBalancer(config, outbounds, index, description)?.let { return it }
    val outbound = outbounds.firstOrNull { it.str("tag") == "proxy" } ?: outbounds.firstOrNull() ?: return null
    val proxy = xrayProxy(config, outbound, index, null) ?: return null
    return XrayServer(proxy, description)
}

private fun xrayBalancer(config: JsonObject, outbounds: List<JsonObject>, index: Int, description: String?): XrayServer? {
    val routing = config.obj("routing") ?: return null
    val balancers = (routing["balancers"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    if (balancers.isEmpty()) return null
    val used = (routing["rules"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.str("balancerTag") }.toSet()
    val balancer = balancers.firstOrNull { it.str("tag") in used } ?: balancers.first()
    val prefixes = (balancer["selector"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    val picked = outbounds.filter { out -> val tag = out.str("tag").orEmpty(); prefixes.any { tag.startsWith(it) } }
    if (picked.size < 2) return null
    val title = config.str("remarks") ?: balancer.str("tag") ?: "Auto ${index + 1}"
    val members = picked.mapIndexedNotNull { i, out -> xrayProxy(config, out, index, "$title · ${i + 1}") }
    if (members.size < 2) return null
    val probe = config.obj("burstObservatory")?.obj("pingConfig")?.str("destination")
        ?: config.obj("observatory")?.str("probeURL") ?: config.obj("observatory")?.str("probeUrl")
    val group = when (balancer.obj("strategy")?.str("type")?.lowercase()) {
        "random" -> XrayBalancer("load-balance", probe, "consistent-hashing")
        "roundrobin" -> XrayBalancer("load-balance", probe, "round-robin")
        else -> XrayBalancer("url-test", probe, null)
    }
    return XrayServer(JsonObject(mapOf("name" to JsonPrimitive(title))), description, members, group)
}

private fun xrayProxy(config: JsonObject, outbound: JsonObject, index: Int, forcedName: String?): JsonObject? {
    val settings = outbound.obj("settings") ?: return null
    val vnext = settings.first("vnext")
    val user = vnext?.first("users")
    val server = settings.first("servers")
    val address = vnext?.str("address") ?: server?.str("address") ?: settings.str("address") ?: return null
    val port = (vnext ?: server ?: settings)["port"]?.jsonPrimitive?.intOrNull ?: return null
    val protocol = outbound.str("protocol")!!
    val name = forcedName ?: config.str("remarks") ?: outbound.str("tag")?.takeIf { it != "proxy" } ?: "$address ${index + 1}"

    val proxy = buildMap<String, JsonElement> {
        put("name", JsonPrimitive(name))
        put("server", JsonPrimitive(address))
        put("port", JsonPrimitive(port))
        put("udp", JsonPrimitive(true))
        when (protocol) {
            "vless" -> {
                put("type", JsonPrimitive("vless"))
                put("uuid", JsonPrimitive(user?.str("id") ?: settings.str("id") ?: return null))
                (user?.str("flow") ?: settings.str("flow"))?.let { put("flow", JsonPrimitive(it)) }
            }
            "vmess" -> {
                put("type", JsonPrimitive("vmess"))
                put("uuid", JsonPrimitive(user?.str("id") ?: settings.str("id") ?: return null))
                put("alterId", JsonPrimitive(user?.get("alterId")?.jsonPrimitive?.intOrNull ?: 0))
                put("cipher", JsonPrimitive(user?.str("security") ?: "auto"))
            }
            "trojan" -> {
                put("type", JsonPrimitive("trojan"))
                put("password", JsonPrimitive(server?.str("password") ?: settings.str("password") ?: return null))
            }
            "hysteria" -> {
                if ((settings["version"] as? JsonPrimitive)?.intOrNull != 2) return null
                put("type", JsonPrimitive("hysteria2"))
                put("password", JsonPrimitive(outbound.obj("streamSettings")?.obj("hysteriaSettings")?.str("auth") ?: return null))
            }
            else -> {
                put("type", JsonPrimitive("ss"))
                put("password", JsonPrimitive(server?.str("password") ?: settings.str("password") ?: return null))
                put("cipher", JsonPrimitive(server?.str("method") ?: settings.str("method") ?: return null))
            }
        }
        outbound.obj("streamSettings")?.let { putStream(it, protocol) }
    }
    return JsonObject(proxy)
}

private fun MutableMap<String, JsonElement>.putStream(stream: JsonObject, protocol: String) {
    val network = stream.str("network") ?: "tcp"
    when (network) {
        "ws", "httpupgrade" -> {
            val opts = stream.obj(if (network == "ws") "wsSettings" else "httpupgradeSettings")
            put("network", JsonPrimitive("ws"))
            put("ws-opts", buildJsonObject {
                opts?.str("path")?.let { put("path", it) }
                val host = opts?.str("host") ?: opts?.obj("headers")?.str("Host")
                if (host != null) putJsonObject("headers") { put("Host", host) }
                if (network == "httpupgrade") put("v2ray-http-upgrade", true)
            })
        }
        "grpc" -> {
            put("network", JsonPrimitive("grpc"))
            put("grpc-opts", buildJsonObject { put("grpc-service-name", stream.obj("grpcSettings")?.str("serviceName").orEmpty()) })
        }
        "xhttp", "splithttp" -> {
            val opts = stream.obj("xhttpSettings") ?: stream.obj("splithttpSettings")
            put("network", JsonPrimitive("xhttp"))
            put("xhttp-opts", buildJsonObject {
                opts?.str("path")?.let { put("path", it) }
                opts?.str("host")?.let { put("host", it) }
                opts?.str("mode")?.let { put("mode", it) }
            })
        }
    }
    val security = stream.str("security")
    val tls = stream.obj("tlsSettings")
    val reality = stream.obj("realitySettings")
    val secure = security == "tls" || security == "reality"
    if (secure && protocol != "shadowsocks") {
        if (protocol != "trojan" && protocol != "hysteria") put("tls", JsonPrimitive(true))
        val serverName = (if (security == "reality") reality else tls)?.str("serverName")
        if (serverName != null) put(if (protocol == "trojan" || protocol == "hysteria") "sni" else "servername", JsonPrimitive(serverName))
        if (protocol != "hysteria") (if (security == "reality") reality else tls)?.str("fingerprint")?.let { put("client-fingerprint", JsonPrimitive(it)) }
        (tls?.get("alpn") as? JsonArray)?.let { put("alpn", it) }
        if ((tls?.get("allowInsecure") as? JsonPrimitive)?.booleanOrNull == true) put("skip-cert-verify", JsonPrimitive(true))
        if (security == "reality" && reality != null) {
            put("reality-opts", buildJsonObject {
                reality.str("publicKey")?.let { put("public-key", it) }
                put("short-id", reality.str("shortId").orEmpty())
            })
        }
    }
}

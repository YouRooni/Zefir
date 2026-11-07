package dev.rooni.zefir.app

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

enum class ManualType(val label: String, val key: String) {
    Socks("SOCKS5", "socks5"),
    Http("HTTP", "http"),
    Shadowsocks("Shadowsocks", "ss"),
    Vless("VLESS", "vless"),
    Vmess("VMess", "vmess"),
    Trojan("Trojan", "trojan"),
    Hysteria2("Hysteria2", "hysteria2"),
    Tuic("TUIC", "tuic"),
    WireGuard("WireGuard", "wireguard"),
    Amnezia("AmneziaWG", "wireguard");

    val isWireGuard: Boolean get() = this == WireGuard || this == Amnezia

    val usesUuid: Boolean get() = this == Vless || this == Vmess || this == Tuic
    val usesLogin: Boolean get() = this == Socks || this == Http
    val needsPassword: Boolean get() = this == Shadowsocks || this == Trojan || this == Hysteria2 || this == Tuic
    val hasPassword: Boolean get() = usesLogin || needsPassword
    val hasTransport: Boolean get() = this == Vless || this == Vmess || this == Trojan

    val securities: List<ManualSecurity>
        get() = when (this) {
            Socks, Http, Vmess -> listOf(ManualSecurity.None, ManualSecurity.Tls)
            Vless -> ManualSecurity.entries
            Trojan -> listOf(ManualSecurity.Tls, ManualSecurity.Reality)
            Hysteria2, Tuic -> listOf(ManualSecurity.Tls)
            Shadowsocks, WireGuard, Amnezia -> emptyList()
        }
}

enum class ManualSecurity { None, Tls, Reality }

enum class ManualTransport(val key: String, val label: String) { Tcp("tcp", "TCP"), Ws("ws", "WebSocket"), Grpc("grpc", "gRPC"), H2("h2", "HTTP/2") }

val SS_CIPHERS = listOf(
    "2022-blake3-aes-128-gcm",
    "2022-blake3-aes-256-gcm",
    "2022-blake3-chacha20-poly1305",
    "aes-128-gcm",
    "aes-256-gcm",
    "chacha20-ietf-poly1305",
    "xchacha20-ietf-poly1305",
)

val FINGERPRINTS = listOf("chrome", "firefox", "safari", "ios", "edge", "random")

const val VISION_FLOW = "xtls-rprx-vision"

data class ManualServer(
    val type: ManualType = ManualType.Socks,
    val name: String = "",
    val host: String = "",
    val port: String = "",
    val user: String = "",
    val password: String = "",
    val uuid: String = "",
    val cipher: String = SS_CIPHERS.first(),
    val vision: Boolean = false,
    val security: ManualSecurity = ManualSecurity.None,
    val sni: String = "",
    val fingerprint: String = FINGERPRINTS.first(),
    val insecure: Boolean = false,
    val realityKey: String = "",
    val shortId: String = "",
    val transport: ManualTransport = ManualTransport.Tcp,
    val path: String = "",
    val hostHeader: String = "",
    val service: String = "",
    val obfsPassword: String = "",
    val privateKey: String = "",
    val peerKey: String = "",
    val presharedKey: String = "",
    val address: String = "",
    val amnezia: String = "",
    val original: JsonObject? = null,
) {
    val portNumber: Int? get() = port.trim().toIntOrNull()?.takeIf { it in 1..65535 }
    val cleanHost: String get() = host.trim().removePrefix("[").removeSuffix("]")

    val effectiveSecurity: ManualSecurity
        get() = type.securities.let { options -> if (security in options) security else options.firstOrNull() ?: ManualSecurity.None }

    val showsTlsFields: Boolean get() = effectiveSecurity != ManualSecurity.None
    val showsReality: Boolean get() = effectiveSecurity == ManualSecurity.Reality

    fun amneziaOption(): JsonObject = amneziaOptions(
        amnezia.split('\n', ',', ';').map { it.trim() }.filter { it.isNotEmpty() }.map { line ->
            line.substringBefore('=').substringBefore(':').trim() to line.substringAfter('=', line.substringAfter(':', "")).trim()
        },
    )

    fun withType(next: ManualType): ManualServer = copy(type = next, security = if (security in next.securities) security else next.securities.firstOrNull() ?: ManualSecurity.None)

    val missing: ManualField?
        get() = when {
            cleanHost.isEmpty() || cleanHost.any { it.isWhitespace() || it == '/' } -> ManualField.Host
            portNumber == null -> ManualField.Port
            type.usesUuid && uuid.isBlank() -> ManualField.Uuid
            type.needsPassword && password.isEmpty() -> ManualField.Password
            type.isWireGuard && (privateKey.isBlank() || peerKey.isBlank()) -> ManualField.Keys
            type.isWireGuard && address.isBlank() -> ManualField.Address
            type == ManualType.Amnezia && amneziaOption().isEmpty() -> ManualField.Amnezia
            showsReality && realityKey.isBlank() -> ManualField.RealityKey
            else -> null
        }

    val title: String
        get() = name.trim().ifEmpty { "${type.label} $cleanHost" }

    fun toProxy(): JsonObject {
        val built = buildJsonObject {
            put("name", title)
            put("type", type.key)
            put("server", cleanHost)
            put("port", portNumber ?: 0)
            if (type != ManualType.Http) put("udp", true)
            if (type.usesLogin && user.isNotEmpty()) put("username", user)
            if (type.hasPassword && password.isNotEmpty()) put("password", password)
            if (type.usesUuid) put("uuid", uuid.trim())
            when (type) {
                ManualType.Shadowsocks -> put("cipher", cipher)
                ManualType.Vmess -> {
                    put("alterId", 0)
                    put("cipher", "auto")
                }
                ManualType.Vless -> if (vision && transport == ManualTransport.Tcp && effectiveSecurity != ManualSecurity.None) put("flow", VISION_FLOW)
                ManualType.Hysteria2 -> if (obfsPassword.isNotEmpty()) {
                    put("obfs", "salamander")
                    put("obfs-password", obfsPassword)
                }
                ManualType.Tuic -> {
                    put("congestion-controller", "bbr")
                    put("udp-relay-mode", "native")
                    putJsonArray("alpn") { add(JsonPrimitive("h3")) }
                }
                ManualType.WireGuard, ManualType.Amnezia -> {
                    put("private-key", privateKey.trim())
                    put("public-key", peerKey.trim())
                    if (presharedKey.isNotBlank()) put("pre-shared-key", presharedKey.trim())
                    val (v4, v6) = address.split(',').map { it.trim() }.filter { it.isNotEmpty() }.partition { ':' !in it }
                    v4.firstOrNull()?.let { put("ip", it) }
                    v6.firstOrNull()?.let { put("ipv6", it) }
                    if (type == ManualType.Amnezia) put("amnezia-wg-option", amneziaOption())
                }
                else -> Unit
            }
            if (showsTlsFields) {
                if (type == ManualType.Socks || type == ManualType.Http || type == ManualType.Vless || type == ManualType.Vmess) put("tls", true)
                val sniKey = if (type == ManualType.Vless || type == ManualType.Vmess) "servername" else "sni"
                if (sni.isNotBlank()) put(sniKey, sni.trim())
                if (insecure) put("skip-cert-verify", true)
                if (type.hasTransport) put("client-fingerprint", fingerprint)
                if (showsReality) putJsonObject("reality-opts") {
                    put("public-key", realityKey.trim())
                    if (shortId.isNotBlank()) put("short-id", shortId.trim())
                }
            }
            if (type.hasTransport && transport != ManualTransport.Tcp) {
                put("network", transport.key)
                when (transport) {
                    ManualTransport.Ws -> putJsonObject("ws-opts") {
                        put("path", path.ifBlank { "/" })
                        if (hostHeader.isNotBlank()) putJsonObject("headers") { put("Host", hostHeader.trim()) }
                    }
                    ManualTransport.Grpc -> putJsonObject("grpc-opts") { put("grpc-service-name", service.trim()) }
                    ManualTransport.H2 -> putJsonObject("h2-opts") {
                        put("path", path.ifBlank { "/" })
                        if (hostHeader.isNotBlank()) putJsonArray("host") { add(JsonPrimitive(hostHeader.trim())) }
                    }
                    ManualTransport.Tcp -> Unit
                }
            }
        }
        val base = original?.takeIf { text(it, "type") == type.key } ?: return built
        return JsonObject(base.filterKeys { it !in MANAGED_KEYS } + built)
    }

    companion object {
        private val MANAGED_KEYS = setOf(
            "name", "type", "server", "port", "udp", "username", "password", "uuid", "cipher", "alterId", "flow",
            "obfs", "obfs-password", "private-key", "public-key", "pre-shared-key", "ip", "ipv6", "tls", "servername", "sni",
            "skip-cert-verify", "client-fingerprint", "reality-opts", "network", "ws-opts", "grpc-opts", "h2-opts",
            "amnezia-wg-option",
        )

        private fun amneziaTitle(key: String) = amneziaConfKey(key)

        private fun text(obj: JsonObject?, key: String): String = (obj?.get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
        private fun flag(obj: JsonObject?, key: String) = text(obj, key) == "true"
        private fun child(obj: JsonObject?, key: String) = obj?.get(key) as? JsonObject

        fun from(proxy: JsonObject): ManualServer? {
            val amneziaOpts = child(proxy, "amnezia-wg-option")
            val type = when {
                text(proxy, "type") == "wireguard" && amneziaOpts != null -> ManualType.Amnezia
                else -> ManualType.entries.firstOrNull { it.key == text(proxy, "type") } ?: return null
            }
            val network = ManualTransport.entries.firstOrNull { it.key == text(proxy, "network").ifEmpty { "tcp" } } ?: return null
            val reality = child(proxy, "reality-opts")
            val tls = when {
                reality != null -> ManualSecurity.Reality
                type == ManualType.Trojan || type == ManualType.Hysteria2 || type == ManualType.Tuic || flag(proxy, "tls") -> ManualSecurity.Tls
                else -> ManualSecurity.None
            }
            val ws = child(proxy, "ws-opts")
            val h2 = child(proxy, "h2-opts")
            val address = listOf(text(proxy, "ip"), text(proxy, "ipv6")).filter { it.isNotEmpty() }.joinToString(", ")
            return ManualServer(
                type = type,
                name = text(proxy, "name"),
                host = text(proxy, "server"),
                port = text(proxy, "port"),
                user = text(proxy, "username"),
                password = text(proxy, "password"),
                uuid = text(proxy, "uuid"),
                cipher = text(proxy, "cipher").takeIf { it in SS_CIPHERS } ?: SS_CIPHERS.first(),
                vision = text(proxy, "flow") == VISION_FLOW,
                security = tls,
                sni = text(proxy, "servername").ifEmpty { text(proxy, "sni") },
                fingerprint = text(proxy, "client-fingerprint").takeIf { it in FINGERPRINTS } ?: FINGERPRINTS.first(),
                insecure = flag(proxy, "skip-cert-verify"),
                realityKey = text(reality, "public-key"),
                shortId = text(reality, "short-id"),
                transport = network,
                path = text(ws, "path").ifEmpty { text(h2, "path") },
                hostHeader = text(child(ws, "headers"), "Host").ifEmpty { ((h2?.get("host") as? JsonArray)?.firstOrNull() as? JsonElement)?.jsonPrimitive?.contentOrNull.orEmpty() },
                service = text(child(proxy, "grpc-opts"), "grpc-service-name"),
                obfsPassword = text(proxy, "obfs-password"),
                privateKey = text(proxy, "private-key"),
                peerKey = text(proxy, "public-key"),
                presharedKey = text(proxy, "pre-shared-key"),
                address = address,
                amnezia = amneziaOpts?.entries?.joinToString("\n") { (key, value) -> "${amneziaTitle(key)} = ${(value as? JsonPrimitive)?.contentOrNull.orEmpty()}" }.orEmpty(),
                original = proxy,
            )
        }
    }
}

enum class ManualField { Host, Port, Password, Uuid, Keys, Address, RealityKey, Amnezia }

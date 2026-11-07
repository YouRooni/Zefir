package dev.rooni.zefir.app

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.net.URLEncoder
import java.util.Base64

private fun encode(text: String) = URLEncoder.encode(text, "UTF-8").replace("+", "%20")

private fun Map<*, *>.text(key: String) = this[key]?.toString()?.ifBlank { null }

private fun Map<*, *>.section(key: String) = this[key] as? Map<*, *>

private fun hostPort(proxy: Map<*, *>): String? {
    val host = proxy.text("server") ?: return null
    val port = proxy.text("port") ?: return null
    return (if (':' in host && !host.startsWith("[")) "[$host]" else host) + ":$port"
}

private fun query(params: List<Pair<String, String?>>) =
    params.filter { it.second != null }.joinToString("&") { (key, value) -> "$key=${encode(value!!)}" }.let { if (it.isEmpty()) "" else "?$it" }

private fun transport(proxy: Map<*, *>): List<Pair<String, String?>> {
    val network = proxy.text("network") ?: "tcp"
    val ws = proxy.section("ws-opts")
    val http = proxy.section("http-opts") ?: proxy.section("h2-opts")
    val xhttp = proxy.section("xhttp-opts")
    val path = ws?.text("path") ?: xhttp?.text("path") ?: when (val value = http?.get("path")) {
        is List<*> -> value.firstOrNull()?.toString()
        else -> value?.toString()
    }
    val hostValue = ws?.section("headers")?.text("Host") ?: xhttp?.text("host") ?: when (val value = http?.get("host")) {
        is List<*> -> value.firstOrNull()?.toString()
        else -> value?.toString()
    }
    return listOf(
        "type" to network,
        "path" to path,
        "host" to hostValue,
        "serviceName" to proxy.section("grpc-opts")?.text("grpc-service-name"),
    )
}

private fun tlsParams(proxy: Map<*, *>, tls: Boolean): List<Pair<String, String?>> {
    val reality = proxy.section("reality-opts")
    return listOf(
        "security" to if (reality != null) "reality" else if (tls) "tls" else "none",
        "sni" to (proxy.text("servername") ?: proxy.text("sni")),
        "fp" to proxy.text("client-fingerprint"),
        "pbk" to reality?.text("public-key"),
        "sid" to reality?.text("short-id"),
        "alpn" to (proxy["alpn"] as? List<*>)?.joinToString(","),
        "allowInsecure" to if (proxy["skip-cert-verify"] == true) "1" else null,
    )
}

fun proxyToLink(proxy: Map<*, *>): String? {
    val name = encode(proxy.text("name").orEmpty())
    val address = hostPort(proxy) ?: return null
    return when (proxy.text("type")?.lowercase()) {
        "vless" -> {
            val id = proxy.text("uuid") ?: return null
            val params = transport(proxy) + tlsParams(proxy, proxy["tls"] == true) + ("flow" to proxy.text("flow"))
            "vless://$id@$address${query(params)}#$name"
        }
        "trojan" -> {
            val password = proxy.text("password") ?: return null
            "trojan://${encode(password)}@$address${query(transport(proxy) + tlsParams(proxy, true))}#$name"
        }
        "ss" -> {
            if (proxy.text("plugin") != null) return null
            val secret = "${proxy.text("cipher") ?: return null}:${proxy.text("password") ?: return null}"
            "ss://${Base64.getUrlEncoder().withoutPadding().encodeToString(secret.toByteArray())}@$address#$name"
        }
        "hysteria2" -> {
            val password = proxy.text("password") ?: return null
            val params = listOf(
                "sni" to (proxy.text("sni") ?: proxy.text("servername")),
                "obfs" to proxy.text("obfs"),
                "obfs-password" to proxy.text("obfs-password"),
                "insecure" to if (proxy["skip-cert-verify"] == true) "1" else null,
            )
            "hysteria2://${encode(password)}@$address${query(params)}#$name"
        }
        "vmess" -> {
            val params = transport(proxy).toMap()
            val json = kotlinx.serialization.json.buildJsonObject {
                fun add(key: String, value: String?) = put(key, kotlinx.serialization.json.JsonPrimitive(value.orEmpty()))
                add("v", "2")
                add("ps", proxy.text("name"))
                add("add", proxy.text("server"))
                add("port", proxy.text("port"))
                add("id", proxy.text("uuid") ?: return null)
                add("aid", proxy.text("alterId") ?: "0")
                add("scy", proxy.text("cipher") ?: "auto")
                add("net", params["type"])
                add("host", params["host"])
                add("path", params["path"] ?: params["serviceName"])
                add("tls", if (proxy["tls"] == true) "tls" else "")
                add("sni", proxy.text("servername"))
            }
            "vmess://${Base64.getEncoder().encodeToString(json.toString().toByteArray())}"
        }
        "wireguard" -> {
            val key = proxy.text("private-key") ?: return null
            val own = listOfNotNull(proxy.text("ip"), proxy.text("ipv6")).joinToString(",")
            val amnezia = proxy.section("amnezia-wg-option")?.entries?.map { it.key.toString() to it.value?.toString() }.orEmpty()
            val params = listOf(
                "publickey" to proxy.text("public-key"),
                "address" to own,
                "presharedkey" to proxy.text("pre-shared-key"),
                "reserved" to (proxy["reserved"] as? List<*>)?.joinToString(","),
                "mtu" to proxy.text("mtu"),
                "keepalive" to proxy.text("persistent-keepalive"),
                "allowedips" to (proxy["allowed-ips"] as? List<*>)?.joinToString(","),
            ) + amnezia
            "wireguard://${encode(key)}@$address${query(params)}#$name"
        }
        else -> null
    }
}

fun findServerLink(config: String, name: String): String? {
    val root = runCatching { Yaml(SafeConstructor(LoaderOptions())).load<Any?>(config) as? Map<*, *> }.getOrNull() ?: return null
    val proxy = (root["proxies"] as? List<*>)?.filterIsInstance<Map<*, *>>()?.firstOrNull { it["name"]?.toString() == name } ?: return null
    return proxyToLink(proxy)
}

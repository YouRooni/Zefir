package dev.rooni.zefir.app

import java.net.URLDecoder

val SERVER_SCHEMES = setOf(
    "vless", "vmess", "trojan", "ss", "ssr", "socks", "socks5", "socks5h",
    "hysteria", "hysteria2", "hy2", "tuic", "anytls", "mierus", "wireguard", "wg", "awg",
)

private val LINK_START = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*(://|%3A%2F%2F)", RegexOption.IGNORE_CASE)

private val ACTIONS = setOf("add", "import", "install-config", "install-sub", "sub", "subscription", "config", "profile")

private fun decode(value: String) = runCatching { URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }.getOrDefault(value)

fun unwrapImportLink(raw: String, depth: Int = 0): String {
    val text = raw.trim()
    if (depth > 3 || text.any { it == '\n' }) return text
    val scheme = text.substringBefore("://", "").lowercase()
    if (scheme.isEmpty() || scheme == "http" || scheme == "https" || scheme in SERVER_SCHEMES) return text
    val rest = text.substringAfter("://")

    val body = rest.substringBefore('#')
    if (scheme == "sub") {
        val decoded = runCatching { decodeBase64Text(body.substringBefore('?')).trim() }.getOrNull()
        if (decoded != null && LINK_START.containsMatchIn(decoded)) return unwrapImportLink(decoded, depth + 1)
    }

    val action = body.substringBefore('/').lowercase()
    val path = if (action in ACTIONS && '/' in rest) rest.substringAfter('/') else rest
    if (LINK_START.containsMatchIn(path)) {
        val inner = if (path.contains("%3A", true) && "://" !in path) decode(path) else path

        val keepName = inner.substringBefore("://", "").lowercase() in SERVER_SCHEMES
        return unwrapImportLink(if (keepName) inner else inner.substringBefore('#'), depth + 1)
    }
    val url = body.substringAfter('?', "").split('&').firstOrNull { it.startsWith("url=", true) }?.substring(4)
    if (!url.isNullOrEmpty()) return unwrapImportLink(decode(url), depth + 1)
    return text
}

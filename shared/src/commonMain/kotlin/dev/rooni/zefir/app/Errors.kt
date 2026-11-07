package dev.rooni.zefir.app

import dev.rooni.zefir.res.*
import org.jetbrains.compose.resources.StringResource

fun friendlyError(raw: String?): StringResource {
    val text = raw.orEmpty().lowercase()
    fun has(vararg parts: String) = parts.any { it in text }
    return when {
        has("no such host", "lookup ", "dns", "resolve") -> Res.string.err_dns
        has("x509", "certificate", "tls", "handshake", "reality") -> Res.string.err_tls
        has("establish", "permission", "tun", "revoked", "vpn") -> Res.string.err_vpn
        has("yaml", "parse", "unmarshal", "config", "proxy group", "invalid", "geodata", "geoip") -> Res.string.err_config
        has("timeout", "deadline", "timed out") -> Res.string.err_timeout
        has("refused", "unreachable", "no route", "reset by peer", "broken pipe", "eof", "network") -> Res.string.err_network
        else -> Res.string.err_generic
    }
}

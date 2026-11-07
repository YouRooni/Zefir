package dev.rooni.zefir.app

import dev.rooni.zefir.platform.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

data class ServerAddress(val host: String, val port: Int)

private val UDP_TYPES = setOf("hysteria", "hysteria2", "tuic", "wireguard", "shadowquic", "masque")

fun findServerAddresses(config: String): Map<String, ServerAddress> {
    val root = runCatching { Yaml(SafeConstructor(LoaderOptions())).load<Any?>(config) as? Map<*, *> }.getOrNull() ?: return emptyMap()
    return (root["proxies"] as? List<*>)?.filterIsInstance<Map<*, *>>().orEmpty().mapNotNull { proxy ->
        if (proxy["type"]?.toString()?.lowercase() in UDP_TYPES) return@mapNotNull null
        val name = proxy["name"]?.toString() ?: return@mapNotNull null
        val host = proxy["server"]?.toString()?.trim('[', ']')?.ifBlank { null } ?: return@mapNotNull null
        val port = proxy["port"]?.toString()?.toIntOrNull() ?: return@mapNotNull null
        name to ServerAddress(host, port)
    }.toMap()
}

private fun isIpLiteral(host: String) = ':' in host || host.all { it.isDigit() || it == '.' }

fun dnsQuery(host: String, id: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    out.write(byteArrayOf((id shr 8).toByte(), id.toByte(), 1, 0, 0, 1, 0, 0, 0, 0, 0, 0))
    host.trimEnd('.').split('.').forEach { label ->
        val bytes = label.encodeToByteArray()
        out.write(bytes.size)
        out.write(bytes)
    }
    out.write(byteArrayOf(0, 0, 1, 0, 1))
    return out.toByteArray()
}

fun dnsFirstA(reply: ByteArray, size: Int, id: Int): ByteArray? {
    fun byte(at: Int) = reply[at].toInt() and 0xFF
    fun word(at: Int) = byte(at) shl 8 or byte(at + 1)
    if (size < 12 || word(0) != id) return null
    fun skipName(start: Int): Int {
        var at = start
        while (true) {
            val length = byte(at)
            if (length == 0) return at + 1
            if (length >= 0xC0) return at + 2
            at += length + 1
        }
    }
    return runCatching {
        var at = 12
        repeat(word(4)) { at = skipName(at) + 4 }
        repeat(word(6)) {
            at = skipName(at)
            val type = word(at)
            val length = word(at + 8)
            at += 10
            if (type == 1 && length == 4) return reply.copyOfRange(at, at + 4)
            at += length
        }
        null
    }.getOrNull()
}

class TcpPinger(private val platform: Platform) {
    private val resolved = ConcurrentHashMap<String, InetAddress>()

    fun forget() = resolved.clear()

    suspend fun ping(address: ServerAddress, tunnelOn: Boolean): Int = withContext(Dispatchers.IO) {
        runCatching {
            val ip = resolve(address.host, tunnelOn) ?: return@runCatching -1
            SocketChannel.open().use { channel ->
                val socket = channel.socket()
                platform.protectSocket(socket)
                val start = System.nanoTime()
                socket.connect(InetSocketAddress(ip, address.port), TIMEOUT)
                ((System.nanoTime() - start) / 1_000_000).toInt().coerceAtLeast(1)
            }
        }.getOrDefault(-1)
    }

    private fun resolve(host: String, tunnelOn: Boolean): InetAddress? {
        if (isIpLiteral(host) || !tunnelOn) return InetAddress.getByName(host)
        resolved[host]?.let { return it }
        return DNS_SERVERS.firstNotNullOfOrNull { server -> runCatching { ask(host, server) }.getOrNull() }?.also { resolved[host] = it }
    }

    private fun ask(host: String, server: String): InetAddress? = DatagramSocket().use { socket ->
        platform.protectSocket(socket)
        socket.soTimeout = DNS_TIMEOUT
        val id = Random.nextInt(0xFFFF)
        val query = dnsQuery(host, id)
        socket.send(DatagramPacket(query, query.size, InetAddress.getByName(server), 53))
        val reply = ByteArray(1500)
        val packet = DatagramPacket(reply, reply.size)
        socket.receive(packet)
        dnsFirstA(reply, packet.length, id)?.let(InetAddress::getByAddress)
    }

    private companion object {
        const val TIMEOUT = 3000
        const val DNS_TIMEOUT = 1500
        val DNS_SERVERS = listOf("8.8.8.8", "1.1.1.1")
    }
}

package dev.rooni.zefir

import java.math.BigInteger
import java.net.InetAddress

// Маршруты "весь интернет кроме локалки". Нужны, чтобы трафик к машине (Android Auto),
// роутеру, принтеру и прочему в LAN шёл мимо туннеля прямо через Wi-Fi.
// excludeRoute есть только с API 33, поэтому просто считаем дополнение сами.
internal object LocalRoutes {
    private val V4_LOCAL = listOf(
        "0.0.0.0/8",
        "10.0.0.0/8",
        "127.0.0.0/8",
        "169.254.0.0/16",
        "172.16.0.0/12",
        "192.168.0.0/16",
        "224.0.0.0/3", // мультикаст, резерв и broadcast
    )

    private val V6_LOCAL = listOf(
        "fc00::/7",
        "fe80::/10",
        "ff00::/8",
    )

    val v4: List<Pair<String, Int>> by lazy { complement(V4_LOCAL, 32) }
    val v6: List<Pair<String, Int>> by lazy { complement(V6_LOCAL, 128) }

    private fun complement(excluded: List<String>, bits: Int): List<Pair<String, Int>> {
        val ranges = excluded.map { cidr ->
            val (ip, len) = cidr.split('/')
            val start = BigInteger(1, InetAddress.getByName(ip).address)
            start to start + BigInteger.ONE.shiftLeft(bits - len.toInt()) - BigInteger.ONE
        }.sortedBy { it.first }

        val out = mutableListOf<Pair<String, Int>>()
        var cursor = BigInteger.ZERO
        for ((start, end) in ranges) {
            if (start > cursor) split(cursor, start - BigInteger.ONE, bits, out)
            if (end + BigInteger.ONE > cursor) cursor = end + BigInteger.ONE
        }
        val max = BigInteger.ONE.shiftLeft(bits) - BigInteger.ONE
        if (cursor <= max) split(cursor, max, bits, out)
        return out
    }

    // Режем диапазон [from, to] на минимальный набор CIDR-блоков
    private fun split(from: BigInteger, to: BigInteger, bits: Int, out: MutableList<Pair<String, Int>>) {
        var start = from
        while (start <= to) {
            var size = if (start.signum() == 0) bits else start.lowestSetBit.coerceAtMost(bits)
            while (size > 0 && start + BigInteger.ONE.shiftLeft(size) - BigInteger.ONE > to) size--
            out += toAddress(start, bits) to (bits - size)
            start += BigInteger.ONE.shiftLeft(size)
        }
    }

    private fun toAddress(value: BigInteger, bits: Int): String {
        val bytes = ByteArray(bits / 8)
        val raw = value.toByteArray()
        val n = minOf(raw.size, bytes.size)
        System.arraycopy(raw, raw.size - n, bytes, bytes.size - n, n)
        return InetAddress.getByAddress(bytes).hostAddress!!
    }
}

package dev.rooni.zefir.app

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ServerPingTest {
    private val query = dnsQuery("example.com", 0x1234)

    private fun reply(vararg answer: Int): ByteArray {
        val head = query.copyOf()
        head[2] = 0x81.toByte()
        head[7] = 2
        return head + answer.map { it.toByte() }.toByteArray()
    }

    @Test
    fun skipsOtherRecordsBeforeA() {
        val data = reply(
            0xC0, 12, 0, 5, 0, 1, 0, 0, 0, 60, 0, 2, 0xC0, 12,
            0xC0, 12, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 93, 184, 216, 34,
        )
        val ip = dnsFirstA(data, data.size, 0x1234)!!.map { it.toInt() and 0xFF }
        assertContentEquals(listOf(93, 184, 216, 34), ip)
    }

    @Test
    fun rejectsForeignIdAndBrokenReply() {
        val data = reply(0xC0, 12, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 1, 2)
        assertNull(dnsFirstA(data, data.size, 0x4321))
        assertNull(dnsFirstA(data, data.size, 0x1234))
    }

    @Test
    fun readsAddressesFromConfig() {
        val config = """
            proxies:
              - {name: Sweden, server: se.example.com, port: 443}
              - {name: V6, server: "[2001:db8::1]", port: 8443}
              - {name: NoPort, server: x.example.com}
        """.trimIndent()
        val found = findServerAddresses(config)
        assertEquals(ServerAddress("se.example.com", 443), found["Sweden"])
        assertEquals(ServerAddress("2001:db8::1", 8443), found["V6"])
        assertNull(found["NoPort"])
    }
}

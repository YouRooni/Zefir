package dev.rooni.zefir.app

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerLinkTest {
    private val yaml = """
proxies:
  - name: "Россия 1"
    type: vless
    server: example.com
    port: 443
    uuid: 11111111-2222-3333-4444-555555555555
    network: ws
    tls: true
    servername: cdn.example.com
    client-fingerprint: chrome
    flow: xtls-rprx-vision
    ws-opts:
      path: /a b
      headers:
        Host: h.example.com
    reality-opts:
      public-key: PBK
      short-id: "0011"
  - {name: ss1, type: ss, server: "2001:db8::1", port: 8388, cipher: aes-128-gcm, password: pw}
  - {name: ss2, type: ss, server: a.b, port: 1, cipher: x, password: y, plugin: obfs}
  - {name: vm, type: vmess, server: a.b, port: 2, uuid: u, alterId: 0, cipher: auto, tls: true}
  - {name: unknown, type: snell, server: a.b, port: 3}
"""

    @Test
    fun vlessKeepsAllParameters() {
        val link = assertNotNull(findServerLink(yaml, "Россия 1"))
        assertTrue(link.startsWith("vless://11111111-2222-3333-4444-555555555555@example.com:443?"), link)
        listOf("type=ws", "path=%2Fa%20b", "host=h.example.com", "security=reality", "sni=cdn.example.com", "fp=chrome", "pbk=PBK", "sid=0011", "flow=xtls-rprx-vision").forEach {
            assertTrue(it in link, "$it in $link")
        }
        assertTrue(link.endsWith("#%D0%A0%D0%BE%D1%81%D1%81%D0%B8%D1%8F%201"), link)
    }

    @Test
    fun shadowsocksWrapsIpv6() {
        val link = assertNotNull(findServerLink(yaml, "ss1"))
        assertTrue("@[2001:db8::1]:8388#ss1" in link, link)
        val secret = link.removePrefix("ss://").substringBefore('@')
        assertEquals("aes-128-gcm:pw", String(Base64.getUrlDecoder().decode(secret)))
    }

    @Test
    fun vmessIsBase64Json() {
        val link = assertNotNull(findServerLink(yaml, "vm"))
        val json = String(Base64.getDecoder().decode(link.removePrefix("vmess://")))
        assertTrue("\"add\":\"a.b\"" in json && "\"tls\":\"tls\"" in json, json)
    }

    @Test
    fun unsupportedGiveNoLink() {
        assertNull(findServerLink(yaml, "ss2"))
        assertNull(findServerLink(yaml, "unknown"))
        assertNull(findServerLink(yaml, "нет такого"))
        assertNull(findServerLink("not: [valid", "x"))
    }

    @Test
    fun readsGeneratedJsonConfig() {
        val json = """{"proxies":[{"name":"j","type":"trojan","server":"a.b","port":443,"password":"p w"}]}"""
        assertEquals("trojan://p%20w@a.b:443?type=tcp&security=tls#j", findServerLink(json, "j"))
    }
}

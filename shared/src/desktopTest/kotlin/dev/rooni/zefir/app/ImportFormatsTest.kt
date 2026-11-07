package dev.rooni.zefir.app

import kotlinx.serialization.json.*
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImportFormatsTest {
    private val sub = "https://example.com/sub?token=a%2Bb&x=1"
    private val encoded = "https%3A%2F%2Fexample.com%2Fsub%3Ftoken%3Da%252Bb%26x%3D1"

    @Test
    fun unwrapsEveryClientWrapper() {
        listOf(
            "clash://install-config?url=$encoded&name=x",
            "clashmeta://install-config?url=$encoded",
            "flclash://install-config?url=$encoded",
            "flclashx://install-config?url=$encoded",
            "stash://install-config?url=$encoded",
            "karing://install-config?url=$encoded&name=x",
            "v2rayng://install-config?url=$encoded#x",
            "v2box://install-sub?url=$encoded&name=x",
            "sing-box://import-remote-profile?url=$encoded#x",
            "sn://subscription?url=$encoded",
            "zefir://add?url=$encoded",
            "happ://add/$sub",
            "hiddify://import/$sub#x",
            "v2raytun://import/$sub#x",
            "streisand://import/$sub#x",
            "zefir://add/$sub",
            "zefir://add/$encoded",
            "sub://" + Base64.getEncoder().encodeToString(sub.toByteArray()) + "#x",
            "shadowrocket://add/sub://" + Base64.getEncoder().encodeToString(sub.toByteArray()) + "#x",
        ).forEach { assertEquals(sub, unwrapImportLink(it), it) }
    }

    @Test
    fun leavesServersAndPlainTextAlone() {
        val link = "vless://id@host:443?url=not-this#name"
        assertEquals(link, unwrapImportLink(link))
        assertEquals(sub, unwrapImportLink(sub))
        assertEquals("happ://crypt/abc", unwrapImportLink("happ://crypt/abc"))
        assertEquals("a://b\nc://d", unwrapImportLink("a://b\nc://d"))
        assertEquals(link, unwrapImportLink("zefir://import/$link"))
    }

    private val conf = """
        # Name = Дом
        [Interface]
        PrivateKey = cHJpdmF0ZQ==
        Address = 10.0.0.2/32, fd00::2/128
        MTU = 1280
        Jc = 4
        Jmin = 40
        S1 = 15
        H1 = 1234
        I1 = <b 0xc000>

        [Peer]
        PublicKey = cHVibGlj+w==
        PresharedKey = cHNr
        Endpoint = vpn.example.com:51820
        AllowedIPs = 0.0.0.0/0, ::/0
        PersistentKeepalive = 25

        [Peer]
        PublicKey = other
        Endpoint = other.example.com:1
    """.trimIndent()

    @Test
    fun readsAmneziaConf() {
        assertTrue(isWireguardConf(conf))
        val proxy = assertNotNull(parseWireguardConf(conf))
        fun text(key: String) = proxy[key]?.jsonPrimitive?.contentOrNull
        assertEquals("Дом", text("name"))
        assertEquals("wireguard", text("type"))
        assertEquals("vpn.example.com", text("server"))
        assertEquals(51820, proxy["port"]?.jsonPrimitive?.int)
        assertEquals("cHJpdmF0ZQ==", text("private-key"))
        assertEquals("cHVibGlj+w==", text("public-key"))
        assertEquals("10.0.0.2/32", text("ip"))
        assertEquals("fd00::2/128", text("ipv6"))
        assertEquals(2, proxy["allowed-ips"]?.jsonArray?.size)
        val amnezia = proxy["amnezia-wg-option"]!!.jsonObject
        assertEquals(4, amnezia["jc"]?.jsonPrimitive?.int)
        assertEquals("1234", amnezia["h1"]?.jsonPrimitive?.content)
        assertEquals("<b 0xc000>", amnezia["i1"]?.jsonPrimitive?.content)
    }

    @Test
    fun readsAmnezia3Conf() {
        val text = "[Interface]\nPrivateKey = a\nAddress = 10.0.0.2/32\nJc = 3\nH1 = 100-200\nHeaderProtectionKey = AAEC/w==\n" +
            "content_padding_addition = 16\nRandomTrailers = 1\n[Peer]\nPublicKey = b\nEndpoint = 1.2.3.4:51820\n"
        val amnezia = parseWireguardConf(text)!!["amnezia-wg-option"]!!.jsonObject
        assertEquals(3, amnezia["version"]?.jsonPrimitive?.int)
        assertEquals("100-200", amnezia["h1"]?.jsonPrimitive?.content)
        assertEquals("AAEC/w==", amnezia["header-protection-key"]?.jsonPrimitive?.content)
        assertEquals("16", amnezia["content-padding-addition"]?.jsonPrimitive?.content)
        assertEquals(true, amnezia["random-trailers"]?.jsonPrimitive?.content?.toBoolean())
    }

    @Test
    fun oldAmneziaKeepsLegacyVersion() {
        val text = "[Interface]\nPrivateKey = a\nAddress = 10.0.0.2/32\nJc = 3\nS3 = 10\n[Peer]\nPublicKey = b\nEndpoint = 1.2.3.4:51820\n"
        assertNull(parseWireguardConf(text)!!["amnezia-wg-option"]!!.jsonObject["version"])
    }

    @Test
    fun plainConfHasNoAmneziaBlock() {
        val proxy = parseWireguardConf("[Interface]\nPrivateKey = a\nAddress = 10.0.0.2\n[Peer]\nPublicKey = b\nEndpoint = [2001:db8::1]:51820\n")!!
        assertNull(proxy["amnezia-wg-option"])
        assertEquals("2001:db8::1", proxy["server"]?.jsonPrimitive?.content)
    }

    @Test
    fun readsLinksOfDifferentClients() {
        val full = parseWireguardLink("wireguard://cHJp%2Bdg==@1.2.3.4:51820/?publickey=cHVi&address=10.0.0.2/32&reserved=1,2,3&mtu=1280#%D0%94%D0%BE%D0%BC")!!
        assertEquals("Дом", full["name"]?.jsonPrimitive?.content)
        assertEquals("cHJp+dg==", full["private-key"]?.jsonPrimitive?.content)
        assertEquals(listOf(1, 2, 3), full["reserved"]!!.jsonArray.map { it.jsonPrimitive.int })
        val hiddify = parseWireguardLink("wg://host.example:1234?pk=priv&peer_pub=pub&local_address=172.16.0.2&reserved=AQID")!!
        assertEquals("host.example", hiddify["name"]?.jsonPrimitive?.content)
        assertEquals("priv", hiddify["private-key"]?.jsonPrimitive?.content)
        assertEquals(listOf(1, 2, 3), hiddify["reserved"]!!.jsonArray.map { it.jsonPrimitive.int })
        assertNull(parseWireguardLink("wg://host:1?pk=only"))
    }

    @Test
    fun linkSurvivesRoundTrip() {
        val proxy = parseWireguardConf(conf)!!
        val map: Map<String, Any?> = proxy.mapValues { (_, value) ->
            when (value) {
                is JsonPrimitive -> value.contentOrNull
                is JsonArray -> value.map { it.jsonPrimitive.content }
                is JsonObject -> value.mapValues { it.value.jsonPrimitive.content }
            }
        }
        val again = parseWireguardLink(proxyToLink(map)!!)!!
        assertEquals(proxy["private-key"], again["private-key"])
        assertEquals(proxy["public-key"], again["public-key"])
        assertEquals(proxy["ip"], again["ip"])
        assertEquals(proxy["ipv6"], again["ipv6"])
        assertEquals(proxy["amnezia-wg-option"]!!.jsonObject["i1"], again["amnezia-wg-option"]!!.jsonObject["i1"])
    }

    @Test
    fun udpServersSkipTcpPing() {
        val found = findServerAddresses(
            """
            proxies:
              - {name: A, type: vless, server: a.example, port: 443}
              - {name: B, type: hysteria2, server: b.example, port: 443}
              - {name: C, type: tuic, server: c.example, port: 443}
              - {name: D, type: wireguard, server: d.example, port: 51820}
            """.trimIndent(),
        )
        assertTrue("A" in found)
        assertFalse("B" in found || "C" in found || "D" in found)
    }
}

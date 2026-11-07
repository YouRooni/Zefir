package dev.rooni.zefir.app

import dev.rooni.zefir.model.AppSettings
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Release117Test {
    @Test
    fun chainsAddDialerProxyAndKeepShortIdText() {
        val yaml = """
            proxies:
              - {name: A, type: vless, server: a.com, port: 443, uuid: u, reality-opts: {public-key: k, short-id: 0123}}
              - {name: B, type: ss, server: b.com, port: 8388, cipher: aes-128-gcm, password: on}
            proxy-groups:
              - {name: S, type: select, proxies: [A, B]}
        """.trimIndent()
        val tuned = Json.parseToJsonElement(tuneProfile(yaml, null, mapOf("A" to "B", "B" to "Gone"))!!).jsonObject
        val proxies = tuned["proxies"]!!.jsonArray.map { it.jsonObject }
        assertEquals("B", proxies[0]["dialer-proxy"]!!.jsonPrimitive.content)
        assertNull(proxies[1]["dialer-proxy"])
        val shortId = proxies[0]["reality-opts"]!!.jsonObject["short-id"]!!.jsonPrimitive
        assertTrue(shortId.isString)
        assertEquals("0123", shortId.content)
        assertEquals("on", proxies[1]["password"]!!.jsonPrimitive.content)
    }

    @Test
    fun nothingToTuneReturnsNull() {
        assertNull(tuneProfile("proxies: [{name: A, type: socks5, server: a, port: 1}]", 600, emptyMap()))
    }

    @Test
    fun logsLoseTokens() {
        val line = "import: failed https://panel.example.com/sub/AbCdEfGhIjKlMnOpQrStUvWxYz012345?x=1 uuid 3f1c2a9b-7d8e-4f60-9a1b-2c3d4e5f6a7b"
        val clean = redactLog(line)
        assertFalse("AbCdEf" in clean)
        assertFalse("3f1c2a9b" in clean)
        assertTrue("https://panel.example.com/…" in clean)
    }

    @Test
    fun manualServersBecomeProxies() {
        val socks = ManualServer(ManualType.Socks, host = " 1.2.3.4 ", port = "1080", user = "u", password = "p").toProxy()
        assertEquals("socks5", socks["type"]!!.jsonPrimitive.content)
        assertEquals("1.2.3.4", socks["server"]!!.jsonPrimitive.content)
        assertEquals(1080, socks["port"]!!.jsonPrimitive.int)
        val http = ManualServer(ManualType.Http, host = "p.com", port = "443", security = ManualSecurity.Tls).toProxy()
        assertEquals(true, http["tls"]!!.jsonPrimitive.boolean)
        assertNull(http["username"])
        assertEquals(ManualField.Password, ManualServer(ManualType.Shadowsocks, host = "s.com", port = "8388").missing)
        assertEquals(ManualField.Port, ManualServer(host = "s.com", port = "70000").missing)
        val back = ManualServer.from(socks)!!
        assertEquals("u", back.user)
        assertEquals(ManualType.Socks, back.type)
    }

    @Test
    fun manualVlessRealityRoundTrip() {
        val form = ManualServer(
            ManualType.Vless, host = "v.com", port = "443", uuid = "id-1", vision = true,
            security = ManualSecurity.Reality, sni = "www.microsoft.com", realityKey = "pbk", shortId = "0123",
        )
        assertEquals(null, form.missing)
        val proxy = form.toProxy()
        assertEquals("vless", proxy["type"]!!.jsonPrimitive.content)
        assertEquals(VISION_FLOW, proxy["flow"]!!.jsonPrimitive.content)
        assertEquals("www.microsoft.com", proxy["servername"]!!.jsonPrimitive.content)
        assertEquals("0123", proxy["reality-opts"]!!.jsonObject["short-id"]!!.jsonPrimitive.content)
        val back = ManualServer.from(proxy)!!
        assertEquals(ManualSecurity.Reality, back.security)
        assertEquals("pbk", back.realityKey)
        assertTrue(back.vision)
        assertEquals(ManualField.RealityKey, form.copy(realityKey = "").missing)
    }

    @Test
    fun manualEditKeepsUnknownKeys() {
        val original = buildJsonObject {
            put("name", "wg"); put("type", "wireguard"); put("server", "w.com"); put("port", 51820)
            put("private-key", "a"); put("public-key", "b"); put("ip", "10.0.0.2/32")
            putJsonArray("reserved") { add(1); add(2); add(3) }
        }
        val form = ManualServer.from(original)!!
        assertEquals("10.0.0.2/32", form.address)
        val saved = form.copy(port = "51821").toProxy()
        assertEquals(51821, saved["port"]!!.jsonPrimitive.int)
        assertTrue("reserved" in saved)
        assertFalse("reserved" in form.withType(ManualType.Socks).toProxy())
        assertNull(ManualServer.from(buildJsonObject { put("type", "vless"); put("network", "xhttp") }))
    }

    @Test
    fun manualAmneziaRoundTrip() {
        val form = ManualServer(
            ManualType.Amnezia, host = "a.com", port = "51820", privateKey = "k", peerKey = "p", address = "10.8.0.2/32",
            amnezia = "Jc = 4\nJmin = 40\nJmax = 70\nS1 = 15\nH1 = 123456\nI1 = <b 0xf6ab>\nfoo = 1",
        )
        assertEquals(null, form.missing)
        val opts = form.toProxy()["amnezia-wg-option"]!!.jsonObject
        assertEquals(4, opts["jc"]!!.jsonPrimitive.int)
        assertEquals("123456", opts["h1"]!!.jsonPrimitive.content)
        assertEquals("<b 0xf6ab>", opts["i1"]!!.jsonPrimitive.content)
        assertNull(opts["foo"])
        val back = ManualServer.from(form.toProxy())!!
        assertEquals(ManualType.Amnezia, back.type)
        assertTrue("Jc = 4" in back.amnezia)
        assertEquals(ManualField.Amnezia, form.copy(amnezia = "").missing)
        assertFalse("amnezia-wg-option" in form.withType(ManualType.WireGuard).toProxy())
    }

    @Test
    fun clashSnippetGivesProxies() {
        val list = clashProxies("- {name: a, type: ss, server: s.com, port: 8388, cipher: aes-128-gcm, password: p}\n- {name: b, type: trojan, server: t.com, port: 443, password: q}")
        assertEquals(listOf("a", "b"), list.map { it["name"]!!.jsonPrimitive.content })
        assertEquals(1, clashProxies("proxies:\n  - {name: c, type: socks5, server: x.com, port: 1080}").size)
    }

    @Test
    fun overlayDropsListenersFromSubscription() {
        val base = buildJsonObject {
            putJsonArray("listeners") { addJsonObject { put("type", "mixed"); put("listen", "0.0.0.0") } }
            put("redir-port", 7892)
            put("tproxy-port", 7893)
            putJsonObject("dns") { put("enable", true); put("listen", "0.0.0.0:53") }
            putJsonObject("geox-url") { put("geoip", "https://example.com/geoip.dat") }
        }
        val overlay = buildOverlay(base, AppSettings(), null, "S")
        assertNull(overlay["listeners"])
        assertNull(overlay["redir-port"])
        assertNull(overlay["tproxy-port"])
        assertTrue("geox-url" in overlay)
        assertEquals(false, overlay["allow-lan"]!!.jsonPrimitive.boolean)
    }
}

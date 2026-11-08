package dev.rooni.zefir.app

import dev.rooni.zefir.platform.linuxAuthorizeScript
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Release118Test {
    @Test
    fun balancerMembersKeepOutboundTags() {
        fun out(tag: String, host: String) = """{"tag":"$tag","protocol":"vless","settings":{"vnext":[{"address":"$host","port":443,"users":[{"id":"u"}]}]}}"""
        val json = """[{"remarks":"🇱🇹 Литва","outbounds":[${out("proxy", "a.com")},${out("bypass-2", "b.com")}],
            "routing":{"balancers":[{"tag":"lt","selector":["proxy","bypass"]}],"rules":[{"balancerTag":"lt"}]}}]"""
        val server = parseXraySubscription(json).single()
        assertEquals(listOf("🇱🇹 Литва [proxy]", "🇱🇹 Литва [bypass-2]"), server.members.map { it["name"]!!.jsonPrimitive.content })
    }

    @Test
    fun balancerWithoutSelectorTakesAllOutbounds() {
        fun out(tag: String, host: String) = """{"tag":"$tag","protocol":"trojan","settings":{"servers":[{"address":"$host","port":443,"password":"p"}]}}"""
        val json = """{"remarks":"X","outbounds":[${out("a", "a.com")},${out("b", "b.com")}],"routing":{"balancers":[{"tag":"x"}]}}"""
        assertEquals(2, parseXraySubscription(json).single().members.size)
    }

    @Test
    fun dividersAreTidied() {
        val d = dev.rooni.zefir.model.HomeWidget.Divider
        val s = dev.rooni.zefir.model.HomeWidget.Server
        val m = dev.rooni.zefir.model.HomeWidget.Mode
        assertEquals(listOf(s, d, m), dev.rooni.zefir.ui.screens.tidyWidgets(listOf(d, s, d, d, m, d)))
    }

    @Test
    fun anyTlsAndXhttpBuild() {
        val tls = ManualServer(type = ManualType.AnyTls, host = "a.b", port = "443", password = "p", security = ManualSecurity.Tls, sni = "x.y").toProxy()
        assertEquals("anytls", tls["type"]!!.jsonPrimitive.content)
        assertEquals("x.y", tls["sni"]!!.jsonPrimitive.content)
        val vless = ManualServer(type = ManualType.Vless, host = "a.b", port = "443", uuid = "u", security = ManualSecurity.Tls, transport = ManualTransport.Xhttp, path = "/p").toProxy()
        assertEquals("xhttp", vless["network"]!!.jsonPrimitive.content)
        assertEquals(ManualTransport.Xhttp, ManualServer.from(vless)!!.transport)
    }

    @Test
    fun linuxAuthorizeQuotesPath() {
        val script = linuxAuthorizeScript("/home/a b/it's/vpncore")
        val quoted = """'/home/a b/it'\''s/vpncore'"""
        assertTrue("chown \"root:\$group\" $quoted" in script)
        assertTrue(script.endsWith("chmod 4750 $quoted"))
    }
}

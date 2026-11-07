package dev.rooni.zefir.app

import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubscriptionMetaTest {
    private fun b64(text: String) = Base64.getEncoder().encodeToString(text.encodeToByteArray())

    @Test
    fun readsParamsFromBody() {
        val body = """
            #profile-title: base64:${b64("Мой VPN")}
            #profile-update-interval: 6
            #subscription-userinfo: upload=10; download=20; total=100; expire=1800000000
            #support-url: https://t.me/help
            #announce: base64:${b64("Новые серверы")}
            vless://id@host:443#Name
        """.trimIndent()
        val meta = parseSubscriptionMeta({ null }, body)
        assertEquals("Мой VPN", meta.title)
        assertEquals(6, meta.updateHours)
        assertEquals(30, meta.used)
        assertEquals(100, meta.total)
        assertEquals(1800000000, meta.expire)
        assertEquals("https://t.me/help", meta.supportUrl)
        assertEquals("Новые серверы", meta.announce)
    }

    @Test
    fun headerWinsOverBody() {
        val meta = parseSubscriptionMeta({ if (it == "profile-title") "Заголовок" else null }, "#profile-title: Тело")
        assertEquals("Заголовок", meta.title)
    }

    @Test
    fun dropsDangerousLinks() {
        val meta = parseSubscriptionMeta({ null }, "#support-url: javascript:alert(1)\n#new-url: http://plain.example/x\n#sub-info-text: 0")
        assertNull(meta.supportUrl)
        assertNull(meta.newUrl)
        assertNull(meta.info)
    }

    @Test
    fun routingBecomesRules() {
        val json = """{"Name":"RU","GlobalProxy":"false","DirectSites":["geosite:ru","domain:ya.ru","full:a.b","bad,DIRECT"],"DirectIp":["geoip:private","10.0.0.1"],"ProxySites":["geosite:youtube"],"BlockSites":["keyword:ads"]}"""
        val routing = assertNotNull(parseSubscriptionMeta({ null }, "happ://routing/add/${b64(json)}\nvless://x").routing)
        assertFalse(routing.globalProxy)
        assertEquals(
            listOf(
                "DOMAIN-KEYWORD,ads,REJECT",
                "GEOSITE,youtube,Servers",
                "GEOSITE,ru,DIRECT",
                "DOMAIN-SUFFIX,ya.ru,DIRECT",
                "DOMAIN,a.b,DIRECT",
                "GEOIP,lan,DIRECT,no-resolve",
                "IP-CIDR,10.0.0.1/32,DIRECT,no-resolve",
                "MATCH,DIRECT",
            ),
            routing.toRules("Servers"),
        )
    }

    @Test
    fun routingCanBeTurnedOffBySubscription() {
        val json = """{"DirectSites":["geosite:ru"]}"""
        assertNull(parseSubscriptionMeta({ null }, "#routing-enable: 0\nhapp://routing/add/${b64(json)}").routing)
        assertNull(parseRoutingLink("happ://routing/off"))
    }

    @Test
    fun cleansLinkTails() {
        val clean = cleanShareLink("vless://id@h:443?a=1#%D0%9D%D0%BB?serverDescription=${b64("быстрый")}&fragment=1,2,3")
        assertEquals("vless://id@h:443?a=1#%D0%9D%D0%BB", clean.link)
        assertEquals("Нл", clean.name)
        assertEquals("быстрый", clean.description)
        assertEquals("ss://x#A?B", cleanShareLink("ss://x#A?B").link)
    }

    @Test
    fun convertsXrayReality() {
        val json = """[{"remarks":"NL","meta":{"serverDescription":"fast"},"outbounds":[
            {"protocol":"freedom","tag":"direct"},
            {"tag":"proxy","protocol":"vless","settings":{"vnext":[{"address":"1.2.3.4","port":443,"users":[{"id":"uuid","flow":"xtls-rprx-vision"}]}]},
             "streamSettings":{"network":"tcp","security":"reality","realitySettings":{"serverName":"a.com","fingerprint":"chrome","publicKey":"pk","shortId":"ab"}}}
        ]}]"""
        assertTrue(isXrayJson(json))
        val server = parseXraySubscription(json).single()
        val proxy = server.proxy
        assertEquals("fast", server.description)
        assertEquals("NL", proxy["name"]!!.jsonPrimitive.content)
        assertEquals("vless", proxy["type"]!!.jsonPrimitive.content)
        assertEquals("a.com", proxy["servername"]!!.jsonPrimitive.content)
        assertEquals("xtls-rprx-vision", proxy["flow"]!!.jsonPrimitive.content)
        assertEquals("pk", proxy["reality-opts"]!!.jsonObject["public-key"]!!.jsonPrimitive.content)
    }

    @Test
    fun xrayBalancerBecomesAutoGroup() {
        fun out(tag: String, host: String) = """{"tag":"$tag","protocol":"vless","settings":{"vnext":[{"address":"$host","port":443,"users":[{"id":"u"}]}]}}"""
        val json = """[{"remarks":"EU Auto","outbounds":[${out("proxy", "a.com")},${out("proxy-2", "b.com")},${out("proxy-3", "c.com")},{"tag":"direct","protocol":"freedom"}],
            "routing":{"balancers":[{"tag":"fast","selector":["proxy"],"strategy":{"type":"leastPing"}}],"rules":[{"balancerTag":"fast","network":"tcp,udp"}]},
            "burstObservatory":{"subjectSelector":["proxy"],"pingConfig":{"destination":"https://example.com/204"}}},
            {"remarks":"NL","outbounds":[${out("proxy", "nl.com")}]}]"""
        val servers = parseXraySubscription(json)
        assertEquals(2, servers.size)
        val auto = servers[0]
        assertEquals("EU Auto", auto.name)
        assertEquals("url-test", auto.balancer!!.type)
        assertEquals("https://example.com/204", auto.balancer!!.url)
        assertEquals(listOf("a.com", "b.com", "c.com"), auto.members.map { it["server"]!!.jsonPrimitive.content })
        assertNull(servers[1].balancer)
    }

    @Test
    fun hugeNumbersMeanNoLimit() {
        assertEquals(Triple(30L, 0L, 0L), parseUserInfo("upload=10; download=20; total=10737418240000000; expire=32503680000"))
        assertEquals(100L, normalizeTotal(100))
        assertEquals(1800000000L, normalizeExpire(1800000000))
        assertEquals(1800000000L, normalizeExpire(1800000000000))
    }

    @Test
    fun recognizesStubServers() {
        val json = """[{"remarks":"Приложение не поддерживается","outbounds":[{"tag":"proxy","protocol":"vless","settings":{"vnext":[{"address":"0.0.0.0","port":1,"users":[{"id":"x"}]}]}}]},
            {"remarks":"NL","outbounds":[{"tag":"proxy","protocol":"vless","settings":{"vnext":[{"address":"nl.example.com","port":443,"users":[{"id":"x"}]}]}}]}]"""
        val servers = parseXraySubscription(json)
        assertEquals(listOf(true, false), servers.map { isStubServer(it.proxy) })
    }

    @Test
    fun clientsAreKnown() {
        assertEquals(SubscriptionClient.Happ, SubscriptionClient.of("happ"))
        assertEquals(SubscriptionClient.Auto, SubscriptionClient.of("нет такого"))
        assertEquals(SubscriptionClient.Plain, SubscriptionClient.autoOrder.first())
    }

    @Test
    fun convertsXrayOthersAndSkipsUnknown() {
        val json = """[
            {"remarks":"T","outbounds":[{"protocol":"trojan","settings":{"servers":[{"address":"t.com","port":443,"password":"p"}]},"streamSettings":{"network":"ws","security":"tls","tlsSettings":{"serverName":"s.com"},"wsSettings":{"path":"/w","headers":{"Host":"h.com"}}}}]},
            {"remarks":"S","outbounds":[{"protocol":"shadowsocks","settings":{"servers":[{"address":"s.com","port":8388,"method":"aes-128-gcm","password":"p"}]}}]},
            {"remarks":"X","outbounds":[{"protocol":"wireguard","settings":{}}]}
        ]"""
        val servers = parseXraySubscription(json).map { it.proxy }
        assertEquals(2, servers.size)
        assertEquals("s.com", servers[0]["sni"]!!.jsonPrimitive.content)
        assertEquals("/w", servers[0]["ws-opts"]!!.jsonObject["path"]!!.jsonPrimitive.content)
        assertEquals("ss", servers[1]["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun convertsXrayHysteria2() {
        val json = """[{"remarks":"H","outbounds":[{"tag":"proxy","protocol":"hysteria","settings":{"address":"h.com","port":443,"version":2},
            "streamSettings":{"network":"hysteria","hysteriaSettings":{"version":2,"auth":"secret"},"security":"tls","tlsSettings":{"serverName":"s.com","fingerprint":"chrome","alpn":["h3"]}}}]}]"""
        val proxy = parseXraySubscription(json).single().proxy
        assertEquals("hysteria2", proxy["type"]!!.jsonPrimitive.content)
        assertEquals("secret", proxy["password"]!!.jsonPrimitive.content)
        assertEquals("s.com", proxy["sni"]!!.jsonPrimitive.content)
        assertEquals(null, proxy["tls"])
        assertEquals(null, proxy["client-fingerprint"])
    }
}

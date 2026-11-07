package dev.rooni.zefir.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuditFixesTest {
    @Test
    fun chainLoopIsDetected() {
        assertTrue(chainCreatesLoop("A", "B", mapOf("B" to "A")))
        assertTrue(chainCreatesLoop("A", "B", mapOf("B" to "C", "C" to "A")))
        assertFalse(chainCreatesLoop("A", "B", mapOf("B" to "C")))
        assertFalse(chainCreatesLoop("A", "B", mapOf("B" to "C", "C" to "B")))
    }

    @Test
    fun tuneProfileSkipsLoopedChain() {
        val config = "proxies:\n" + listOf("A", "B").joinToString("\n") { "  - {name: $it, type: ss, server: ${it.lowercase()}.b, port: 1}" }
        val tuned = tuneProfile(config, null, linkedMapOf("A" to "B", "B" to "A"))!!
        val dialers = Json.parseToJsonElement(tuned).jsonObject.getValue("proxies").jsonArray
            .associate { it.jsonObject.getValue("name").jsonPrimitive.content to it.jsonObject["dialer-proxy"]?.jsonPrimitive?.content }
        assertEquals(mapOf("A" to "B", "B" to null), dialers)
    }

    @Test
    fun tuneProfileRespectsSubscriptionDialer() {
        val config = "proxies:\n  - {name: A, type: ss, server: a.b, port: 1, dialer-proxy: B}\n  - {name: B, type: ss, server: b.b, port: 1}"
        assertEquals(null, tuneProfile(config, null, mapOf("B" to "A")))
    }

    @Test
    fun prereleaseNumbersCompareAsNumbers() {
        assertTrue(Updates.compareVersions("1.2.0-beta.10", "1.2.0-beta.2") > 0)
        assertTrue(Updates.compareVersions("1.2.0-rc.1", "1.2.0-beta.9") > 0)
        assertTrue(Updates.compareVersions("1.2.0", "1.2.0-rc.1") > 0)
        assertTrue(Updates.compareVersions("1.2.0-beta", "1.2.0-beta.1") < 0)
        assertEquals(0, Updates.compareVersions("1.1.7-beta", "1.1.7-beta"))
    }
}

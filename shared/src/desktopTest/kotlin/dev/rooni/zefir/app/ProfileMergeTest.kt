package dev.rooni.zefir.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ProfileMergeTest {
    private fun config(vararg names: String) = "proxies:\n" + names.joinToString("\n") { "  - {name: \"$it\", type: ss, server: ${it.lowercase()}.b, port: 1}" }

    @Test
    fun dropsExactDuplicates() {
        val first = "proxies:\n  - {name: A, type: ss, server: x.y, port: 1, cipher: aes}"
        val second = "proxies:\n  - {port: 1, name: Copy, cipher: aes, type: ss, server: x.y}\n  - {name: B, type: ss, server: x.y, port: 2}"
        val merged = mergeProfileConfigs(listOf(first, second))
        val names = Json.parseToJsonElement(merged.config).jsonObject.getValue("proxies").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content }
        assertEquals(listOf("A", "B"), names)
        assertEquals("A", merged.renames[1]["Copy"])
    }

    @Test
    fun renamesServersThatCollide() {
        val merged = mergeProfileConfigs(listOf(config("DE", "NL"), "proxies:\n  - {name: DE, type: ss, server: other.b, port: 1}"))
        val root = Json.parseToJsonElement(merged.config).jsonObject
        val names = root.getValue("proxies").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content }
        assertEquals(listOf("DE", "NL", "DE 2"), names)
        assertEquals("DE 2", merged.renames[1]["DE"])
    }

    @Test
    fun groupsCoverAllServers() {
        val root = Json.parseToJsonElement(mergeProfileConfigs(listOf(config("A"), config("B"))).config).jsonObject
        val groups = root.getValue("proxy-groups").jsonArray.map { it.jsonObject }
        assertEquals(listOf(AUTO_GROUP, "A", "B"), groups[0].getValue("proxies").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("A", "B"), groups[1].getValue("proxies").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun keepsProviders() {
        val provider = "proxy-providers:\n  p: {type: http, url: \"http://x\", path: ./a.yaml}\n"
        val root = Json.parseToJsonElement(mergeProfileConfigs(listOf(provider, provider)).config).jsonObject
        val providers = root.getValue("proxy-providers").jsonObject
        assertEquals(setOf("p", "p 2"), providers.keys)
        assertNull(providers.getValue("p").jsonObject["path"])
    }

    @Test
    fun refusesConfigsWithoutServers() {
        assertFailsWith<IllegalArgumentException> { mergeProfileConfigs(listOf("mode: rule")) }
    }

    @Test
    fun raisesOnlyShortIntervals() {
        val text = """
            proxy-groups:
              - {name: a, type: url-test, interval: 180}
              - {name: b, type: url-test, interval: 900}
              - {name: c, type: select}
            proxy-providers:
              p: {type: http, health-check: {enable: true, interval: 60}}
        """.trimIndent()
        val root = Json.parseToJsonElement(tuneProfile(text, 600, emptyMap())!!).jsonObject
        val intervals = root.getValue("proxy-groups").jsonArray.map { it.jsonObject["interval"]?.jsonPrimitive?.content }
        assertEquals(listOf("600", "900", null), intervals)
        assertEquals("600", root.getValue("proxy-providers").jsonObject.getValue("p").jsonObject.getValue("health-check").jsonObject.getValue("interval").jsonPrimitive.content)
    }

    @Test
    fun leavesFineConfigsAlone() {
        assertNull(tuneProfile("proxy-groups:\n  - {name: a, type: url-test, interval: 900}", 600, emptyMap()))
        assertNull(tuneProfile("not: [valid", 600, emptyMap()))
    }

    @Test
    fun nightWindow() {
        assertEquals(0, msUntilNight(LocalTime.of(23, 0)))
        assertEquals(0, msUntilNight(LocalTime.of(6, 59)))
        assertEquals(3_600_000, msUntilNight(LocalTime.of(22, 0)))
        assertEquals(16 * 3_600_000L, msUntilNight(LocalTime.of(7, 0)))
    }
}

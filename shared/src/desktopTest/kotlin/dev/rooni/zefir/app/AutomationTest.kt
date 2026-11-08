package dev.rooni.zefir.app

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutomationTest {
    private val dir = kotlin.io.path.createTempDirectory("zefir-auto").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun cleanup() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private fun setup(settings: String = """{"onboarded":true,"activeProfile":"p1","automation":true,"automationKey":"secret"}"""): Pair<AppController, FakeEngine> {
        seedProfile(dir, settings)
        val engine = FakeEngine(FakeCore())
        val controller = AppController(FakePlatform(dir.absolutePath, engine), scope)
        runBlocking { withTimeout(10_000) { controller.ready.first { it } } }
        return controller to engine
    }

    private fun call(controller: AppController, command: String, key: String? = "secret", vararg args: Pair<String, String>) =
        runBlocking { withTimeout(30_000) { controller.automation.handle(key, command, args.toMap()) } }

    private val JsonObject.ok get() = getValue("ok").jsonPrimitive.boolean
    private val JsonObject.result get() = getValue("result")

    @Test
    fun offByDefault() {
        val (controller, _) = setup("""{"onboarded":true,"activeProfile":"p1"}""")
        val answer = call(controller, "status", key = "")
        assertFalse(answer.ok)
        assertEquals("automation is off", answer.getValue("error").jsonPrimitive.content)
    }

    @Test
    fun wrongKeyRejected() {
        val (controller, engine) = setup()
        assertFalse(call(controller, "connect", key = "nope").ok)
        assertFalse(call(controller, "connect", key = null).ok)
        Thread.sleep(300)
        assertEquals(0, engine.starts.get())
    }

    @Test
    fun connectAndStatus() {
        val (controller, engine) = setup()
        val answer = call(controller, "connect")
        assertTrue(answer.ok)
        assertEquals("on", answer.result.jsonObject.getValue("state").jsonPrimitive.content)
        assertEquals(1, engine.starts.get())
        val status = call(controller, "status").result.jsonObject
        assertEquals("A", status.getValue("server").jsonPrimitive.content)
        assertEquals("p1", status.getValue("profile").jsonPrimitive.content)
        assertEquals("off", call(controller, "disconnect").result.jsonObject.getValue("state").jsonPrimitive.content)
    }

    @Test
    fun listsProfilesAndServers() {
        val (controller, _) = setup()
        val profiles = call(controller, "profiles").result.jsonArray
        assertEquals("Test", profiles.single().jsonObject.getValue("name").jsonPrimitive.content)
        val servers = call(controller, "servers").result.jsonObject
        assertEquals("PROXY", servers.getValue("group").jsonPrimitive.content)
        assertEquals("A", servers.getValue("servers").jsonArray.single().jsonObject.getValue("name").jsonPrimitive.content)
        assertFalse(call(controller, "server.select", args = arrayOf("name" to "Missing")).ok)
    }

    @Test
    fun settingsRoundTrip() {
        val (controller, _) = setup()
        assertTrue(call(controller, "settings.set", args = arrayOf("name" to "blockAds", "value" to "true")).ok)
        assertTrue(controller.settings.value.blockAds)
        // строки можно без кавычек
        assertTrue(call(controller, "settings.set", args = arrayOf("name" to "customDns", "value" to "1.1.1.1")).ok)
        assertEquals("1.1.1.1", controller.settings.value.customDns)
        assertTrue(call(controller, "settings.set", args = arrayOf("name" to "mode", "value" to "Global")).ok)
        assertEquals("true", call(controller, "settings.get", args = arrayOf("name" to "blockAds")).result.jsonPrimitive.content)
        assertFalse(call(controller, "settings.set", args = arrayOf("name" to "mtu", "value" to "abc")).ok)
        assertFalse(call(controller, "settings.set", args = arrayOf("name" to "nope", "value" to "1")).ok)
    }

    @Test
    fun keyStaysPrivate() {
        val (controller, _) = setup()
        val all = call(controller, "settings.get").result.jsonObject
        assertFalse("automationKey" in all)
        assertFalse(call(controller, "settings.set", args = arrayOf("name" to "automation", "value" to "false")).ok)
        assertFalse(call(controller, "settings.set", args = arrayOf("name" to "automationKey", "value" to "x")).ok)
        assertTrue(controller.settings.value.automation)
    }

    @Test
    fun pluginNeedsNoKey() {
        val (controller, _) = setup()
        assertTrue(runBlocking { controller.automation.handlePlugin("status", emptyMap()) }.ok)
    }

    @Test
    fun pluginOffWithoutSwitch() {
        val (controller, _) = setup("""{"onboarded":true,"activeProfile":"p1"}""")
        assertFalse(runBlocking { controller.automation.handlePlugin("status", emptyMap()) }.ok)
    }

    @Test
    fun unknownCommand() {
        val (controller, _) = setup()
        assertFalse(call(controller, "explode").ok)
        assertTrue("status" in call(controller, "help").result.jsonObject)
    }

    @Test
    fun keysAreRandom() {
        val a = Automation.newKey()
        assertEquals(24, a.length)
        assertTrue(a != Automation.newKey())
    }
}

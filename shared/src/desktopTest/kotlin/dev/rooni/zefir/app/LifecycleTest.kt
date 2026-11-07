package dev.rooni.zefir.app

import dev.rooni.zefir.core.CoreApi
import dev.rooni.zefir.core.CoreTransport
import dev.rooni.zefir.model.*
import dev.rooni.zefir.platform.Platform
import dev.rooni.zefir.platform.VpnEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LifecycleTest {
    private val dir = kotlin.io.path.createTempDirectory("zefir-life").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun cleanup() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private class FakeCore : CoreTransport() {
        @Volatile var setupError: String? = null
        @Volatile var dead = false

        override fun send(frame: String) {
            if (dead) throw dev.rooni.zefir.core.CoreException("core stopped")
            val root = Json.parseToJsonElement(frame).jsonObject
            val id = root.getValue("id").jsonPrimitive.content
            val result: JsonElement = when (root.getValue("method").jsonPrimitive.content) {
                "getProfileBase" -> buildJsonObject { putJsonObject("config") {} }
                "getProfileOutbounds" -> buildJsonObject { putJsonArray("proxy-groups") { addJsonObject { put("name", "PROXY") } } }
                "setupConfig" -> JsonPrimitive(setupError.orEmpty())
                "getProxies" -> buildJsonObject {
                    putJsonObject("proxies") {
                        putJsonObject("PROXY") {
                            put("type", "Selector")
                            put("now", "A")
                            putJsonArray("all") { add("A") }
                        }
                        putJsonObject("A") { put("type", "Shadowsocks") }
                    }
                    putJsonArray("all") { add("PROXY") }
                }
                else -> JsonNull
            }
            val reply = buildJsonObject {
                put("id", id)
                put("result", result)
            }.toString().encodeToByteArray()
            Thread { receive(reply) }.start()
        }

        fun crash() {
            dead = true
            failPending("core stopped")
        }
    }

    private class FakeEngine(transport: FakeCore) : VpnEngine {
        val _state = MutableStateFlow<TunnelState>(TunnelState.Off)
        override val state: StateFlow<TunnelState> = _state
        override val core = CoreApi(transport)
        val starts = AtomicInteger()
        @Volatile var permission: CompletableDeferred<Boolean> = CompletableDeferred(true)

        override suspend fun ensurePermission() = permission.await()

        override suspend fun start(options: TunnelOptions) {
            starts.incrementAndGet()
            _state.value = TunnelState.On
        }

        override suspend fun stop() {
            _state.value = TunnelState.Off
        }
    }

    private class FakePlatform(override val dataDir: String, override val engine: VpnEngine) : Platform {
        override val sdkVersion = 0
        override val hasPerAppRouting = false
        override fun detectCountry() = "RU"
        override fun readClipboard(): String? = null
        override fun writeClipboard(text: String, secret: Boolean) {}
        override suspend fun installedApps(): List<InstalledApp> = emptyList()
        override suspend fun pickFile(): String? = null
        override suspend fun scanQr(): String? = null
        override val canScanQr = false
        override val resumed: StateFlow<Int> = MutableStateFlow(0)
        override val foreground: StateFlow<Boolean> = MutableStateFlow(true)
        override val network: StateFlow<NetworkState> = MutableStateFlow(NetworkState())
        override fun deviceInfo() = ""
        override suspend fun shareFile(name: String, text: String) {}
        override suspend fun saveFile(name: String, text: String) = false
    }

    private fun controller(core: FakeCore, engine: FakeEngine): AppController {
        File(dir, "profiles").mkdirs()
        File(dir, "profiles/p1.yaml").writeText("proxies:\n  - {name: A, type: ss, server: a.b, port: 1, cipher: aes-128-gcm, password: x}\n")
        File(dir, "profiles.json").writeText("""[{"id":"p1","name":"Test"}]""")
        File(dir, "settings.json").writeText("""{"onboarded":true,"activeProfile":"p1"}""")
        return AppController(FakePlatform(dir.absolutePath, engine), scope)
    }

    private fun ready(controller: AppController) = runBlocking { withTimeout(10_000) { controller.ready.first { it } } }

    @Test
    fun connectStartsTunnel() {
        val core = FakeCore()
        val engine = FakeEngine(core)
        val controller = controller(core, engine)
        ready(controller)
        controller.connect()
        runBlocking { withTimeout(10_000) { engine.state.first { it == TunnelState.On } } }
        assertEquals(1, engine.starts.get())
    }

    @Test
    fun disconnectDuringStartWins() {
        val core = FakeCore()
        val engine = FakeEngine(core)
        val controller = controller(core, engine)
        ready(controller)
        val gate = CompletableDeferred<Boolean>()
        engine.permission = gate
        controller.connect()
        Thread.sleep(200)
        controller.disconnect()
        gate.complete(true)
        Thread.sleep(500)
        assertEquals(0, engine.starts.get())
        assertEquals(TunnelState.Off, engine.state.value)
    }

    @Test
    fun newerConnectReplacesOlder() {
        val core = FakeCore()
        val engine = FakeEngine(core)
        val controller = controller(core, engine)
        ready(controller)
        val gate = CompletableDeferred<Boolean>()
        engine.permission = gate
        controller.connect()
        Thread.sleep(100)
        engine.permission = CompletableDeferred(true)
        controller.connect()
        gate.complete(true)
        runBlocking { withTimeout(10_000) { engine.state.first { it == TunnelState.On } } }
        Thread.sleep(300)
        assertEquals(1, engine.starts.get())
    }

    @Test
    fun failedReloadDoesNotRestart() {
        val core = FakeCore()
        val engine = FakeEngine(core)
        val controller = controller(core, engine)
        ready(controller)
        controller.connect()
        runBlocking { withTimeout(10_000) { engine.state.first { it == TunnelState.On } } }
        core.setupError = "broken config"
        runBlocking { withTimeout(10_000) { controller.reloadAndRestart() } }
        assertEquals(1, engine.starts.get())
        assertEquals(TunnelState.Off, engine.state.value)
    }

    @Test
    fun coreCrashFailsCallsInsteadOfHanging() {
        val core = FakeCore()
        val engine = FakeEngine(core)
        val controller = controller(core, engine)
        ready(controller)
        core.crash()
        controller.connect()
        Thread.sleep(500)
        assertEquals(0, engine.starts.get())
        assertTrue(engine.state.value != TunnelState.On)
    }
}

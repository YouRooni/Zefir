package dev.rooni.zefir.app

import dev.rooni.zefir.core.CoreApi
import dev.rooni.zefir.core.CoreTransport
import dev.rooni.zefir.model.*
import dev.rooni.zefir.platform.Platform
import dev.rooni.zefir.platform.VpnEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

// общие подделки ядра и платформы для тестов контроллера
internal class FakeCore : CoreTransport() {
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

internal class FakeEngine(transport: FakeCore) : VpnEngine {
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

internal class FakePlatform(override val dataDir: String, override val engine: VpnEngine) : Platform {
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

internal fun seedProfile(dir: File, settings: String = """{"onboarded":true,"activeProfile":"p1"}""") {
    File(dir, "profiles").mkdirs()
    File(dir, "profiles/p1.yaml").writeText("proxies:\n  - {name: A, type: ss, server: a.b, port: 1, cipher: aes-128-gcm, password: x}\n")
    File(dir, "profiles.json").writeText("""[{"id":"p1","name":"Test"}]""")
    File(dir, "settings.json").writeText(settings)
}

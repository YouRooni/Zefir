package dev.rooni.zefir.app

import dev.rooni.zefir.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
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

    private fun controller(core: FakeCore, engine: FakeEngine): AppController {
        seedProfile(dir)
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

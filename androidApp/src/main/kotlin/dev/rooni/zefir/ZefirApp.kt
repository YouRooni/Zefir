package dev.rooni.zefir

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import dev.rooni.zefir.app.AppController
import dev.rooni.zefir.app.UiMessage
import dev.rooni.zefir.app.text
import dev.rooni.zefir.model.TunnelState
import dev.rooni.zefir.res.Res
import dev.rooni.zefir.res.home_failed
import org.jetbrains.compose.resources.getString
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import dev.rooni.zefir.platform.AndroidPlatform
import dev.rooni.zefir.platform.TunnelBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class ZefirApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var platform: AndroidPlatform
    lateinit var controller: AppController

    override fun onCreate() {
        super.onCreate()
        TunnelBridge.startService = {
            startForegroundService(Intent(this, TunnelService::class.java).setAction(TunnelService.ACTION_START))
        }
        TunnelBridge.stopService = {
            startService(Intent(this, TunnelService::class.java).setAction(TunnelService.ACTION_STOP))
        }
        platform = AndroidPlatform(this)
        platform.storeUpdates = storeUpdates(this)
        platform.setWatchService = { on ->
            val intent = Intent(this, WatchService::class.java)
            runCatching { if (on) startForegroundService(intent) else stopService(intent) }
        }
        platform.setBootService = { on ->
            val state = if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            runCatching { packageManager.setComponentEnabledSetting(ComponentName(this, BootReceiver::class.java), state, PackageManager.DONT_KILL_APP) }
        }
        controller = AppController(platform, appScope)
        ReminderWorker.schedule(this)
        appScope.launch {
            controller.profiles.map { list -> list.map { it.id to it.name } }.distinctUntilChanged().collect(platform::updateShortcuts)
        }
        watchErrors()
        watchAutomationEvents()
        appScope.launch {
            kotlinx.coroutines.flow.combine(TunnelBridge.state, TunnelBridge.server, controller.profiles, controller.settings, controller.connectedAt) { state, server, profiles, settings, since ->
                listOf(state, server, since, settings.activeProfile, profiles.firstOrNull { it.id == settings.activeProfile }?.let { it.name to it.expire })
            }.distinctUntilChanged()
                .collect {
                    QuickTile.refresh(this@ZefirApp)
                    ServerWidget.refreshAll(this@ZefirApp)
                }
        }
    }

    // события для Tasker и компании: включился, выключился, сменился сервер или подписка
    private fun watchAutomationEvents() {
        appScope.launch {
            kotlinx.coroutines.flow.combine(controller.tunnel, controller.proxies, controller.settings) { state, proxies, settings ->
                Triple(AutomationReceiver.stateName(state), proxies.resolve(proxies.main?.name), settings.activeProfile)
            }.distinctUntilChanged().drop(1).collect { (state, server, profile) ->
                if (controller.settings.value.automation) AutomationReceiver.sendEvent(this@ZefirApp, state, server, profile)
            }
        }
    }

    private fun watchErrors() {
        fun show(text: String) {
            if (!platform.foreground.value) Toast.makeText(this, text, Toast.LENGTH_LONG).show()
        }
        appScope.launch {
            controller.message.collect { message ->
                if (message != UiMessage.Updated) show(message.text(controller.settings.value.detailedErrors))
            }
        }
        appScope.launch {
            TunnelBridge.state.collect { state ->
                if (state is TunnelState.Failed) show(getString(Res.string.home_failed))
            }
        }
    }
}

package dev.rooni.zefir

import android.content.ComponentName
import android.content.Context
import android.net.VpnService
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.rooni.zefir.model.TunnelState
import dev.rooni.zefir.platform.TunnelBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class QuickTile : TileService() {
    private val scope = CoroutineScope(Dispatchers.Main.immediate)
    private var watching: Job? = null

    override fun onStartListening() {
        watching?.cancel()
        val controller = (application as ZefirApp).controller
        watching = scope.launch {
            launch { TunnelBridge.state.collect { render(it) } }
            launch { TunnelBridge.server.collect { render(TunnelBridge.state.value) } }

            launch { controller.message.collect { render(TunnelBridge.state.value) } }
        }
    }

    override fun onStopListening() {
        watching?.cancel()
        watching = null
    }

    override fun onClick() {
        val controller = (application as ZefirApp).controller

        if (VpnService.prepare(this) != null) {
            val intent = android.content.Intent(this, MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pending = android.app.PendingIntent.getActivity(this, 0, intent, android.app.PendingIntent.FLAG_IMMUTABLE)
                startActivityAndCollapse(pending)
            } else {
                @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
                startActivityAndCollapse(intent)
            }
            return
        }

        val state = TunnelBridge.state.value
        val turningOn = state != TunnelState.On && state != TunnelState.Starting
        paint(turningOn, turningOn)
        controller.toggle()
    }

    private fun render(state: TunnelState) {
        paint(state == TunnelState.On || state == TunnelState.Starting, state == TunnelState.Starting)
    }

    private fun paint(active: Boolean, connecting: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                connecting -> getString(R.string.tunnel_connecting)
                active -> TunnelBridge.server.value?.let(::tileServer)
                else -> null
            }
        }
        tile.updateTile()
    }

    private fun tileServer(raw: String): String {
        val name = dev.rooni.zefir.ui.screens.parseServerName(raw)
        return listOfNotNull(name.flag, name.label).joinToString(" ")
    }

    companion object {
        fun refresh(context: Context) {
            requestListeningState(context, ComponentName(context, QuickTile::class.java))
        }
    }
}

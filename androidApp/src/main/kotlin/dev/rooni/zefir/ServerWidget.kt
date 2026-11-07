package dev.rooni.zefir

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import dev.rooni.zefir.model.TunnelState
import dev.rooni.zefir.platform.TunnelBridge
import dev.rooni.zefir.ui.screens.parseServerName

class ServerWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(context, manager, it) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        render(context, manager, id)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_TOGGLE) {
            val app = context.applicationContext as ZefirApp
            if (VpnService.prepare(context) != null) {
                context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } else {
                app.controller.toggle()
            }
            return
        }
        super.onReceive(context, intent)
    }

    private enum class Size { Small, Wide, Large }

    private class Info(val on: Boolean, val status: String, val server: String, val profile: String?, val since: Long?)

    private fun info(context: Context): Info {
        val app = context.applicationContext as ZefirApp
        val controller = app.controller
        val state = TunnelBridge.state.value
        val on = state == TunnelState.On || state == TunnelState.Starting
        val status = context.getString(
            when (state) {
                TunnelState.On -> R.string.widget_on
                TunnelState.Starting -> R.string.tunnel_connecting
                else -> R.string.widget_off
            },
        )
        val profile = controller.activeProfile
        val raw = TunnelBridge.server.value
        val server = raw?.let { name ->
            when (name) {
                "DIRECT" -> null
                else -> parseServerName(name).let { listOfNotNull(it.flag, it.label).joinToString(" ") }
            }
        } ?: profile?.name ?: context.getString(R.string.widget_no_profile)
        val note = profile?.let { sub ->
            val days = if (sub.expire > 0) ((sub.expire - System.currentTimeMillis() / 1000) / 86_400).coerceAtLeast(0).toInt() else null
            listOfNotNull(sub.name.takeIf { it != server }, days?.let { context.getString(R.string.widget_days, it) }).joinToString(" · ").ifEmpty { null }
        }
        val since = controller.connectedAt.value.takeIf { state == TunnelState.On }
        return Info(on, status, server, note, since)
    }

    private fun views(context: Context, size: Size, info: Info): RemoteViews {
        val layout = when (size) {
            Size.Small -> if (info.on) R.layout.widget_small_on else R.layout.widget_small_off
            Size.Wide -> if (info.on) R.layout.widget_wide_on else R.layout.widget_wide_off
            Size.Large -> if (info.on) R.layout.widget_large_on else R.layout.widget_large_off
        }
        val views = RemoteViews(context.packageName, layout)
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val toggle = PendingIntent.getBroadcast(
            context, 1, Intent(context, ServerWidget::class.java).setAction(ACTION_TOGGLE), PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.widget_root, if (size == Size.Large) open else toggle)
        if (size == Size.Small) return views
        views.setOnClickPendingIntent(R.id.widget_toggle, toggle)
        views.setTextViewText(R.id.widget_status, info.status)
        views.setTextViewText(R.id.widget_server, info.server)
        if (size == Size.Large) {
            views.setTextViewText(R.id.widget_action, context.getString(if (info.on) R.string.tunnel_disconnect else R.string.widget_connect))
            views.setTextViewText(R.id.widget_profile, info.profile.orEmpty())
            views.setViewVisibility(R.id.widget_profile, if (info.profile == null) View.GONE else View.VISIBLE)
            if (info.since != null) {
                views.setChronometer(R.id.widget_timer, SystemClock.elapsedRealtime() - (System.currentTimeMillis() - info.since), null, true)
                views.setViewVisibility(R.id.widget_timer, View.VISIBLE)
            } else {
                views.setChronometer(R.id.widget_timer, SystemClock.elapsedRealtime(), null, false)
                views.setViewVisibility(R.id.widget_timer, View.GONE)
            }
        }
        return views
    }

    private fun sizeOf(options: Bundle): Size {
        val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)
        val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
        return when {
            width in 1 until WIDE_WIDTH -> Size.Small
            height >= LARGE_HEIGHT && width >= LARGE_WIDTH -> Size.Large
            else -> Size.Wide
        }
    }

    private fun render(context: Context, manager: AppWidgetManager, id: Int) {
        val info = info(context)
        val views = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            RemoteViews(
                mapOf(
                    SizeF(40f, 40f) to views(context, Size.Small, info),
                    SizeF(WIDE_WIDTH.toFloat(), 40f) to views(context, Size.Wide, info),
                    SizeF(LARGE_WIDTH.toFloat(), LARGE_HEIGHT.toFloat()) to views(context, Size.Large, info),
                ),
            )
        } else {
            views(context, sizeOf(manager.getAppWidgetOptions(id)), info)
        }
        manager.updateAppWidget(id, views)
    }

    companion object {
        private const val ACTION_TOGGLE = "dev.rooni.zefir.WIDGET_TOGGLE"
        private const val WIDE_WIDTH = 100
        private const val LARGE_WIDTH = 150
        private const val LARGE_HEIGHT = 130

        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, ServerWidget::class.java))
            if (ids.isEmpty()) return
            val provider = ServerWidget()
            ids.forEach { provider.render(context, manager, it) }
        }
    }
}

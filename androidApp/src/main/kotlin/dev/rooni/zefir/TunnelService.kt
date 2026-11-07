package dev.rooni.zefir

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.util.Log
import dev.rooni.zefir.model.SplitMode
import dev.rooni.zefir.model.Traffic
import dev.rooni.zefir.model.TunnelOptions
import dev.rooni.zefir.model.TunnelState
import dev.rooni.zefir.platform.AndroidCore
import dev.rooni.zefir.platform.TunnelBridge
import dev.rooni.vpncore.mobile.TunHandler
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.first

class TunnelService : VpnService() {
    private val lock = Any()
    private var running = false
    private var generation = 0L
    @Volatile private var paused = false
    private var inForeground = false
    private var shownNotification: List<Any?>? = null
    private var lastSpeed: Traffic? = null
    private val packages = ConcurrentHashMap<Int, String>()
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }

    override fun onCreate() {
        super.onCreate()
        TunnelBridge.serviceAlive.value = true
        getSystemService(NotificationManager::class.java).deleteNotificationChannel(CHANNEL_QUIET)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            bumpGeneration()
            shutdown()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_PAUSE) {
            bumpGeneration()
            pause()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_RESUME) {
            (application as ZefirApp).controller.connect(auto = true)
            return START_NOT_STICKY
        }
        paused = false
        startForegroundCompat(buildNotification(getString(R.string.tunnel_connecting), null))

        val controller = (application as ZefirApp).controller
        val gen = bumpGeneration()
        Thread({
            kotlinx.coroutines.runBlocking { controller.ready.first { it } }
            if (isCurrent(gen)) launch(gen, TunnelBridge.options ?: controller.tunnelOptions())
        }, "tunnel-start").start()
        return START_STICKY
    }

    private fun bumpGeneration(): Long = synchronized(lock) { ++generation }

    private fun isCurrent(gen: Long): Boolean = synchronized(lock) { gen == generation }

    private fun launch(gen: Long, options: TunnelOptions) {
        try {
            val fd = Builder().apply {
                addAddress(IPV4_ADDRESS, 30)
                addRoute("0.0.0.0", 0)
                addDnsServer(DNS)
                if (options.ipv6) {
                    runCatching {
                        addAddress(IPV6_ADDRESS, 126)
                        addRoute("::", 0)
                        addDnsServer(DNS6)
                    }
                }
                setMtu(options.mtu)
                setSession(getString(R.string.app_name))
                setBlocking(false)
                if (options.allowBypass) allowBypass()
                when (options.splitMode) {
                    SplitMode.Off -> {}
                    SplitMode.Exclude -> (options.excluded - packageName).forEach { skipMissing { addDisallowedApplication(it) } }
                    SplitMode.Only -> (options.only + packageName).forEach { skipMissing { addAllowedApplication(it) } }
                }
            }.establish()?.detachFd() ?: error("establish rejected")

            val started = synchronized(lock) {
                if (gen != generation) {
                    runCatching { android.os.ParcelFileDescriptor.adoptFd(fd).close() }
                    return
                }
                if (running) AndroidCore.stopTun()
                running = false
                val ok = AndroidCore.startTun(
                    fd,
                    handler,
                    options.stack,
                    if (options.ipv6) "$IPV4_ADDRESS/30,$IPV6_ADDRESS/126" else "$IPV4_ADDRESS/30",
                    when {
                        options.dnsHijackAll -> "0.0.0.0"
                        options.ipv6 -> "$DNS,$DNS6"
                        else -> DNS
                    },
                    """{"mtu":${options.mtu},"congestion-controller":""}""",
                )
                running = ok
                ok
            }
            check(started) { "core rejected tun" }
            TunnelBridge.notify = ::updateNotification
            TunnelBridge.protectSocket = { protect(it) }
            TunnelBridge.protectDatagram = { protect(it) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) TunnelBridge.lockdown = isLockdownEnabled
            TunnelBridge.state.value = TunnelState.On
            updateNotification(null)
            QuickTile.refresh(this)
        } catch (e: Exception) {
            Log.e(TAG, "tunnel start failed", e)
            if (!isCurrent(gen)) return
            TunnelBridge.state.value = TunnelState.Failed(e.message ?: "start failed")
            shutdownQuietly()
        }
    }

    private fun skipMissing(block: () -> Unit) {
        try {
            block()
        } catch (_: PackageManager.NameNotFoundException) {
        }
    }

    private val handler = object : TunHandler {
        override fun protect(fd: Int): Boolean = this@TunnelService.protect(fd)

        override fun resolveUid(protocol: Int, source: String, target: String): Int {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return -1
            return runCatching {
                connectivity.getConnectionOwnerUid(protocol, address(source), address(target))
            }.getOrDefault(-1)
        }

        override fun resolvePackage(uid: Int): String = packages.getOrPut(uid) {
            packageManager.getPackagesForUid(uid)?.firstOrNull().orEmpty()
        }
    }

    private fun address(value: String): InetSocketAddress {
        val uri = URI("tcp://$value")
        return InetSocketAddress(InetAddress.getByName(uri.host), uri.port)
    }

    private fun pause() {
        synchronized(lock) {
            if (!running) return
            AndroidCore.stopTun()
            running = false
        }
        paused = true
        shownNotification = null
        TunnelBridge.notify = null
        TunnelBridge.protectSocket = null
        TunnelBridge.protectDatagram = null
        TunnelBridge.state.value = TunnelState.Off
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(getString(R.string.tunnel_paused), null))
        QuickTile.refresh(this)
    }

    private fun shutdown() {
        shutdownQuietly()
        TunnelBridge.state.value = TunnelState.Off
    }

    private fun shutdownQuietly() {
        synchronized(lock) {
            if (running) {
                AndroidCore.stopTun()
                running = false
            }
        }
        TunnelBridge.notify = null
        TunnelBridge.protectSocket = null
        TunnelBridge.protectDatagram = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        inForeground = false
        shownNotification = null
        stopSelf()
        QuickTile.refresh(this)
    }

    override fun onRevoke() {
        bumpGeneration()
        shutdown()
    }

    override fun onDestroy() {
        synchronized(lock) {
            generation++
            if (running) {
                AndroidCore.stopTun()
                running = false
                TunnelBridge.state.value = TunnelState.Off
            }
        }
        TunnelBridge.serviceAlive.value = false
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        inForeground = true
        shownNotification = null
    }

    private fun updateNotification(speed: Traffic?) {
        val options = TunnelBridge.options
        if (options?.notifEnabled == false) {
            if (inForeground) stopForeground(STOP_FOREGROUND_REMOVE)
            inForeground = false
            shownNotification = null
            return
        }
        if (speed != null) lastSpeed = speed
        val shownSpeed = if (options?.notifLiveSpeed == true) speed ?: lastSpeed else null
        val server = TunnelBridge.server.value?.takeIf { options?.notifShowServer != false }?.trim()
        val text = listOfNotNull(server, shownSpeed?.let { "↓ ${human(it.down)}/s  ↑ ${human(it.up)}/s" }).joinToString("  ·  ").ifEmpty { null }
        val content = listOf(text, options?.notifPauseButton, options?.notifStopButton)
        if (content == shownNotification) return
        val notification = buildNotification(getString(R.string.tunnel_on), text)
        val manager = getSystemService(NotificationManager::class.java)
        if (inForeground) {
            manager.notify(NOTIFICATION_ID, notification)
        } else {
            runCatching { startForegroundCompat(notification) }.onFailure { manager.notify(NOTIFICATION_ID, notification) }
        }
        shownNotification = content
    }

    private fun human(bytes: Long): String = when {
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }

    private fun buildNotification(title: String, text: String?): Notification {
        val options = TunnelBridge.options
        val manager = getSystemService(NotificationManager::class.java)
        val channel = CHANNEL
        if (manager.getNotificationChannel(channel) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    channel,
                    getString(R.string.tunnel_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { setShowBadge(false) },
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        fun action(code: Int, name: String, label: Int) {
            val intent = PendingIntent.getService(this, code, Intent(this, TunnelService::class.java).setAction(name), PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(Notification.Action.Builder(null, getString(label), intent).build())
        }
        if (paused) {
            action(3, ACTION_RESUME, R.string.tunnel_resume)
            action(1, ACTION_STOP, R.string.tunnel_disconnect)
        } else {
            if (options?.notifPauseButton == true) action(2, ACTION_PAUSE, R.string.tunnel_pause)
            if (options?.notifStopButton == true) action(1, ACTION_STOP, R.string.tunnel_disconnect)
        }
        return builder.build()
    }

    companion object {
        const val ACTION_START = "dev.rooni.zefir.START"
        const val ACTION_STOP = "dev.rooni.zefir.STOP"
        const val ACTION_PAUSE = "dev.rooni.zefir.PAUSE"
        const val ACTION_RESUME = "dev.rooni.zefir.RESUME"
        private const val TAG = "TunnelService"
        private const val CHANNEL = "tunnel"
        private const val CHANNEL_QUIET = "tunnel_quiet"
        private const val NOTIFICATION_ID = 7
        private const val IPV4_ADDRESS = "198.18.0.1"
        private const val IPV6_ADDRESS = "fdfe:dcba:9876::1"
        private const val DNS = "198.18.0.2"
        private const val DNS6 = "fdfe:dcba:9876::2"
    }
}

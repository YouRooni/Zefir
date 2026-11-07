package dev.rooni.zefir.platform

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import dev.rooni.zefir.core.CoreApi
import dev.rooni.zefir.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

expect object Fs {
    fun readText(path: String): String
    fun writeText(path: String, text: String)
    fun appendText(path: String, text: String)
    fun delete(path: String)
    fun exists(path: String): Boolean
    fun mkdirs(path: String)
}

expect fun nowMillis(): Long

interface VpnEngine {
    val state: StateFlow<TunnelState>
    val core: CoreApi

    suspend fun ensurePermission(): Boolean

    fun hasPermission(): Boolean = true
    suspend fun start(options: TunnelOptions)
    suspend fun stop()

    suspend fun awaitStopped() {}
    fun updateNotification(speed: Traffic?) {}
    fun applyOptions(options: TunnelOptions) {}
    fun updateServer(name: String?) {}
}

class DirectRoute(val key: Any, val sockets: javax.net.SocketFactory, val lookup: (String) -> List<java.net.InetAddress>)

enum class WifiAccess { Ok, NoLocation, NoBackground, LocationOff }

enum class Haptic { Tick, Toggle, Press, Connected, Disconnected, Failed }

enum class IdleEvent { ScreenOff, Unlocked, Alarm }

interface Platform {
    val dataDir: String

    val deviceHeaders: Map<String, String> get() = emptyMap()
    val sdkVersion: Int
    val hasPerAppRouting: Boolean
    val engine: VpnEngine
    fun detectCountry(): String
    fun readClipboard(): String?

    fun writeClipboard(text: String, secret: Boolean)
    suspend fun installedApps(): List<InstalledApp>
    suspend fun pickFile(): String?
    suspend fun scanQr(): String?
    val canScanQr: Boolean
    fun requestNotifications() {}
    fun haptic(event: Haptic) {}
    fun setHaptics(on: Boolean) {}
    val hasHaptics: Boolean get() = true
    val hasWifiRules: Boolean get() = true

    fun directRoute(): DirectRoute? = null
    val vpnProxy: java.net.Proxy? get() = null
    fun protectSocket(socket: java.net.Socket) {}
    fun protectSocket(socket: java.net.DatagramSocket) {}
    val hasIdleOff: Boolean get() = false
    val isLocked: Boolean get() = false
    val isInteractive: Boolean get() = true
    val idleEvents: Flow<IdleEvent> get() = kotlinx.coroutines.flow.emptyFlow()
    fun armIdleAlarm(delayMillis: Long) {}
    fun cancelIdleAlarm() {}
    val systemColorsAvailable: Boolean get() = false

    fun setSystemBarsDark(dark: Boolean) {}

    fun setWindowColors(caption: androidx.compose.ui.graphics.Color, text: androidx.compose.ui.graphics.Color, icon: androidx.compose.ui.graphics.Color) {}
    val hasNotificationSettings: Boolean get() = true

    val tunInConfig: Boolean get() = false

    val resumed: StateFlow<Int>

    val foreground: StateFlow<Boolean>
    val hasBatterySettings: Boolean get() = false
    val isBatteryUnrestricted: Boolean get() = true
    val network: StateFlow<NetworkState>
    suspend fun requestWifiAccess() {}
    fun setWatching(on: Boolean) {}

    fun setBootWatching(on: Boolean) {}
    fun setSsidWatching(on: Boolean) {}
    fun wifiAccess(): WifiAccess = WifiAccess.Ok
    suspend fun fixWifiAccess(access: WifiAccess) {}
    fun deviceInfo(): String
    suspend fun shareFile(name: String, text: String)
    suspend fun shareText(text: String) = writeClipboard(text, secret = true)
    suspend fun saveFile(name: String, text: String): Boolean
    fun openBatterySettings() {}

    val isTv: Boolean get() = false

    val isDesktop: Boolean get() = false
    val hasDesktopTun: Boolean get() = false
    val hasCustomFrame: Boolean get() = false
    val desktopOsName: String get() = ""
    fun applyDesktopSettings(tun: Boolean) {}
    fun takeTunDeclined(): Boolean = false
    val hasVpnSettings: Boolean get() = false
    val vpnLockdown: Boolean? get() = null
    fun openVpnSettings() {}
    fun copyGeoData() {}

    @Composable
    fun systemColorScheme(dark: Boolean): ColorScheme? = null

    @Composable
    fun ownIcon(): androidx.compose.ui.graphics.painter.Painter? = null

    val canPinShortcuts: Boolean get() = false
    fun pinProfileShortcut(id: String, name: String): Boolean = false
    fun updateShortcuts(profiles: List<Pair<String, String>>) {}

    fun cachedAppIcon(pkg: String): androidx.compose.ui.graphics.ImageBitmap? = null
    suspend fun loadAppIcon(pkg: String): androidx.compose.ui.graphics.ImageBitmap? = null
}

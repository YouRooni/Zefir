package dev.rooni.zefir.platform

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.telephony.TelephonyManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.tasks.await
import dev.rooni.zefir.core.CoreApi
import dev.rooni.zefir.model.InstalledApp
import dev.rooni.zefir.model.NetworkState
import dev.rooni.zefir.model.Traffic
import dev.rooni.zefir.model.TunnelOptions
import dev.rooni.zefir.model.TunnelState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

object TunnelBridge {
    val state = MutableStateFlow<TunnelState>(TunnelState.Off)
    val serviceAlive = MutableStateFlow(false)
    @Volatile var lockdown: Boolean? = null
    var options: TunnelOptions? = null
    var startService: ((TunnelOptions) -> Unit)? = null
    var stopService: (() -> Unit)? = null
    var notify: ((Traffic?) -> Unit)? = null
    val server = MutableStateFlow<String?>(null)
    var protectSocket: ((java.net.Socket) -> Unit)? = null
    var protectDatagram: ((java.net.DatagramSocket) -> Unit)? = null
    val idleEvents = kotlinx.coroutines.flow.MutableSharedFlow<IdleEvent>(extraBufferCapacity = 8)
}

const val SHORTCUT_ACTION = "dev.rooni.zefir.PROFILE"

class IdleAlarmReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        TunnelBridge.idleEvents.tryEmit(IdleEvent.Alarm)
    }
}

class AndroidPlatform(
    private val context: Context,
) : Platform {
    private val _resumed = MutableStateFlow(0)
    override val resumed: StateFlow<Int> = _resumed

    private val _foreground = MutableStateFlow(false)
    override val foreground: StateFlow<Boolean> = _foreground

    fun onResume() {
        NetworkObserver.refresh()
        _resumed.value += 1
        _foreground.value = true
    }

    fun onPause() {
        _foreground.value = false
    }

    var activity: ComponentActivity? = null
    private var pendingVpn: CompletableDeferred<Boolean>? = null
    private var pendingFile: CompletableDeferred<String?>? = null

    private val icons = ConcurrentHashMap<String, ImageBitmap>()
    private val iconDispatcher = Dispatchers.IO.limitedParallelism(2)

    override val dataDir: String = context.filesDir.absolutePath

    @get:SuppressLint("HardwareIds")
    override val deviceHeaders: Map<String, String> by lazy {
        val raw = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest("zefir:$raw".encodeToByteArray())
        mapOf(
            "x-hwid" to hash.take(16).joinToString("") { "%02x".format(it) },
            "x-device-os" to "Android",
            "x-ver-os" to Build.VERSION.RELEASE,
            "x-device-model" to Build.MODEL,
        )
    }
    override val sdkVersion = Build.VERSION.SDK_INT
    override val hasPerAppRouting = true

    override val engine = object : VpnEngine {
        override val state: StateFlow<TunnelState> = TunnelBridge.state
        override val core: CoreApi = AndroidCore.api

        override fun hasPermission(): Boolean = VpnService.prepare(context) == null

        override suspend fun ensurePermission(): Boolean {
            val intent = VpnService.prepare(context) ?: return true
            if (activity == null) return false
            val deferred = CompletableDeferred<Boolean>()
            pendingVpn = deferred
            withContext(Dispatchers.Main) { vpnLauncher?.launch(intent) ?: deferred.complete(false) }
            return deferred.await()
        }

        override suspend fun start(options: TunnelOptions) {
            TunnelBridge.state.value = TunnelState.Starting
            TunnelBridge.options = options

            try {
                TunnelBridge.startService?.invoke(options)
            } catch (e: Exception) {
                TunnelBridge.state.value = TunnelState.Failed(e.message ?: "start failed")
            }
        }

        override suspend fun stop() {
            TunnelBridge.state.value = TunnelState.Stopping
            TunnelBridge.stopService?.invoke()
        }

        override suspend fun awaitStopped() {
            withTimeoutOrNull(5000) { TunnelBridge.serviceAlive.first { !it } }

            delay(300)
        }

        override fun updateNotification(speed: Traffic?) {
            TunnelBridge.notify?.invoke(speed)
        }

        override fun applyOptions(options: TunnelOptions) {
            TunnelBridge.options = options
            TunnelBridge.notify?.invoke(null)
        }

        override fun updateServer(name: String?) {
            if (TunnelBridge.server.value == name) return
            TunnelBridge.server.value = name
            TunnelBridge.notify?.invoke(null)
        }
    }

    private var vpnLauncher: androidx.activity.result.ActivityResultLauncher<Intent>? = null
    private var fileLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>? = null
    private var notifyLauncher: androidx.activity.result.ActivityResultLauncher<String>? = null

    fun attach(activity: ComponentActivity) {
        this.activity = activity
        vpnLauncher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            pendingVpn?.complete(it.resultCode == Activity.RESULT_OK)
            pendingVpn = null
        }
        fileLauncher = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val text = uri?.let {
                runCatching { context.contentResolver.openInputStream(it)?.use { s -> s.readBytes().decodeToString() } }.getOrNull()
            }
            pendingFile?.complete(text)
            pendingFile = null
        }
        notifyLauncher = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
        qrLauncher = activity.registerForActivityResult(com.journeyapps.barcodescanner.ScanContract()) { result ->
            pendingQr?.complete(result.contents)
            pendingQr = null
        }
        locationLauncher = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            pendingLocation?.complete(it)
            pendingLocation = null
        }
        saveLauncher = activity.registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            val (text, deferred) = pendingSave ?: return@registerForActivityResult
            pendingSave = null
            val ok = uri != null && runCatching {
                context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.encodeToByteArray()) }
            }.isSuccess
            deferred.complete(ok)
        }
    }

    init {
        NetworkObserver.start(context) { _network.value = it }
        val screen = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                TunnelBridge.idleEvents.tryEmit(if (intent.action == Intent.ACTION_SCREEN_OFF) IdleEvent.ScreenOff else IdleEvent.Unlocked)
            }
        }
        val filter = android.content.IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_USER_PRESENT) }
        androidx.core.content.ContextCompat.registerReceiver(context, screen, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override val systemColorsAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    override val hasIdleOff = true
    override val isInteractive: Boolean get() = context.getSystemService(PowerManager::class.java)?.isInteractive != false
    override val isLocked: Boolean get() = context.getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true
    override val idleEvents get() = TunnelBridge.idleEvents

    private val idleAlarm by lazy {
        android.app.PendingIntent.getBroadcast(
            context, 0, Intent(context, IdleAlarmReceiver::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    override fun armIdleAlarm(delayMillis: Long) {
        context.getSystemService(android.app.AlarmManager::class.java)?.setAndAllowWhileIdle(
            android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
            android.os.SystemClock.elapsedRealtime() + delayMillis,
            idleAlarm,
        )
    }

    override fun cancelIdleAlarm() {
        context.getSystemService(android.app.AlarmManager::class.java)?.cancel(idleAlarm)
    }

    @Volatile private var hapticsOn = true

    override fun setHaptics(on: Boolean) {
        hapticsOn = on
    }

    override fun directRoute(): DirectRoute? {
        if (TunnelBridge.state.value != TunnelState.On) return null
        val manager = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return null
        @Suppress("DEPRECATION")
        val network = manager.allNetworks.firstOrNull { candidate ->
            manager.getNetworkCapabilities(candidate)?.let {
                it.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) && !it.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
            } == true
        } ?: return null
        return DirectRoute(network, network.socketFactory) { host -> network.getAllByName(host).toList() }
    }

    override fun protectSocket(socket: java.net.Socket) {
        TunnelBridge.protectSocket?.invoke(socket)
    }

    override fun protectSocket(socket: java.net.DatagramSocket) {
        TunnelBridge.protectDatagram?.invoke(socket)
    }

    override fun haptic(event: Haptic) {
        if (!hapticsOn || !_foreground.value) return
        val vibrator = context.getSystemService(Vibrator::class.java) ?: return
        if (!vibrator.hasVibrator()) return
        val effect = when (event) {
            Haptic.Tick -> predefined(VibrationEffect.EFFECT_TICK, 10)
            Haptic.Toggle -> predefined(VibrationEffect.EFFECT_CLICK, 20)
            Haptic.Press -> composed(VibrationEffect.Composition.PRIMITIVE_CLICK to 0.6f) ?: predefined(VibrationEffect.EFFECT_HEAVY_CLICK, 40)

            Haptic.Connected -> composed(
                VibrationEffect.Composition.PRIMITIVE_QUICK_RISE to 0.7f,
                VibrationEffect.Composition.PRIMITIVE_THUD to 1f,
                VibrationEffect.Composition.PRIMITIVE_TICK to 0.5f,
            ) ?: predefined(VibrationEffect.EFFECT_HEAVY_CLICK, 40)
            Haptic.Disconnected -> composed(
                VibrationEffect.Composition.PRIMITIVE_QUICK_FALL to 0.6f,
                VibrationEffect.Composition.PRIMITIVE_TICK to 0.4f,
            ) ?: predefined(VibrationEffect.EFFECT_CLICK, 20)
            Haptic.Failed -> predefined(VibrationEffect.EFFECT_DOUBLE_CLICK, 60)
        }
        vibrator.vibrate(effect)
    }

    private fun predefined(effect: Int, fallbackMs: Long): VibrationEffect =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) VibrationEffect.createPredefined(effect)
        else VibrationEffect.createOneShot(fallbackMs, VibrationEffect.DEFAULT_AMPLITUDE)

    private fun composed(vararg parts: Pair<Int, Float>): VibrationEffect? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val vibrator = context.getSystemService(Vibrator::class.java) ?: return null
        if (!vibrator.areAllPrimitivesSupported(*parts.map { it.first }.toIntArray())) return null
        val composition = VibrationEffect.startComposition()
        parts.forEach { (primitive, scale) -> composition.addPrimitive(primitive, scale) }
        return composition.compose()
    }

    override fun setSystemBarsDark(dark: Boolean) {
        val window = activity?.window ?: return
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }

    override fun detectCountry(): String {
        val telephony = context.getSystemService(TelephonyManager::class.java)
        val sim = runCatching { telephony?.simCountryIso }.getOrNull().orEmpty()
        return sim.ifEmpty { Locale.getDefault().country }.uppercase()
    }

    override fun readClipboard(): String? {
        val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(context)?.toString()
    }

    override fun writeClipboard(text: String, secret: Boolean) {
        val clip = android.content.ClipData.newPlainText("Zefir", text)
        if (secret && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = android.os.PersistableBundle().apply { putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true) }
        }
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
    }

    @SuppressLint("QueryPermissionsNeeded")
    override suspend fun installedApps(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        pm.getInstalledApplications(0)
            .filter { it.packageName != context.packageName }
            .map {
                InstalledApp(
                    pkg = it.packageName,
                    label = it.loadLabel(pm).toString(),
                    system = it.flags and ApplicationInfo.FLAG_SYSTEM != 0 && pm.getLaunchIntentForPackage(it.packageName) == null,
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    override suspend fun pickFile(): String? {
        val deferred = CompletableDeferred<String?>()
        pendingFile = deferred
        withContext(Dispatchers.Main) { fileLauncher?.launch(arrayOf("*/*")) ?: deferred.complete(null) }
        return deferred.await()
    }

    private val shortcuts by lazy { context.getSystemService(android.content.pm.ShortcutManager::class.java) }

    override val canPinShortcuts: Boolean get() = runCatching { shortcuts.isRequestPinShortcutSupported }.getOrDefault(false)

    private fun profileShortcut(id: String, name: String) = android.content.pm.ShortcutInfo.Builder(context, "profile-$id")
        .setShortLabel(name.take(24).ifBlank { "Zefir" })
        .setLongLabel(name.take(48).ifBlank { "Zefir" })
        .setIcon(android.graphics.drawable.Icon.createWithResource(context, context.applicationInfo.icon))
        .setIntent(
            Intent(SHORTCUT_ACTION).setClassName(context, "dev.rooni.zefir.MainActivity")
                .putExtra("profile", id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        .build()

    override fun pinProfileShortcut(id: String, name: String): Boolean =
        runCatching { shortcuts.requestPinShortcut(profileShortcut(id, name), null) }.getOrDefault(false)

    override fun updateShortcuts(profiles: List<Pair<String, String>>) {
        runCatching {
            val limit = shortcuts.maxShortcutCountPerActivity.coerceAtMost(4)
            shortcuts.dynamicShortcuts = profiles.take(limit).map { (id, name) -> profileShortcut(id, name) }
            val alive = profiles.associate { (id, name) -> "profile-$id" to name }
            val pinned = shortcuts.pinnedShortcuts
            pinned.filter { it.id in alive }.map { profileShortcut(it.id.removePrefix("profile-"), alive.getValue(it.id)) }
                .takeIf { it.isNotEmpty() }?.let { shortcuts.updateShortcuts(it) }
            pinned.map { it.id }.filter { it.startsWith("profile-") && it !in alive }
                .takeIf { it.isNotEmpty() }?.let { shortcuts.disableShortcuts(it) }
        }
    }

    override val canScanQr = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

    private fun hasGms() = runCatching {
        com.google.android.gms.common.GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == com.google.android.gms.common.ConnectionResult.SUCCESS
    }.getOrDefault(false)

    override suspend fun scanQr(): String? {
        val current = activity ?: return null
        if (hasGms()) {
            try {
                return GmsBarcodeScanning.getClient(current).startScan().await().rawValue
            } catch (e: kotlinx.coroutines.CancellationException) {
                return null
            } catch (_: Exception) {
            }
        }
        val launcher = qrLauncher ?: return null
        val deferred = CompletableDeferred<String?>()
        pendingQr = deferred
        val options = com.journeyapps.barcodescanner.ScanOptions()
            .setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE)
            .setBeepEnabled(false)
            .setOrientationLocked(false)
            .setPrompt("")
        withContext(Dispatchers.Main) { launcher.launch(options) }
        return deferred.await()
    }

    private var qrLauncher: androidx.activity.result.ActivityResultLauncher<com.journeyapps.barcodescanner.ScanOptions>? = null
    private var pendingQr: CompletableDeferred<String?>? = null

    override val isTv: Boolean = context.getSystemService(android.app.UiModeManager::class.java)?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    override val hasHaptics: Boolean get() = !isTv
    override val hasVpnSettings = true
    override val vpnLockdown: Boolean? get() = TunnelBridge.lockdown

    override fun openVpnSettings() {
        val current = activity ?: return
        runCatching { current.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }
            .onFailure { runCatching { current.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) } }
    }

    private val _network = MutableStateFlow(NetworkState())
    override val network: StateFlow<NetworkState> = _network

    var setWatchService: ((Boolean) -> Unit)? = null
    private var pendingLocation: CompletableDeferred<Boolean>? = null
    private var locationLauncher: androidx.activity.result.ActivityResultLauncher<String>? = null
    private var pendingSave: Pair<String, CompletableDeferred<Boolean>>? = null
    private var saveLauncher: androidx.activity.result.ActivityResultLauncher<String>? = null

    override fun setWatching(on: Boolean) {
        setWatchService?.invoke(on)
    }

    var setBootService: ((Boolean) -> Unit)? = null

    override fun setBootWatching(on: Boolean) {
        setBootService?.invoke(on)
    }

    override fun setSsidWatching(on: Boolean) = NetworkObserver.setSsidWatching(on)

    private fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    override fun wifiAccess(): WifiAccess = when {
        !granted(Manifest.permission.ACCESS_FINE_LOCATION) -> WifiAccess.NoLocation
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION) -> WifiAccess.NoBackground
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && context.getSystemService(android.location.LocationManager::class.java)?.isLocationEnabled == false -> WifiAccess.LocationOff
        else -> WifiAccess.Ok
    }

    override suspend fun fixWifiAccess(access: WifiAccess) {
        when (access) {
            WifiAccess.Ok -> Unit
            WifiAccess.NoLocation -> requestWifiAccess()
            WifiAccess.NoBackground -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
                val deferred = CompletableDeferred<Boolean>()
                pendingLocation = deferred
                withContext(Dispatchers.Main) { locationLauncher?.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) ?: deferred.complete(false) }
                deferred.await()
                NetworkObserver.refresh()
            }
            WifiAccess.LocationOff -> runCatching { activity?.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
        }
    }

    override suspend fun requestWifiAccess() {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) return
        val deferred = CompletableDeferred<Boolean>()
        pendingLocation = deferred
        withContext(Dispatchers.Main) { locationLauncher?.launch(Manifest.permission.ACCESS_FINE_LOCATION) ?: deferred.complete(false) }
        deferred.await()
        NetworkObserver.refresh()
    }

    override fun deviceInfo(): String = buildString {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        appendLine("App: ${info.versionName} (${context.packageName})")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        appendLine("Android: ${Build.VERSION.RELEASE} SDK ${Build.VERSION.SDK_INT}")
        appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
        append("Locale: ${Locale.getDefault()}")
    }

    override suspend fun shareFile(name: String, text: String) {
        val current = activity ?: return
        val file = withContext(Dispatchers.IO) {
            File(context.cacheDir, "share").apply { mkdirs() }.resolve(name).apply { writeText(text) }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        current.startActivity(Intent.createChooser(intent, name))
    }

    override suspend fun shareText(text: String) {
        val current = activity ?: return
        current.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
    }

    override suspend fun saveFile(name: String, text: String): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        pendingSave = text to deferred
        withContext(Dispatchers.Main) { saveLauncher?.launch(name) ?: deferred.complete(false) }
        return deferred.await()
    }

    override fun requestNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        activity?.runOnUiThread { notifyLauncher?.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }

    override val hasBatterySettings = true

    override val isBatteryUnrestricted: Boolean
        get() = context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    @SuppressLint("BatteryLife")
    override fun openBatterySettings() {
        val intent = if (isBatteryUnrestricted) {
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        } else {
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:${context.packageName}"))
        }
        runCatching { activity?.startActivity(intent) }
    }

    override fun copyGeoData() {
        val stamp = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString() }.getOrDefault("")
        val marker = File(dataDir, "geo.stamp")
        val fresh = stamp.isNotEmpty() && runCatching { marker.readText() }.getOrNull() != stamp
        listOf("GEOIP.metadb", "GEOSITE.dat").forEach { name ->
            val target = File(dataDir, name)
            if (target.exists() && !fresh) return@forEach
            val draft = File(dataDir, "$name.new")
            context.assets.open(name).use { input -> draft.outputStream().use(input::copyTo) }
            if (!draft.renameTo(target)) {
                target.delete()
                draft.renameTo(target)
            }
        }
        if (fresh) marker.writeText(stamp)
    }

    @Composable
    override fun systemColorScheme(dark: Boolean): ColorScheme? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }

    @Composable
    override fun ownIcon(): Painter? = remember {
        runCatching { BitmapPainter(context.applicationInfo.loadIcon(context.packageManager).toBitmap(256, 256).asImageBitmap()) }.getOrNull()
    }

    override fun cachedAppIcon(pkg: String): ImageBitmap? = icons[pkg]

    override suspend fun loadAppIcon(pkg: String): ImageBitmap? = icons[pkg] ?: withContext(iconDispatcher) {
        icons[pkg] ?: runCatching {
            val size = (40 * context.resources.displayMetrics.density).toInt()
            val bitmap = context.packageManager.getApplicationIcon(pkg).toBitmap(size, size)
            bitmap.prepareToDraw()
            bitmap.asImageBitmap()
        }.getOrNull()?.also { icons[pkg] = it }
    }
}

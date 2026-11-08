package dev.rooni.zefir.platform

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import dev.rooni.zefir.core.CoreApi
import dev.rooni.zefir.core.CoreTransport
import dev.rooni.zefir.model.*
import dev.rooni.zefir.ui.singleHue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.io.DataInputStream
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.CountDownLatch

class DesktopCore(private val binary: File) : CoreTransport() {
    val api = CoreApi(this)
    private val writeLock = Any()
    private val connected = CountDownLatch(1)
    @Volatile private var socket: Socket? = null
    @Volatile private var startError: String? = null
    private var process: Process? = null

    @Volatile var elevated = false
        private set
    var onLost: (() -> Unit)? = null

    fun start() {
        if (!isWindows()) {
            if (!linuxPrivileged(binary)) runCatching { binary.setExecutable(true) }
            elevated = linuxPrivileged(binary)
        }
        val server = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        process = ProcessBuilder(binary.absolutePath, server.localPort.toString())
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
        Runtime.getRuntime().addShutdownHook(Thread {
            process?.destroyForcibly()
            runCatching { socket?.close() }
        })
        process!!.onExit().thenRun {
            if (socket == null) {
                startError = "core exited with code ${runCatching { process?.exitValue() }.getOrNull()}"
                runCatching { server.close() }
            }
        }
        Thread({
            val accepted = runCatching {
                server.soTimeout = CONNECT_TIMEOUT.toInt()
                acceptOwn(server)
            }.getOrNull()
            runCatching { server.close() }
            if (accepted == null) {
                if (startError == null) startError = "core did not connect"
                process?.destroyForcibly()
                connected.countDown()
                onLost?.invoke()
                return@Thread
            }
            socket = accepted
            connected.countDown()
            readLoop(accepted)
        }, "core-reader").apply { isDaemon = true }.start()
    }

    fun restartElevated(): Boolean = if (isWindows()) restartWindows() else restartLinux()

    private fun restartLinux(): Boolean {
        if (!linuxPrivileged(binary) && !authorizeLinux(binary)) return false
        if (!linuxPrivileged(binary)) return false
        val server = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        try {
            val next = runCatching {
                ProcessBuilder(binary.absolutePath, server.localPort.toString())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start()
            }.getOrNull() ?: return false
            server.soTimeout = 15_000
            val accepted = runCatching { acceptOwn(server, next.pid()) }.getOrNull()
            if (accepted == null || !runsAsRoot(next.pid())) {
                runCatching { accepted?.close() }
                next.destroyForcibly()
                return false
            }
            val old = synchronized(writeLock) { socket.also { socket = accepted } }
            val oldProcess = process
            process = next
            elevated = true
            runCatching { old?.close() }
            oldProcess?.destroyForcibly()
            failPending("core restarted")
            Thread({ readLoop(accepted) }, "core-reader").apply { isDaemon = true }.start()
            return true
        } finally {
            server.close()
        }
    }

    private fun restartWindows(): Boolean {
        val server = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        try {
            val result = runCatching {
                Native.load("shell32", Shell32::class.java).ShellExecuteW(
                    null, WString("runas"), WString(binary.absolutePath), WString(server.localPort.toString()), WString(binary.parent), 0,
                )
            }.getOrDefault(0L)
            if (result <= 32L) return false
            server.soTimeout = 15_000
            val accepted = runCatching { acceptOwn(server) }.getOrNull() ?: return false
            val old = synchronized(writeLock) { socket.also { socket = accepted } }
            elevated = true
            runCatching { old?.close() }
            process?.destroyForcibly()
            process = null
            failPending("core restarted")
            Thread({ readLoop(accepted) }, "core-reader").apply { isDaemon = true }.start()
            return true
        } finally {
            server.close()
        }
    }

    private fun acceptOwn(server: ServerSocket, pid: Long? = process?.pid()): Socket {
        while (true) {
            val accepted = server.accept()
            val ours = if (isWindows()) connectionFrom(accepted, binary) else linuxConnectionFrom(accepted, pid)
            if (ours != false) return accepted
            runCatching { accepted.close() }
        }
    }

    private fun readLoop(from: Socket) {
        val input = DataInputStream(from.getInputStream().buffered())
        runCatching {
            while (true) {
                val size = ByteBuffer.wrap(ByteArray(4).also(input::readFully)).order(ByteOrder.LITTLE_ENDIAN).int
                receive(ByteArray(size).also(input::readFully))
            }
        }

        if (from === socket) {
            elevated = false
            failPending("core stopped")
            onLost?.invoke()
        }
    }

    override fun send(frame: String) {
        if (!connected.await(CONNECT_TIMEOUT, java.util.concurrent.TimeUnit.MILLISECONDS)) throw dev.rooni.zefir.core.CoreException("core not connected")
        if (socket == null) throw dev.rooni.zefir.core.CoreException(startError ?: "core not connected")
        val data = frame.encodeToByteArray()
        val header = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(data.size).array()
        synchronized(writeLock) {
            socket!!.getOutputStream().apply {
                write(header)
                write(data)
                flush()
            }
        }
    }
}

private const val CONNECT_TIMEOUT = 30_000L

internal fun coreBinaryName(): String {
    val arch = if (System.getProperty("os.arch").let { it == "aarch64" || it == "arm64" }) "arm64" else "amd64"
    return if (isWindows()) "vpncore-windows-$arch.exe" else "vpncore-linux-$arch"
}

private fun isWindows() = System.getProperty("os.name").startsWith("Windows")

private interface IpHelper : Library {
    fun GetExtendedTcpTable(table: Pointer?, size: IntArray, order: Boolean, family: Int, tableClass: Int, reserved: Int): Int
}

private interface Kernel32 : Library {
    fun OpenProcess(access: Int, inherit: Boolean, pid: Int): Pointer?
    fun QueryFullProcessImageNameW(process: Pointer, flags: Int, name: CharArray, size: IntArray): Boolean
    fun CloseHandle(handle: Pointer): Boolean
}

private fun connectionFrom(socket: Socket, binary: File): Boolean? = runCatching {
    val ip = Native.load("iphlpapi", IpHelper::class.java)
    val size = IntArray(1)
    ip.GetExtendedTcpTable(null, size, false, 2, 5, 0)
    val memory = com.sun.jna.Memory(size[0].toLong() + 4096)
    size[0] = memory.size().toInt()
    if (ip.GetExtendedTcpTable(memory, size, false, 2, 5, 0) != 0) return null
    fun port(raw: Int) = ((raw and 0xFF) shl 8) or ((raw shr 8) and 0xFF)
    val rows = memory.getInt(0)
    val pid = (0 until rows).map { 4L + it * 24L }.firstOrNull { offset ->
        port(memory.getInt(offset + 8)) == socket.port && port(memory.getInt(offset + 16)) == socket.localPort
    }?.let { memory.getInt(it + 20) } ?: return false
    val kernel = Native.load("kernel32", Kernel32::class.java)
    val handle = kernel.OpenProcess(0x1000, false, pid) ?: return null
    try {
        val name = CharArray(1024)
        val length = intArrayOf(name.size)
        if (!kernel.QueryFullProcessImageNameW(handle, 0, name, length)) return false
        File(String(name, 0, length[0])).canonicalFile == binary.canonicalFile
    } finally {
        kernel.CloseHandle(handle)
    }
}.getOrNull()

internal fun linuxConnectionFrom(socket: Socket, pid: Long?): Boolean? = runCatching {
    pid ?: return null
    val tables = listOf(File("/proc/net/tcp"), File("/proc/net/tcp6")).filter { it.canRead() }
    if (tables.isEmpty()) return null
    val inode = tables.asSequence().flatMap { it.readLines().drop(1).asSequence() }.mapNotNull { line ->
        val cols = line.trim().split(Regex("\\s+"))
        val local = cols.getOrNull(1)?.substringAfterLast(':')?.toIntOrNull(16)
        val remote = cols.getOrNull(2)?.substringAfterLast(':')?.toIntOrNull(16)
        if (local == socket.port && remote == socket.localPort) cols.getOrNull(9) else null
    }.firstOrNull() ?: return false
    val fds = File("/proc/$pid/fd").listFiles() ?: return null
    fds.any { runCatching { java.nio.file.Files.readSymbolicLink(it.toPath()).toString() == "socket:[$inode]" }.getOrDefault(false) }
}.getOrNull()

internal fun linuxPrivileged(binary: File): Boolean = runCatching {
    val path = binary.toPath()
    val uid = java.nio.file.Files.getAttribute(path, "unix:uid") as Int
    val mode = java.nio.file.Files.getAttribute(path, "unix:mode") as Int
    uid == 0 && (mode and 0x800) != 0
}.getOrDefault(false)

private fun runsAsRoot(pid: Long): Boolean = runCatching {
    val line = File("/proc/$pid/status").readLines().first { it.startsWith("Uid:") }
    line.split(Regex("\\s+")).getOrNull(2) == "0"
}.getOrDefault(false)

internal fun linuxAuthorizeScript(path: String): String {
    val quoted = "'" + path.replace("'", "'\\''") + "'"
    return "group=\$(id -g \"\${PKEXEC_UID:-\$SUDO_UID}\") && chown \"root:\$group\" $quoted && chmod 4750 $quoted"
}

private fun authorizeLinux(binary: File): Boolean {
    val command = listOf("/bin/sh", "-c", linuxAuthorizeScript(binary.canonicalPath))
    fun exec(vararg prefix: String): Int? = runCatching {
        ProcessBuilder(*prefix, *command.toTypedArray())
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
            .waitFor()
    }.getOrNull()
    val pkexec = exec("pkexec")
    if (pkexec == 0) return true
    if (pkexec == 126) return false
    return exec("sudo", "-n", "--") == 0
}

private interface Shell32 : Library {
    fun ShellExecuteW(hwnd: Pointer?, operation: WString, file: WString, parameters: WString, directory: WString, show: Int): Long
}

private interface SystemProxy {
    var stateFile: File?
    fun recover()
    fun set(port: Int, bypassLocal: Boolean)
    fun clear()
}

private object WindowsProxy : SystemProxy {
    private const val KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings"

    private const val NONE = "-"

    private const val BYPASS = "localhost;127.*;10.*;172.16.*;172.17.*;172.18.*;172.19.*;172.20.*;172.21.*;172.22.*;172.23.*;172.24.*;172.25.*;172.26.*;172.27.*;172.28.*;172.29.*;172.30.*;172.31.*;192.168.*;<local>"

    private var saved: Triple<String?, String?, String?>? = null
    override var stateFile: File? = null

    @Synchronized
    override fun recover() {
        val file = stateFile?.takeIf { it.exists() } ?: return
        val lines = runCatching { file.readLines() }.getOrNull()
        if (lines != null && lines.size >= 3) {
            fun value(line: String) = line.takeIf { it != NONE }
            saved = Triple(value(lines[0]), value(lines[1]), value(lines[2]))
            clear()
        }
        file.delete()
    }

    @Synchronized
    override fun set(port: Int, bypassLocal: Boolean) {
        if (saved == null) {
            saved = Triple(query("ProxyEnable"), query("ProxyServer"), query("ProxyOverride"))
            saved?.let { (a, b, c) -> runCatching { stateFile?.writeText(listOf(a, b, c).joinToString("\n") { it ?: NONE }) } }
        }
        reg("ProxyServer", "REG_SZ", "127.0.0.1:$port")
        if (bypassLocal) reg("ProxyOverride", "REG_SZ", BYPASS) else saved?.third?.let { reg("ProxyOverride", "REG_SZ", it) } ?: run("reg", "delete", KEY, "/v", "ProxyOverride", "/f")
        reg("ProxyEnable", "REG_DWORD", "1")
        notifySystem()
    }

    private fun notifySystem() {
        if (!System.getProperty("os.name").startsWith("Windows")) return
        runCatching {
            val api = Native.load("wininet", WinInet::class.java)
            api.InternetSetOptionW(null, 39, null, 0)
            api.InternetSetOptionW(null, 37, null, 0)
        }
    }

    @Synchronized
    override fun clear() {
        val (enable, server, bypass) = saved ?: return
        saved = null
        stateFile?.delete()
        reg("ProxyEnable", "REG_DWORD", enable ?: "0")
        if (server != null) reg("ProxyServer", "REG_SZ", server)
        else run("reg", "delete", KEY, "/v", "ProxyServer", "/f")
        if (bypass != null) reg("ProxyOverride", "REG_SZ", bypass)
        else run("reg", "delete", KEY, "/v", "ProxyOverride", "/f")
        notifySystem()
    }

    private fun reg(name: String, type: String, value: String) {
        run("reg", "add", KEY, "/v", name, "/t", type, "/d", value, "/f")
    }

    private fun query(name: String): String? {
        val line = run("reg", "query", KEY, "/v", name).lineSequence().firstOrNull { it.trim().startsWith(name) } ?: return null
        val value = line.trim().split(Regex("\\s+"), limit = 3).getOrNull(2) ?: return null
        return if (value.startsWith("0x")) value.removePrefix("0x").toInt(16).toString() else value
    }

    private fun run(vararg command: String): String {
        if (!System.getProperty("os.name").startsWith("Windows")) return ""
        return runCommand(*command)
    }
}

private object LinuxProxy : SystemProxy {
    private const val GNOME = "org.gnome.system.proxy"
    private val GNOME_KEYS = listOf(
        GNOME to "mode", GNOME to "ignore-hosts",
        "$GNOME.http" to "host", "$GNOME.http" to "port",
        "$GNOME.https" to "host", "$GNOME.https" to "port",
        "$GNOME.socks" to "host", "$GNOME.socks" to "port",
    )
    private const val KDE_GROUP = "Proxy Settings"
    private val KDE_KEYS = listOf("ProxyType", "httpProxy", "httpsProxy", "socksProxy", "NoProxyFor")
    private const val BYPASS_GNOME = "['localhost', '127.0.0.0/8', '::1', '10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16']"
    private const val BYPASS_KDE = "localhost,127.0.0.0/8,::1,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16"

    private var saved: List<Triple<String, String, String>>? = null
    override var stateFile: File? = null

    private val gsettings by lazy { which("gsettings") }
    private val kde by lazy { System.getenv("XDG_CURRENT_DESKTOP").orEmpty().contains("KDE", true) }
    private val kwrite by lazy { which("kwriteconfig6") ?: which("kwriteconfig5") }
    private val kread by lazy { which("kreadconfig6") ?: which("kreadconfig5") }

    private fun which(name: String): String? =
        System.getenv("PATH").orEmpty().split(':').map { File(it, name) }.firstOrNull { it.canExecute() }?.path

    @Synchronized
    override fun recover() {
        val file = stateFile?.takeIf { it.exists() } ?: return
        saved = runCatching {
            file.readLines().mapNotNull { line ->
                val parts = line.split('\t', limit = 3)
                if (parts.size == 3) Triple(parts[0], parts[1], parts[2]) else null
            }
        }.getOrNull()
        clear()
        file.delete()
    }

    @Synchronized
    override fun set(port: Int, bypassLocal: Boolean) {
        if (saved == null) {
            val backup = buildList {
                gsettings?.let { g ->
                    GNOME_KEYS.forEach { (schema, key) -> add(Triple("gnome", "$schema $key", runCommand(g, "get", schema, key).trim())) }
                }
                if (kde) kread?.let { r ->
                    KDE_KEYS.forEach { key -> add(Triple("kde", key, runCommand(r, "--file", "kioslaverc", "--group", KDE_GROUP, "--key", key).trim())) }
                }
            }
            saved = backup
            runCatching { stateFile?.writeText(backup.joinToString("\n") { "${it.first}\t${it.second}\t${it.third}" }) }
        }
        gsettings?.let { g ->
            listOf("http", "https", "socks").forEach { kind ->
                runCommand(g, "set", "$GNOME.$kind", "host", "127.0.0.1")
                runCommand(g, "set", "$GNOME.$kind", "port", port.toString())
            }
            if (bypassLocal) runCommand(g, "set", GNOME, "ignore-hosts", BYPASS_GNOME)
            runCommand(g, "set", GNOME, "mode", "manual")
        }
        if (kde) kwrite?.let { w ->
            fun put(key: String, value: String) = runCommand(w, "--file", "kioslaverc", "--group", KDE_GROUP, "--key", key, value)
            put("httpProxy", "http://127.0.0.1 $port")
            put("httpsProxy", "http://127.0.0.1 $port")
            put("socksProxy", "socks://127.0.0.1 $port")
            if (bypassLocal) put("NoProxyFor", BYPASS_KDE)
            put("ProxyType", "1")
            notifyKde()
        }
    }

    @Synchronized
    override fun clear() {
        val backup = saved ?: return
        saved = null
        stateFile?.delete()
        backup.sortedBy { it.second.endsWith(" mode") || it.second == "ProxyType" }.forEach { (backend, key, value) ->
            when (backend) {
                "gnome" -> gsettings?.let { g ->
                    val (schema, name) = key.split(' ', limit = 2)
                    if (value.isEmpty()) runCommand(g, "reset", schema, name) else runCommand(g, "set", schema, name, value)
                }
                "kde" -> kwrite?.let { w ->
                    if (value.isEmpty()) runCommand(w, "--file", "kioslaverc", "--group", KDE_GROUP, "--key", key, "--delete")
                    else runCommand(w, "--file", "kioslaverc", "--group", KDE_GROUP, "--key", key, value)
                }
            }
        }
        if (backup.any { it.first == "kde" }) notifyKde()
    }

    private fun notifyKde() {
        runCommand("dbus-send", "--type=signal", "/KIO/Scheduler", "org.kde.KIO.Scheduler.reparseSlaveConfiguration", "string:")
    }
}

private interface Dwm : Library {
    fun DwmSetWindowAttribute(hwnd: Pointer, attribute: Int, value: IntArray, size: Int): Int
}

private interface WinInet : Library {
    fun InternetSetOptionW(handle: Pointer?, option: Int, buffer: Pointer?, length: Int): Boolean
}

private fun runCommand(vararg command: String): String = runCatching {
    val process = ProcessBuilder(*command).redirectErrorStream(true).start()
    process.inputStream.bufferedReader().readText().also { process.waitFor() }
}.getOrDefault("")

private fun readLinuxAccent(): Long? {
    portalAccent()?.let { return it }
    kdeAccent()?.let { return it }
    gnomeAccent()?.let { return it }
    return gtkAccent()
}

private fun gtkAccent(): Long? = runCatching {
    val config = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.startsWith("/") } ?: "${System.getProperty("user.home")}/.config"
    val pattern = Regex("@define-color\\s+(accent_bg_color|accent_color|theme_selected_bg_color)\\s+#([0-9a-fA-F]{6})")
    listOf("gtk-4.0/gtk.css", "gtk-3.0/gtk.css").forEach { name ->
        val file = File(config, name).takeIf { it.canRead() && it.length() < 512 * 1024 } ?: return@forEach
        val found = pattern.findAll(file.readText()).associate { it.groupValues[1] to it.groupValues[2] }
        val hex = found["accent_bg_color"] ?: found["accent_color"] ?: found["theme_selected_bg_color"] ?: return@forEach
        return 0xFF000000 or hex.toLong(16)
    }
    null
}.getOrNull()

private fun rgb(r: Int, g: Int, b: Int): Long = 0xFF000000 or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()

private fun portalAccent(): Long? = runCatching {
    val out = runCommand(
        "gdbus", "call", "--session", "--timeout", "2",
        "--dest", "org.freedesktop.portal.Desktop",
        "--object-path", "/org/freedesktop/portal/desktop",
        "--method", "org.freedesktop.portal.Settings.ReadOne",
        "org.freedesktop.appearance", "accent-color",
    )
    val parts = Regex("\\(([0-9.eE+-]+),\\s*([0-9.eE+-]+),\\s*([0-9.eE+-]+)\\)").find(out)?.groupValues?.drop(1)?.map { it.toDouble() } ?: return null
    if (parts.any { it < 0.0 || it > 1.0 }) return null
    rgb((parts[0] * 255).toInt(), (parts[1] * 255).toInt(), (parts[2] * 255).toInt())
}.getOrNull()

private fun kdeAccent(): Long? = runCatching {
    if (!System.getenv("XDG_CURRENT_DESKTOP").orEmpty().contains("KDE", true)) return null
    val file = File(System.getProperty("user.home"), ".config/kdeglobals").takeIf { it.canRead() } ?: return null
    var group = ""
    file.readLines().forEach { raw ->
        val line = raw.trim()
        if (line.startsWith("[")) group = line
        else if (group == "[General]" && line.startsWith("AccentColor=")) {
            val (r, g, b) = line.substringAfter('=').split(',').map { it.trim().toInt() }
            return rgb(r, g, b)
        }
    }
    null
}.getOrNull()

private val GNOME_ACCENTS = mapOf(
    "blue" to rgb(0x35, 0x84, 0xE4), "teal" to rgb(0x21, 0x90, 0xA4), "green" to rgb(0x3A, 0x94, 0x4A),
    "yellow" to rgb(0xC8, 0x88, 0x00), "orange" to rgb(0xED, 0x5B, 0x00), "red" to rgb(0xE6, 0x2D, 0x42),
    "pink" to rgb(0xD5, 0x61, 0x99), "purple" to rgb(0x91, 0x41, 0xAC), "slate" to rgb(0x6F, 0x83, 0x96),
)

private fun gnomeAccent(): Long? = runCatching {
    val name = runCommand("gsettings", "get", "org.gnome.desktop.interface", "accent-color").trim().trim('\'')
    GNOME_ACCENTS[name]
}.getOrNull()

private fun readWindowsAccent(): Long? = runCatching {
    val line = runCommand("reg", "query", "HKCU\\Software\\Microsoft\\Windows\\DWM", "/v", "AccentColor")
        .lineSequence().first { it.trim().startsWith("AccentColor") }
    val abgr = line.trim().split(Regex("\\s+"))[2].removePrefix("0x").toLong(16)
    0xFF000000 or ((abgr and 0xFF) shl 16) or (abgr and 0xFF00) or ((abgr shr 16) and 0xFF)
}.getOrNull()

class DesktopPlatform(override val dataDir: String, private val coreDir: File) : Platform {
    private val isWindows = System.getProperty("os.name").startsWith("Windows")
    private val systemProxy: SystemProxy = if (isWindows) WindowsProxy else LinuxProxy
    private val dwm by lazy { runCatching { Native.load("dwmapi", Dwm::class.java) }.getOrNull() }
    private var window: Pointer? = null
    private var windowColors: Pair<Int, Int>? = null
    private val accent = MutableStateFlow(if (isWindows) readWindowsAccent() else readLinuxAccent())

    fun attachWindow(handle: Long) {
        window = Pointer(handle)
        applyWindowColors()
    }

    fun refreshAccent() {
        Thread { accent.value = if (isWindows) readWindowsAccent() else readLinuxAccent() }.apply { isDaemon = true }.start()
    }

    private val _windowIcon = MutableStateFlow(Color.Unspecified)
    val windowIcon: StateFlow<Color> = _windowIcon
    override val hasNotificationSettings = false
    override val tunInConfig get() = tunWanted && transport.elevated
    override val vpnProxy: java.net.Proxy?
        get() = if (engine.state.value == TunnelState.On) java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress("127.0.0.1", proxyPort)) else null

    val proxyPort: Int = pickPort()

    @Volatile private var tunWanted = true
    @Volatile private var proxyOnly = false
    @Volatile private var tunDeclined = false

    override val isDesktop = true
    override val hasDesktopTun get() = true
    override val hasCustomFrame get() = true
    override val desktopOsName get() = if (isWindows) "" else "Linux"
    override val hasHaptics = false
    override val hasWifiRules = false

    override fun applyDesktopSettings(tun: Boolean) {
        tunWanted = tun
        if (tun) proxyOnly = false
    }

    override fun takeTunDeclined(): Boolean = tunDeclined.also { tunDeclined = false }

    private val _caption = MutableStateFlow<Pair<Color, Color>?>(null)
    val caption: StateFlow<Pair<Color, Color>?> = _caption

    override fun setWindowColors(caption: Color, text: Color, icon: Color) {
        _windowIcon.value = icon
        _caption.value = caption to text
        val next = caption.colorRef() to text.colorRef()
        if (next == windowColors) return
        windowColors = next
        applyWindowColors()
    }

    private fun applyWindowColors() {
        val hwnd = window ?: return
        val (caption, text) = windowColors ?: return
        val api = dwm ?: return
        api.DwmSetWindowAttribute(hwnd, DWMWA_BORDER_COLOR, intArrayOf(caption), 4)
        api.DwmSetWindowAttribute(hwnd, DWMWA_CAPTION_COLOR, intArrayOf(caption), 4)
        api.DwmSetWindowAttribute(hwnd, DWMWA_TEXT_COLOR, intArrayOf(text), 4)
    }

    private fun Color.colorRef(): Int {
        val argb = toArgb()
        return ((argb and 0xFF) shl 16) or (argb and 0xFF00) or ((argb shr 16) and 0xFF)
    }

    @Composable
    override fun systemColorScheme(dark: Boolean): ColorScheme? {
        val seed by accent.collectAsState()
        val value = seed ?: return null
        return remember(value, dark) { singleHue(value, dark) }
    }

    override val sdkVersion = 0
    override val foreground: kotlinx.coroutines.flow.StateFlow<Boolean> = kotlinx.coroutines.flow.MutableStateFlow(true)
    override val hasPerAppRouting = false
    override val canScanQr = false
    override val resumed: StateFlow<Int> = MutableStateFlow(0)
    override val network: StateFlow<NetworkState> = MutableStateFlow(NetworkState())

    override fun deviceInfo() = "OS: ${System.getProperty("os.name")} ${System.getProperty("os.version")}\nJava: ${System.getProperty("java.version")}\nLocale: ${Locale.getDefault()}"

    override suspend fun shareFile(name: String, text: String) {
        saveFile(name, text)
    }

    override suspend fun saveFile(name: String, text: String): Boolean = withContext(Dispatchers.IO) {
        val dialog = FileDialog(null as Frame?, name, FileDialog.SAVE)
        dialog.file = name
        dialog.isVisible = true
        val file = dialog.file ?: return@withContext false
        File(dialog.directory, file).writeText(text)
        true
    }

    private val transport = DesktopCore(File(coreDir, coreBinaryName())).also { it.start() }

    override val engine = object : VpnEngine {
        private val _state = MutableStateFlow<TunnelState>(TunnelState.Off)
        override val state: StateFlow<TunnelState> = _state
        override val core: CoreApi = transport.api

        init {
            transport.onLost = {
                systemProxy.clear()
                _state.value = TunnelState.Failed("core stopped")
            }
        }

        override fun hasPermission() = !tunWanted || proxyOnly || transport.elevated

        override suspend fun ensurePermission(): Boolean = withContext(Dispatchers.IO) {
            if (hasPermission()) return@withContext true
            _state.value = TunnelState.Starting
            val ok = transport.restartElevated() && runCatching {
                transport.api.init(dataDir, sdkVersion)
                transport.api.startLog()
            }.isSuccess
            if (!ok) {
                proxyOnly = true
                tunDeclined = true
            }
            true
        }

        override suspend fun start(options: TunnelOptions) = withContext(Dispatchers.IO) {
            _state.value = TunnelState.Starting
            runCatching {
                transport.call("startListener")
                systemProxy.set(proxyPort, options.bypassLocal)
            }.onSuccess { _state.value = TunnelState.On }
                .onFailure { _state.value = TunnelState.Failed(it.message ?: "start failed") }
            Unit
        }

        override suspend fun stop() = withContext(Dispatchers.IO) {
            _state.value = TunnelState.Stopping
            systemProxy.clear()
            runCatching { transport.call("stopListener") }
            _state.value = TunnelState.Off
        }
    }

    init {
        systemProxy.stateFile = File(dataDir, "proxy-restore.txt")
        systemProxy.recover()
        Runtime.getRuntime().addShutdownHook(Thread { systemProxy.clear() })
    }

    override fun detectCountry(): String = Locale.getDefault().country.uppercase()

    override fun writeClipboard(text: String, secret: Boolean) {
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(text), null) }
    }

    override fun readClipboard(): String? = runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String
    }.getOrNull()

    override suspend fun installedApps(): List<InstalledApp> = emptyList()

    override suspend fun pickFile(): String? = withContext(Dispatchers.IO) {
        val dialog = FileDialog(null as Frame?)
        dialog.isVisible = true
        val name = dialog.file ?: return@withContext null
        File(dialog.directory, name).readText()
    }

    override suspend fun scanQr(): String? = null

    override fun copyGeoData() {
        File(coreDir, "geo").listFiles()?.forEach { file ->
            val target = File(dataDir, file.name)
            if (!target.exists() || file.lastModified() > target.lastModified()) file.copyTo(target, overwrite = true)
        }
    }

    companion object {
        const val DESKTOP_PROXY_PORT = 7890

        private fun free(port: Int) = runCatching {
            java.net.ServerSocket(port, 1, java.net.InetAddress.getLoopbackAddress()).close()
        }.isSuccess

        fun pickPort(): Int = (DESKTOP_PROXY_PORT..DESKTOP_PROXY_PORT + 9).firstOrNull(::free)
            ?: java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { it.localPort }
        private const val DWMWA_BORDER_COLOR = 34
        private const val DWMWA_CAPTION_COLOR = 35
        private const val DWMWA_TEXT_COLOR = 36
    }
}

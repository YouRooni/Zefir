package dev.rooni.zefir

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.RenderVectorGroup
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.FrameWindowScope
import com.kdroid.composetray.tray.api.Tray as SniTray
import androidx.compose.ui.awt.SwingWindow
import androidx.compose.ui.window.application
import androidx.compose.ui.graphics.toAwtImage
import dev.rooni.zefir.app.AppController
import dev.rooni.zefir.model.TunnelState
import dev.rooni.zefir.platform.DesktopPlatform
import dev.rooni.zefir.res.*
import dev.rooni.zefir.ui.App
import dev.rooni.zefir.ui.ZefirMark
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import kotlin.system.exitProcess

private val isLinux = System.getProperty("os.name").startsWith("Linux")

private fun linuxWindowClass() {
    runCatching {
        val toolkit = java.awt.Toolkit.getDefaultToolkit()
        val field = toolkit.javaClass.getDeclaredField("awtAppClassName")
        field.isAccessible = true
        field.set(toolkit, "zefir")
    }
}

private fun linuxTrayHost(): Boolean = runCatching {
    val process = ProcessBuilder(
        "gdbus", "call", "--session", "--timeout", "2",
        "--dest", "org.freedesktop.DBus", "--object-path", "/org/freedesktop/DBus",
        "--method", "org.freedesktop.DBus.NameHasOwner", "org.kde.StatusNotifierWatcher",
    ).redirectErrorStream(true).start()
    val out = process.inputStream.bufferedReader().readText()
    process.waitFor()
    "true" in out
}.getOrDefault(true)

private fun trayIconFiles(painter: Painter, density: androidx.compose.ui.unit.Density, tag: String): Pair<String, String> {
    val dir = File(System.getProperty("java.io.tmpdir"), "zefir-tray").apply { mkdirs() }
    fun png(size: Int): ByteArray {
        val image = painter.toAwtImage(density, androidx.compose.ui.unit.LayoutDirection.Ltr, androidx.compose.ui.geometry.Size(size.toFloat(), size.toFloat()))
        val buffer = java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        buffer.createGraphics().apply {
            drawImage(image, 0, 0, size, size, null)
            dispose()
        }
        return java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(buffer, "png", it) }.toByteArray()
    }
    val pngFile = File(dir, "tray-$tag.png")
    if (!pngFile.exists()) pngFile.writeBytes(png(64))
    val icoFile = File(dir, "tray-$tag.ico")
    if (!icoFile.exists()) {
        val sizes = listOf(16, 20, 24, 32, 48, 64)
        val images = sizes.map(::png)
        val header = java.nio.ByteBuffer.allocate(6 + 16 * sizes.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        header.putShort(0).putShort(1).putShort(sizes.size.toShort())
        var offset = 6 + 16 * sizes.size
        sizes.zip(images).forEach { (size, data) ->
            header.put(size.toByte()).put(size.toByte()).put(0).put(0).putShort(1).putShort(32).putInt(data.size).putInt(offset)
            offset += data.size
        }
        icoFile.outputStream().use { out ->
            out.write(header.array())
            images.forEach(out::write)
        }
    }
    return pngFile.toURI().toString() to icoFile.toURI().toString()
}

private fun linuxHome(): File {
    val user = System.getProperty("user.home")
    val base = System.getenv("XDG_DATA_HOME")?.takeIf { it.startsWith("/") } ?: "$user/.local/share"
    val target = File(base, "zefir")
    val legacy = File(user, ".zefir")
    if (legacy.isDirectory && !target.exists()) {
        target.parentFile?.mkdirs()
        if (!legacy.renameTo(target) && runCatching { legacy.copyRecursively(target) }.getOrDefault(false)) legacy.deleteRecursively()
    }
    return target
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun main(args: Array<String>) {
    if (isLinux) linuxWindowClass()
    val coreDir = File(System.getProperty("compose.application.resources.dir") ?: "core/prebuilt")

    val appRoot = coreDir.absoluteFile.parentFile?.parentFile
    val portableHome = appRoot?.takeIf { File(it, "portable").exists() }?.let { File(it, "data").path }
    val home = (System.getProperty("zefir.home")?.let(::File) ?: portableHome?.let(::File) ?: if (isLinux) linuxHome() else File("${System.getProperty("user.home")}/.zefir")).apply { mkdirs() }

    val wake = MutableSharedFlow<String>(extraBufferCapacity = 8)
    if (!SingleInstance.acquire(home, args) { wake.tryEmit(it) }) exitProcess(0)

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val platform = DesktopPlatform(home.absolutePath, coreDir)
    val controller = AppController(platform, scope)
    controller.update({ it.copy(localProxyPort = platform.proxyPort) })
    args.firstOrNull { "://" in it }?.let(controller::requestImport)

    scope.launch(Dispatchers.IO) { Res.allStringResources.values.forEach { runCatching { getString(it) } } }

    val isWindows = System.getProperty("os.name").startsWith("Windows")
    val customFrame = controller.settings.value.customTitleBar && (isWindows || isLinux) && System.getProperty("zefir.nativeframe") == null
    val linuxFrame = customFrame && isLinux
    val linuxTray = isLinux && linuxTrayHost()

    application {
        val iconColor by platform.windowIcon.collectAsState()
        val settings by controller.settings.collectAsState()
        val tunnel by controller.tunnel.collectAsState()
        var visible by remember { mutableStateOf(true) }
        var frame by remember { mutableStateOf<androidx.compose.ui.awt.ComposeWindow?>(null) }
        var shownIcon by remember { mutableStateOf<Color?>(null) }
        fun restore() {
            visible = true
            frame?.let { it.extendedState = it.extendedState and java.awt.Frame.ICONIFIED.inv() }
        }

        val icon = key(iconColor) {
            rememberVectorPainter(
                defaultWidth = ZefirMark.defaultWidth,
                defaultHeight = ZefirMark.defaultHeight,
                viewportWidth = ZefirMark.viewportWidth,
                viewportHeight = ZefirMark.viewportHeight,
                name = ZefirMark.name,
                tintColor = iconColor,
                tintBlendMode = BlendMode.SrcIn,
                autoMirror = false,
            ) { _, _ -> RenderVectorGroup(ZefirMark.root) }
        }

        LaunchedEffect(Unit) {
            wake.collect { message ->
                if (message == SingleInstance.SHOW) {
                    restore()
                } else if ("://" in message) {
                    visible = true
                    controller.requestImport(message)
                }
            }
        }

        val trayOn = settings.closeToTray && (if (isLinux) linuxTray else true)
        if (trayOn) {
            val connected = tunnel == TunnelState.On || tunnel == TunnelState.Starting
            val proxies by controller.proxies.collectAsState()
            val server = proxies.resolve(proxies.main?.now)?.let { dev.rooni.zefir.ui.screens.parseServerName(it).label }
            val status = when (tunnel) {
                TunnelState.On -> stringResource(Res.string.home_on) + (server?.let { " · $it" } ?: "")
                TunnelState.Starting -> stringResource(Res.string.home_starting)
                else -> stringResource(Res.string.home_off)
            }
            val openLabel = stringResource(Res.string.tray_open)
            val toggleLabel = stringResource(if (connected) Res.string.tray_disconnect else Res.string.tray_connect)
            val exitLabel = stringResource(Res.string.tray_exit)
            val trayDensity = LocalDensity.current
            val files = remember(iconColor) { trayIconFiles(icon, trayDensity, iconColor.hashCode().toUInt().toString(16)) }
            SniTray(
                iconPath = files.first,
                windowsIconPath = files.second,
                tooltip = "Zefir · $status",
                primaryAction = { restore() },
                menuContent = {
                    Item(status, isEnabled = false)
                    Divider()
                    Item(openLabel) { restore() }
                    Item(toggleLabel) { controller.toggle() }
                    Divider()
                    Item(exitLabel) { exitApplication() }
                },
            )
        }

        val close by rememberUpdatedState { if (trayOn) visible = false else exitApplication() }
        val density = LocalDensity.current
        SwingWindow(
            visible = visible,
            create = {
                androidx.compose.ui.awt.ComposeWindow(coroutineContext = dev.rooni.zefir.ui.AppMotion).apply {
                    title = "Zefir"
                    isUndecorated = linuxFrame
                    defaultCloseOperation = javax.swing.WindowConstants.DO_NOTHING_ON_CLOSE
                    addWindowListener(object : WindowAdapter() {
                        override fun windowClosing(e: WindowEvent) = close()
                    })
                    setSize(1000, 720)
                    setLocationRelativeTo(null)
                    frame = this
                }
            },
            dispose = { it.dispose() },
            update = { window ->
                if (window.iconImage == null || iconColor != shownIcon) {
                    shownIcon = iconColor
                    window.iconImage = icon.toAwtImage(density, androidx.compose.ui.unit.LayoutDirection.Ltr, androidx.compose.ui.geometry.Size(256f, 256f))
                }
            },
        ) {
            var framed by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                platform.attachWindow(window.windowHandle)
                window.addWindowFocusListener(object : WindowAdapter() {
                    override fun windowGainedFocus(e: WindowEvent) = platform.refreshAccent()
                })
                if (customFrame) framed = if (linuxFrame) true else WindowsFrame.install(window.windowHandle)
            }
            LaunchedEffect(visible) {
                if (visible) {
                    window.toFront()
                    window.requestFocus()
                }
            }
            if (framed && linuxFrame) {
                Box(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize()) {
                        LinuxTitleBar(platform, icon, onClose = { if (trayOn) visible = false else exitApplication() })
                        Box(Modifier.weight(1f)) { App(controller) }
                    }
                    ResizeEdges()
                }
            } else if (framed) {
                Column(Modifier.fillMaxSize()) {
                    TitleBar(platform, icon, onClose = { if (trayOn) visible = false else exitApplication() })
                    Box(Modifier.weight(1f)) { App(controller) }
                }
            } else {
                App(controller)
            }
        }
    }
}

private val CaptionHeight = 36.dp
private val CaptionButton = 46.dp

@Composable
private fun FrameWindowScope.TitleBar(platform: DesktopPlatform, icon: Painter, onClose: () -> Unit) {
    val colors by platform.caption.collectAsState()
    val background = colors?.first ?: Color(0xFF1B1B1F)
    val content = colors?.second ?: Color.White
    val density = LocalDensity.current
    SideEffect {
        with(density) {
            WindowsFrame.captionHeight = CaptionHeight.roundToPx()
            WindowsFrame.buttonsWidth = (CaptionButton * 3).roundToPx()
        }
        WindowsFrame.refreshChildren()
    }
    Row(Modifier.fillMaxWidth().height(CaptionHeight).captionBackground(background), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(14.dp))
        Image(icon, null, Modifier.size(16.dp))
        Spacer(Modifier.width(10.dp))
        Text("Zefir", style = TextStyle(fontSize = 12.sp, color = content.copy(alpha = 0.8f)))
        Spacer(Modifier.weight(1f))
        CaptionButton(content, Color.Transparent, { window.extendedState = window.extendedState or java.awt.Frame.ICONIFIED }) { color ->
            drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 1.dp.toPx())
        }
        val maximized = window.extendedState and java.awt.Frame.MAXIMIZED_BOTH != 0
        var zoomed by remember { mutableStateOf(maximized) }
        DisposableEffect(Unit) {
            val listener = java.awt.event.WindowStateListener { zoomed = window.extendedState and java.awt.Frame.MAXIMIZED_BOTH != 0 }
            window.addWindowStateListener(listener)
            onDispose { window.removeWindowStateListener(listener) }
        }
        CaptionButton(content, Color.Transparent, {
            window.extendedState = if (zoomed) java.awt.Frame.NORMAL else java.awt.Frame.MAXIMIZED_BOTH
        }) { color ->
            val stroke = 1.dp.toPx()
            if (zoomed) {
                val step = size.width * 0.22f
                drawRect(color, topLeft = Offset(0f, step), size = androidx.compose.ui.geometry.Size(size.width - step, size.height - step), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                drawLine(color, Offset(step, 0f), Offset(size.width, 0f), stroke)
                drawLine(color, Offset(size.width, 0f), Offset(size.width, size.height - step), stroke)
            } else {
                drawRect(color, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
            }
        }
        CaptionButton(content, Color(0xFFC42B1C), onClose, hoverContent = Color.White) { color ->
            val stroke = 1.dp.toPx()
            drawLine(color, Offset(0f, 0f), Offset(size.width, size.height), stroke)
            drawLine(color, Offset(size.width, 0f), Offset(0f, size.height), stroke)
        }
    }
}

@Composable
private fun CaptionButton(
    content: Color,
    hoverColor: Color,
    onClick: () -> Unit,
    hoverContent: Color = content,
    glyph: androidx.compose.ui.graphics.drawscope.DrawScope.(Color) -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val pressed by source.collectIsPressedAsState()
    val fill = when {
        hoverColor != Color.Transparent && (hovered || pressed) -> hoverColor.copy(alpha = if (pressed) 0.85f else 1f)
        pressed -> content.copy(alpha = 0.14f)
        hovered -> content.copy(alpha = 0.08f)
        else -> Color.Transparent
    }
    val tint = if (hovered || pressed) hoverContent else content
    Box(
        Modifier.width(CaptionButton).fillMaxHeight().background(fill)
            .hoverable(source)
            .clickable(source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(10.dp)) { glyph(tint) }
    }
}

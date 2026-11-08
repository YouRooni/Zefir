package dev.rooni.zefir

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.FrameWindowScope
import dev.rooni.zefir.platform.DesktopPlatform
import dev.rooni.zefir.ui.WindowChrome
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.takeOrElse
import java.awt.Cursor
import java.awt.Frame
import java.awt.MouseInfo
import java.awt.Rectangle

private val LinuxCaption = 40.dp
private val LinuxButton = 28.dp
private val Edge = 6.dp
private const val MIN_WIDTH = 480
private const val MIN_HEIGHT = 400

@Composable
fun FrameWindowScope.LinuxTitleBar(platform: DesktopPlatform, icon: Painter, onClose: () -> Unit) {
    val colors by platform.caption.collectAsState()
    val background = colors?.first ?: Color(0xFF1B1B1F)
    val content = colors?.second ?: Color.White
    var zoomed by remember { mutableStateOf(window.extendedState and Frame.MAXIMIZED_BOTH != 0) }
    DisposableEffect(Unit) {
        val listener = java.awt.event.WindowStateListener { zoomed = window.extendedState and Frame.MAXIMIZED_BOTH != 0 }
        window.addWindowStateListener(listener)
        onDispose { window.removeWindowStateListener(listener) }
    }
    val toggleZoom = { window.extendedState = if (zoomed) Frame.NORMAL else Frame.MAXIMIZED_BOTH }
    WindowDraggableArea(Modifier.fillMaxWidth().height(LinuxCaption).captionBackground(background)) {
        Row(
            Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onDoubleTap = { toggleZoom() }) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(14.dp))
            Image(icon, null, Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text("Zefir", style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = content.copy(alpha = 0.85f)))
            Spacer(Modifier.weight(1f))
            RoundButton(content, { window.extendedState = window.extendedState or Frame.ICONIFIED }) { color ->
                drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.5.dp.toPx())
            }
            Spacer(Modifier.width(6.dp))
            RoundButton(content, toggleZoom) { color ->
                val stroke = 1.5.dp.toPx()
                if (zoomed) {
                    val step = size.width * 0.25f
                    drawRect(color, topLeft = Offset(0f, step), size = androidx.compose.ui.geometry.Size(size.width - step, size.height - step), style = Stroke(stroke))
                    drawLine(color, Offset(step, 0f), Offset(size.width, 0f), stroke)
                    drawLine(color, Offset(size.width, 0f), Offset(size.width, size.height - step), stroke)
                } else {
                    drawRoundRect(color, cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5.dp.toPx()), style = Stroke(stroke))
                }
            }
            Spacer(Modifier.width(6.dp))
            RoundButton(content, onClose, accent = true) { color ->
                val stroke = 1.5.dp.toPx()
                drawLine(color, Offset(0f, 0f), Offset(size.width, size.height), stroke)
                drawLine(color, Offset(size.width, 0f), Offset(0f, size.height), stroke)
            }
            Spacer(Modifier.width(8.dp))
        }
    }
}

@Composable
private fun RoundButton(content: Color, onClick: () -> Unit, accent: Boolean = false, glyph: DrawScope.(Color) -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val pressed by source.collectIsPressedAsState()
    val fill = when {
        accent && (hovered || pressed) -> Color(0xFFE5484D).copy(alpha = if (pressed) 0.85f else 1f)
        pressed -> content.copy(alpha = 0.18f)
        hovered -> content.copy(alpha = 0.10f)
        else -> content.copy(alpha = 0.06f)
    }
    val tint = if (accent && (hovered || pressed)) Color.White else content
    Box(
        Modifier.size(LinuxButton).clip(CircleShape).background(fill)
            .hoverable(source)
            .clickable(source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(10.dp)) { glyph(tint) }
    }
}

fun Modifier.captionBackground(fallback: Color): Modifier = drawBehind {
    drawRect(WindowChrome.topColor().takeOrElse { fallback })
    val rail = WindowChrome.railWidth.toPx()
    if (WindowChrome.railShown && rail > 0f) {
        drawRect(WindowChrome.railColor.takeOrElse { fallback }, size = androidx.compose.ui.geometry.Size(rail, size.height))
        val shade = 8.dp.toPx()
        drawRect(
            androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.10f), Color.Transparent), rail, rail + shade),
            topLeft = Offset(rail, 0f),
            size = androidx.compose.ui.geometry.Size(shade, size.height),
        )
    }
}

private enum class Side(val left: Boolean, val top: Boolean, val right: Boolean, val bottom: Boolean, val cursor: Int) {
    N(false, true, false, false, Cursor.N_RESIZE_CURSOR),
    S(false, false, false, true, Cursor.S_RESIZE_CURSOR),
    W(true, false, false, false, Cursor.W_RESIZE_CURSOR),
    E(false, false, true, false, Cursor.E_RESIZE_CURSOR),
    NW(true, true, false, false, Cursor.NW_RESIZE_CURSOR),
    NE(false, true, true, false, Cursor.NE_RESIZE_CURSOR),
    SW(true, false, false, true, Cursor.SW_RESIZE_CURSOR),
    SE(false, false, true, true, Cursor.SE_RESIZE_CURSOR),
}

@Composable
fun FrameWindowScope.ResizeEdges() {
    var zoomed by remember { mutableStateOf(window.extendedState and Frame.MAXIMIZED_BOTH != 0) }
    DisposableEffect(Unit) {
        val listener = java.awt.event.WindowStateListener { zoomed = window.extendedState and Frame.MAXIMIZED_BOTH != 0 }
        window.addWindowStateListener(listener)
        onDispose { window.removeWindowStateListener(listener) }
    }
    if (zoomed) return
    Box(Modifier.fillMaxSize()) {
        Side.entries.forEach { side ->
            val horizontal = side.left || side.right
            val vertical = side.top || side.bottom
            val align = when {
                side.top && side.left -> Alignment.TopStart
                side.top && side.right -> Alignment.TopEnd
                side.bottom && side.left -> Alignment.BottomStart
                side.bottom && side.right -> Alignment.BottomEnd
                side.top -> Alignment.TopCenter
                side.bottom -> Alignment.BottomCenter
                side.left -> Alignment.CenterStart
                else -> Alignment.CenterEnd
            }
            val size: Modifier = when {
                horizontal && vertical -> Modifier.size(Edge * 2)
                vertical -> Modifier.fillMaxWidth().height(Edge).padding(horizontal = Edge * 2)
                else -> Modifier.fillMaxHeight().width(Edge).padding(vertical = Edge * 2)
            }
            Box(Modifier.align(align).then(size).resizeDrag(window, side))
        }
    }
}

private fun Modifier.resizeDrag(target: java.awt.Window, side: Side): Modifier =
    pointerHoverIcon(PointerIcon(Cursor(side.cursor))).pointerInput(side) {
        var start: Rectangle? = null
        var origin: java.awt.Point? = null
        detectDragGestures(
            onDragStart = {
                start = target.bounds
                origin = MouseInfo.getPointerInfo()?.location
            },
            onDragEnd = { start = null },
            onDragCancel = { start = null },
        ) { change, _ ->
            change.consume()
            val from = start ?: return@detectDragGestures
            val at = origin ?: return@detectDragGestures
            val now = MouseInfo.getPointerInfo()?.location ?: return@detectDragGestures
            val dx = now.x - at.x
            val dy = now.y - at.y
            var x = from.x
            var y = from.y
            var w = from.width
            var h = from.height
            if (side.right) w = (from.width + dx).coerceAtLeast(MIN_WIDTH)
            if (side.bottom) h = (from.height + dy).coerceAtLeast(MIN_HEIGHT)
            if (side.left) {
                w = (from.width - dx).coerceAtLeast(MIN_WIDTH)
                x = from.x + from.width - w
            }
            if (side.top) {
                h = (from.height - dy).coerceAtLeast(MIN_HEIGHT)
                y = from.y + from.height - h
            }
            target.setBounds(x, y, w, h)
        }
    }

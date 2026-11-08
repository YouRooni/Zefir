package dev.rooni.zefir.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

class ChromeEntry {
    var color by mutableStateOf<() -> Color>({ Color.Unspecified })
    var active by mutableStateOf(true)
}

object WindowChrome {
    val entries = mutableStateListOf<ChromeEntry>()
    var railWidth by mutableStateOf(0.dp)
    var railColor by mutableStateOf(Color.Unspecified)
    var railShown by mutableStateOf(false)

    fun topColor(): Color = entries.lastOrNull { it.active }?.color?.invoke() ?: Color.Unspecified
}

val LocalCaptionActive = compositionLocalOf { true }

val LocalNavBarSpace = compositionLocalOf { 0.dp }

@Composable
fun CaptionColor(active: Boolean = true, color: () -> Color) {
    val entry = remember { ChromeEntry() }
    val shown = active && LocalCaptionActive.current
    SideEffect {
        entry.color = color
        entry.active = shown
    }
    DisposableEffect(entry) {
        WindowChrome.entries += entry
        onDispose { WindowChrome.entries -= entry }
    }
}

@Composable
fun CaptionRail(shown: Boolean, color: Color) {
    SideEffect {
        WindowChrome.railShown = shown
        WindowChrome.railColor = color
    }
    DisposableEffect(Unit) { onDispose { WindowChrome.railShown = false } }
}

fun reportRailWidth(width: Dp) {
    WindowChrome.railWidth = width
}

package dev.rooni.zefir.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.onFocusedBoundsChanged
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

// Навигация с пульта и клавиатуры. На телеке всё живёт в одном окне: экраны стека,
// вкладки и диалоги лежат друг над другом, поэтому фокус надо явно держать на видимом слое,
// а сам фокус рисовать заметной рамкой, иначе на тёмной теме его не видно.

// true, когда юзер рулит стрелками: телек всегда, телефон после нажатия клавиши (пульт, клавиатура).
// На десктопе режим ввода всегда «клавиатура», там свой фокус от мыши не трогаем
@Composable
fun keyNavActive(): Boolean {
    val platform = LocalPlatform.current
    return platform.isTv || (!platform.isDesktop && LocalInputModeManager.current.inputMode == InputMode.Keyboard)
}

// Рамка вокруг элемента в фокусе. Вешается на корень окна (приложение, шторка, меню):
// ловит границы любого focusable внутри и рисует поверх контента
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.focusRing(): Modifier = composed {
    val active = keyNavActive()
    val color = MaterialTheme.colorScheme.primary
    var self by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var focused by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var tick by remember { mutableStateOf(0) }
    this.onGloballyPositioned { self = it }
        .onFocusedBoundsChanged {
            focused = it
            tick++ // те же координаты могут прийти после скролла, а перерисовать надо
        }
        .drawWithContent {
            drawContent()
            tick
            val root = self ?: return@drawWithContent
            val target = focused ?: return@drawWithContent
            if (!active || !root.isAttached || !target.isAttached) return@drawWithContent
            val box = runCatching { root.localBoundingBoxOf(target, clipBounds = true) }.getOrNull() ?: return@drawWithContent
            if (box.width < 1f || box.height < 1f) return@drawWithContent
            val stroke = 3.dp.toPx()
            val out = 2.dp.toPx() + stroke / 2
            val ring = Rect(box.left - out, box.top - out, box.right + out, box.bottom + out)
            val corner = minOf(ring.minDimension / 2, 24.dp.toPx())
            drawRoundRect(color, Offset(ring.left, ring.top), Size(ring.width, ring.height), CornerRadius(corner), style = Stroke(stroke))
        }
}

// Активен ли слой, внутри которого мы сидим. Вложенный слой не может быть активнее родителя:
// иначе экран под открытым диалогом утащит фокус обратно
val LocalFocusLayerOn = androidx.compose.runtime.compositionLocalOf { true }

@Composable
fun FocusLayer(active: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val on = active && LocalFocusLayerOn.current
    val requester = remember { FocusRequester() }
    androidx.compose.foundation.layout.Box(modifier.focusLayer(on, requester), propagateMinConstraints = true) {
        androidx.compose.runtime.CompositionLocalProvider(LocalFocusLayerOn provides on, content = content)
    }
}

// Кто из слоёв сейчас держит фокус и активен ли он. Фокус «потерян», если его нет ни в одном слое
// или он застрял в скрытом. Тогда видимые слои забирают его себе, в остальное время никто ничего не трогает
private object FocusLayers {
    val holders = androidx.compose.runtime.mutableStateMapOf<Any, Boolean>()
    val lost: Boolean get() = holders.isEmpty() || holders.values.any { !it }
}

// Слой, который сейчас не на экране (экран под верхним, соседняя вкладка, всё под диалогом):
// стрелки в него не заходят, а если фокус остался внутри, он уезжает на видимый слой
fun Modifier.focusLayer(active: Boolean, requester: FocusRequester): Modifier = composed {
    val key = remember { Any() }
    val keys = keyNavActive()
    androidx.compose.runtime.DisposableEffect(key) { onDispose { FocusLayers.holders.remove(key) } }
    androidx.compose.runtime.SideEffect { if (key in FocusLayers.holders) FocusLayers.holders[key] = active }
    LaunchedEffect(active, keys) {
        if (!active || !keys) return@LaunchedEffect
        androidx.compose.runtime.snapshotFlow { FocusLayers.lost }.collect { lost ->
            if (lost) {
                // ждём кадр: верхний экран мог только что появиться, а нижний ещё не отпустил фокус
                delay(32)
                if (FocusLayers.lost) runCatching { requester.requestFocus() }
            }
        }
    }
    this
        .focusRequester(requester)
        .onFocusChanged { if (it.hasFocus) FocusLayers.holders[key] = active else FocusLayers.holders.remove(key) }
        // видимый слой при возврате (назад со вложенного экрана) ставит фокус туда, где он был
        .then(if (active) Modifier.focusRestorer() else Modifier.focusProperties { onEnter = { cancelFocusChange() } })
        .focusGroup()
}

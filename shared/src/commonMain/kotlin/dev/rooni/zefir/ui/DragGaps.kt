package dev.rooni.zefir.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp

// та же пружина, что у animateItem по умолчанию: отступ и сдвиг элемента едут синхронно,
// поэтому если отступ вырос, а элемент поднялся на столько же, карточка стоит на месте
val DragGapSpring = spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = 0.1.dp)

// LazyColumn держит позицию по ключу первого видимого, при свапе с ним весь список уезжает.
// Прибиваем позицию по индексу.
fun LazyListState.pinScroll() = requestScrollToItem(firstVisibleItemIndex, firstVisibleItemScrollOffset)

// Зазоры списка во время перетаскивания. Остальные элементы держат зазоры как будто
// перетаскиваемого нет, а дырка под ним добирает разницу, чтобы сверху и снизу
// было ровно как надо. Двигается только дырка, соседи не прыгают.
fun <T> dragGaps(items: List<T>, dragged: Int, gap: (List<T>, Int) -> Dp): List<Dp> {
    if (dragged !in items.indices) return items.indices.map { gap(items, it) }
    val rest = items.filterIndexed { i, _ -> i != dragged }
    return items.indices.map { i ->
        when {
            i < dragged -> gap(rest, i)
            i > dragged -> gap(rest, i - 1)
            else -> {
                val top = gap(items, i)
                val bottom = if (i < items.lastIndex) gap(items, i + 1) else 0.dp
                val nextRest = if (i < items.lastIndex) gap(rest, i) else 0.dp
                (top + bottom - nextRest).coerceAtLeast(0.dp)
            }
        }
    }
}

// Автопрокрутка, пока тащим палец у края списка. Чтобы не уносило от лёгкого касания:
// включается только если палец сам сдвинулся к этому краю, после короткой паузы в зоне,
// и скорость растёт плавно (квадрат глубины), а не сразу на максимум.
suspend fun LazyListState.edgeScroll(density: androidx.compose.ui.unit.Density, finger: () -> Float, onScrolled: () -> Unit) {
    val zone = with(density) { 72.dp.toPx() }
    val arm = with(density) { 24.dp.toPx() }
    val maxSpeed = with(density) { 700.dp.toPx() }
    val start = finger()
    var armedUp = false
    var armedDown = false
    var last = androidx.compose.runtime.withFrameNanos { it }
    var held = 0L
    while (true) {
        val now = androidx.compose.runtime.withFrameNanos { it }
        val dt = now - last
        last = now
        val y = finger()
        if (y < start - arm) armedUp = true
        if (y > start + arm) armedDown = true
        val info = layoutInfo
        val top = info.viewportStartOffset + info.beforeContentPadding.toFloat()
        val bottom = info.viewportEndOffset - info.afterContentPadding.toFloat()
        val depth = when {
            armedDown && y > bottom - zone -> ((y - (bottom - zone)) / zone).coerceAtMost(1f)
            armedUp && y < top + zone -> -((top + zone - y) / zone).coerceAtMost(1f)
            else -> 0f
        }
        if (depth == 0f) {
            held = 0
            continue
        }
        held += dt
        if (held < 180_000_000L) continue
        val speed = maxSpeed * depth * kotlin.math.abs(depth)
        val delta = speed * dt / 1_000_000_000f
        if (scrollBy(delta) != 0f) onScrolled()
    }
}

// Общее перетаскивание по списку: подписки и редактор главной.
// Порядок и перестановку отдаёт экран, тут только палец, свапы, автопрокрутка и посадка.
@androidx.compose.runtime.Stable
class ReorderState internal constructor(val list: LazyListState, private val density: androidx.compose.ui.unit.Density) {
    internal var keys: () -> List<String> = { emptyList() }
    internal var canSwap: (Int, Int) -> Boolean = { _, _ -> true }
    internal var move: (Int, Int) -> Unit = { _, _ -> }
    internal var gapOf: (String) -> Dp = { 0.dp }
    internal var onDrop: () -> Unit = {}
    internal var haptic: (dev.rooni.zefir.platform.Haptic) -> Unit = {}
    internal lateinit var scope: kotlinx.coroutines.CoroutineScope

    var dragged by androidx.compose.runtime.mutableStateOf<String?>(null)
        private set
    private var top by androidx.compose.runtime.mutableFloatStateOf(0f)
    internal var grab = 0f
    private var settledOffset: Int? = null
    private val landing = androidx.compose.runtime.mutableStateMapOf<String, Float>()
    private val landingProgress = androidx.compose.runtime.mutableStateMapOf<String, Float>()

    // карточка отцеплена от списка: тащим или она ещё садится на место
    fun isFree(key: String) = dragged == key || key in landing

    private fun rowOf(key: String) = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }

    fun start(key: String, grabAt: Float? = null) {
        val row = rowOf(key)
        landing.remove(key)
        landingProgress.remove(key)
        dragged = key
        top = (row?.offset?.toFloat() ?: 0f) + with(density) { gapOf(key).toPx() }
        grab = grabAt ?: (row?.size ?: 0) / 2f
        settledOffset = null
        haptic(dev.rooni.zefir.platform.Haptic.Toggle)
    }

    fun drag(dy: Float) {
        top += dy
        swapIfNeeded()
    }

    internal fun swapIfNeeded() {
        val key = dragged ?: return
        val current = rowOf(key) ?: return
        // ждём, пока список переложится после прошлого свапа
        if (settledOffset == current.offset) return
        settledOffset = null
        val ids = keys()
        val from = ids.indexOf(key)
        if (from < 0) return
        val center = top + current.size / 2
        val target = list.layoutInfo.visibleItemsInfo.firstOrNull {
            it.key != key && it.key in ids && center > it.offset + it.size * 0.2f && center < it.offset + it.size * 0.8f
        } ?: return
        val to = ids.indexOf(target.key)
        if (to < 0 || !canSwap(from, to)) return
        settledOffset = current.offset
        list.pinScroll()
        move(from, to)
        haptic(dev.rooni.zefir.platform.Haptic.Tick)
    }

    fun drop() {
        val key = dragged ?: return
        landing[key] = top
        landingProgress[key] = 0f
        dragged = null
        settledOffset = null
        onDrop()
        // садимся к живому месту (смещение + текущий зазор), а не к посчитанному при отпускании
        scope.launch {
            Animatable(0f).animateTo(1f, spring(1f, 380f)) { landingProgress[key] = value }
            landing.remove(key)
            landingProgress.remove(key)
        }
    }

    // сдвиг для graphicsLayer: где карточка должна быть минус где её положил список
    fun translation(key: String, gapPx: Float): Float {
        val row = rowOf(key) ?: return 0f
        val rest = row.offset + gapPx
        val y = if (dragged == key) top else landing[key]?.let { from -> from + (rest - from) * (landingProgress[key] ?: 0f) } ?: return 0f
        return y - rest
    }

    internal fun finger() = top + grab
}

@androidx.compose.runtime.Composable
fun rememberReorderState(
    list: LazyListState,
    keys: () -> List<String>,
    gapOf: (String) -> Dp,
    haptic: (dev.rooni.zefir.platform.Haptic) -> Unit,
    canSwap: (Int, Int) -> Boolean = { _, _ -> true },
    onDrop: () -> Unit = {},
    move: (Int, Int) -> Unit,
): ReorderState {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val state = androidx.compose.runtime.remember(list, density) { ReorderState(list, density) }
    state.keys = keys
    state.gapOf = gapOf
    state.haptic = haptic
    state.canSwap = canSwap
    state.onDrop = onDrop
    state.move = move
    state.scope = scope
    androidx.compose.runtime.LaunchedEffect(state, state.dragged) {
        if (state.dragged != null) list.edgeScroll(density, state::finger) { state.swapIfNeeded() }
    }
    return state
}

// зажал и тащи (подписки, строка виджета целиком)
fun androidx.compose.ui.Modifier.reorderAfterLongPress(state: ReorderState, key: String) = pointerInput(key) {
    detectDragGesturesAfterLongPress(
        onDragStart = { at -> state.start(key, at.y) },
        onDragEnd = state::drop,
        onDragCancel = state::drop,
        onDrag = { change, amount ->
            change.consume()
            state.drag(amount.y)
        },
    )
}

// сразу тащи за ручку
fun androidx.compose.ui.Modifier.reorderHandle(state: ReorderState, key: String) = pointerInput(key) {
    detectDragGestures(
        onDragStart = { state.start(key) },
        onDragEnd = state::drop,
        onDragCancel = state::drop,
        onDrag = { change, amount ->
            change.consume()
            state.drag(amount.y)
        },
    )
}

package dev.rooni.zefir.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.backhandler.PredictiveBackHandler
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.rooni.zefir.platform.Haptic
import dev.rooni.zefir.platform.Platform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class MenuEntry(val label: String, val icon: ImageVector? = null, val checked: Boolean = false, val endsSection: Boolean = false, val onClick: () -> Unit)

const val HOLD_MILLIS = 160L

private var claimedDown: Triple<androidx.compose.ui.input.pointer.PointerId, Long, Offset>? = null
private val MenuEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

@Composable
private fun MenuItem(entry: MenuEntry, shape: Shape, hovered: Boolean, finger: () -> Offset?, onPlaced: (Rect) -> Unit, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val content = if (entry.checked) scheme.onSecondaryContainer else scheme.onSurface
    val iconTint = if (entry.checked) scheme.onSecondaryContainer else scheme.onSurfaceVariant
    val source = remember { MutableInteractionSource() }
    var bounds by remember { mutableStateOf(Rect.Zero) }
    var press by remember { mutableStateOf<PressInteraction.Press?>(null) }
    LaunchedEffect(hovered) {
        if (hovered) {
            val start = finger()?.minus(bounds.topLeft) ?: bounds.size.center
            PressInteraction.Press(start).let {
                press = it
                source.emit(it)
            }
        } else {
            press?.let { source.emit(PressInteraction.Release(it)) }
            press = null
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .onGloballyPositioned { coordinates ->
                bounds = Rect(coordinates.localToScreen(Offset.Zero), coordinates.size.toSize())
                onPlaced(bounds)
            }
            .clip(shape)
            .background(if (entry.checked) scheme.secondaryContainer else Color.Transparent)
            .clickable(source, ripple(), onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        entry.icon?.let { Icon(it, null, Modifier.size(22.dp), tint = iconTint) }
        Text(entry.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = content, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (entry.checked) Icon(Icons.Rounded.Check, null, Modifier.size(20.dp), tint = content)
    }
}

private class MenuPosition(private val margin: Int, private val onOrigin: (TransformOrigin) -> Unit) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val toLeft = anchorBounds.center.x > windowSize.width / 2
        val x = if (toLeft) anchorBounds.right - popupContentSize.width else anchorBounds.left
        val up = anchorBounds.bottom + popupContentSize.height > windowSize.height - margin
        val y = if (up) anchorBounds.top - popupContentSize.height else anchorBounds.bottom
        onOrigin(TransformOrigin(if (toLeft) 1f else 0f, if (up) 1f else 0f))
        return IntOffset(
            x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            y.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)),
        )
    }
}

private object FullScreenPosition : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) = IntOffset.Zero
}

@Composable
fun AppMenu(open: Boolean, onDismiss: () -> Unit, entries: List<MenuEntry>, hovered: Int? = null, finger: () -> Offset? = { null }, onPlaced: (Int, Rect) -> Unit = { _, _ -> }, extra: (@Composable ColumnScope.() -> Unit)? = null) {
    val platform = LocalPlatform.current
    val visible = remember { MutableTransitionState(false) }
    visible.targetState = open
    if (!visible.currentState && !visible.targetState) return

    var shownEntries by remember { mutableStateOf(entries) }
    if (open) shownEntries = entries
    var origin by remember { mutableStateOf(TransformOrigin(1f, 0f)) }
    val margin = with(LocalDensity.current) { 16.dp.roundToPx() }
    val position = remember(margin) { MenuPosition(margin) { origin = it } }
    val scope = rememberCoroutineScope()
    val back = remember { Animatable(0f) }

    PredictiveBackHandler(enabled = open) { events ->
        try {
            events.collect { back.snapTo(it.progress) }
            onDismiss()
        } catch (e: CancellationException) {
            scope.launch { back.animateTo(0f, tween(180, easing = MenuEasing)) }
            throw e
        }
    }

    if (open) {
        Popup(popupPositionProvider = FullScreenPosition, properties = PopupProperties(focusable = false)) {
            Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss))
        }
    }
    Popup(popupPositionProvider = position, onDismissRequest = onDismiss, properties = PopupProperties(focusable = false, dismissOnClickOutside = false)) {
        val transition = rememberTransition(visible, "menu")
        val shown by transition.animateFloat({ tween(if (targetState) 220 else 130, easing = MenuEasing) }, label = "menuShown") { if (it) 1f else 0f }

        val sections = buildList<List<IndexedValue<MenuEntry>>> {
            var open = mutableListOf<IndexedValue<MenuEntry>>()
            shownEntries.withIndex().forEach { item ->
                open += item
                if (item.value.endsSection) {
                    add(open)
                    open = mutableListOf()
                }
            }
            if (open.isNotEmpty()) add(open)
        }
        Column(
            Modifier.padding(8.dp).widthIn(min = 180.dp, max = 320.dp).width(IntrinsicSize.Max).graphicsLayer {
                alpha = (shown * 2f).coerceAtMost(1f)
                scaleX = (0.85f + 0.15f * shown) * (1f - 0.12f * back.value)
                scaleY = (0.85f + 0.15f * shown) * (1f - 0.12f * back.value)
                transformOrigin = origin
            },
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            val lastSection = sections.lastIndex + if (extra != null) 1 else 0
            sections.forEachIndexed { sectionIndex, section ->
                val outerTop = if (sectionIndex == 0) 24.dp else 10.dp
                val outerBottom = if (sectionIndex == lastSection) 24.dp else 10.dp
                Surface(
                    shape = positionShape(sectionIndex, lastSection, 24.dp, 10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shadowElevation = 6.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        Modifier.padding(4.dp).heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        section.forEachIndexed { place, (index, entry) ->
                            val top = if (place == 0) outerTop - 4.dp else 6.dp
                            val bottom = if (place == section.lastIndex) outerBottom - 4.dp else 6.dp
                            MenuItem(entry, RoundedCornerShape(top, top, bottom, bottom), hovered == index, finger, { onPlaced(index, it) }) {
                                platform.haptic(Haptic.Tick)
                                onDismiss()
                                entry.onClick()
                            }
                        }
                    }
                }
            }
            if (extra != null) {
                Surface(
                    shape = positionShape(lastSection, lastSection, 24.dp, 10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shadowElevation = 6.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp), content = extra)
                }
            }
        }
    }
}

@Stable
class MenuState {
    var open by mutableStateOf(false)
        private set
    var used by mutableStateOf(false)
        private set
    var hovered by mutableStateOf<Int?>(null)
    var finger: Offset? = null
    val bounds = mutableMapOf<Int, Rect>()
    var entries: List<MenuEntry> = emptyList()
    var anchor: LayoutCoordinates? = null

    fun show() {
        bounds.clear()
        used = true
        open = true
    }

    fun dismiss() {
        open = false
        hovered = null
    }
}

@Composable
fun rememberMenuState() = remember { MenuState() }

fun Modifier.menuGesture(state: MenuState, platform: Platform, holdMillis: Long = HOLD_MILLIS, tapOpens: Boolean = false, source: MutableInteractionSource? = null): Modifier =
    onGloballyPositioned { state.anchor = it }.pointerInput(state) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val stamp = Triple(down.id, down.uptimeMillis, down.position + (state.anchor?.let { if (it.isAttached) it.positionInRoot() else null } ?: Offset.Zero))
            if (tapOpens) {
                claimedDown = stamp
            } else if (claimedDown == stamp) {
                claimedDown = null
                return@awaitEachGesture
            }
            val press = PressInteraction.Press(down.position)
            source?.tryEmit(press)

            val early = withTimeoutOrNull(holdMillis) {
                var result: Boolean? = null
                while (result == null) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                    when {
                        change == null -> result = false
                        !change.pressed -> {
                            if (tapOpens) change.consume()
                            result = true
                        }
                        change.isConsumed -> result = false
                        (change.position - down.position).getDistance() > viewConfiguration.touchSlop -> result = false
                    }
                }
                result
            }
            source?.tryEmit(if (early == false) PressInteraction.Cancel(press) else PressInteraction.Release(press))
            if (early == false || (early == true && !tapOpens)) return@awaitEachGesture
            platform.haptic(Haptic.Tick)
            state.show()
            if (early == true) return@awaitEachGesture
            var moved = false
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                change.consume()
                if (!change.pressed) {
                    val target = state.hovered?.let { state.entries.getOrNull(it) }
                    if (target != null) {
                        platform.haptic(Haptic.Toggle)
                        state.dismiss()
                        target.onClick()
                    } else if (moved) {
                        state.dismiss()
                    }
                    break
                }
                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                val finger = state.anchor?.takeIf { it.isAttached }?.localToScreen(change.position) ?: continue
                state.finger = finger
                val found = state.bounds.entries.firstOrNull { it.value.contains(finger) }?.key
                if (found != state.hovered) {
                    state.hovered = found
                    if (found != null) platform.haptic(Haptic.Tick)
                }
            }
            state.hovered = null
        }
    }

@Composable
fun MenuForState(state: MenuState, entries: List<MenuEntry>, extra: (@Composable ColumnScope.() -> Unit)? = null) {
    state.entries = entries
    AppMenu(state.open, state::dismiss, entries, state.hovered, { state.finger }, { index, rect -> state.bounds[index] = rect }, extra)
}

@Composable
fun MenuButton(entries: List<MenuEntry>, description: String? = null, icon: ImageVector = Icons.Rounded.MoreVert, highlighted: Boolean = false, extra: (@Composable ColumnScope.() -> Unit)? = null) {
    val platform = LocalPlatform.current
    val state = rememberMenuState()
    val source = remember { MutableInteractionSource() }
    Box {
        Box(
            Modifier
                .size(48.dp)
                .indication(source, ripple(bounded = false, radius = 24.dp))
                .semantics {
                    role = Role.Button
                    description?.let { contentDescription = it }
                    onClick {
                        state.show()
                        true
                    }
                }
                .menuGesture(state, platform, tapOpens = true, source = source)
                .focusable(interactionSource = source)
                .onKeyEvent { event ->
                    val confirm = event.key == androidx.compose.ui.input.key.Key.Enter ||
                        event.key == androidx.compose.ui.input.key.Key.DirectionCenter ||
                        event.key == androidx.compose.ui.input.key.Key.NumPadEnter
                    if (confirm && event.type == androidx.compose.ui.input.key.KeyEventType.KeyUp) {
                        platform.haptic(Haptic.Tick)
                        state.show()
                        true
                    } else {
                        confirm
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            val fill by androidx.compose.animation.animateColorAsState(
                if (highlighted) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent,
                androidx.compose.animation.core.tween(200),
                label = "menuFill",
            )
            val tint by androidx.compose.animation.animateColorAsState(
                if (highlighted) MaterialTheme.colorScheme.onSecondaryContainer else LocalContentColor.current,
                androidx.compose.animation.core.tween(200),
                label = "menuTint",
            )
            Box(Modifier.size(40.dp).clip(androidx.compose.foundation.shape.CircleShape).background(fill), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = tint)
            }
        }
        MenuForState(state, entries, extra)
    }
}

package dev.rooni.zefir.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ArrowOutward
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.isOutOfBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import dev.rooni.zefir.platform.Haptic
import dev.rooni.zefir.platform.Platform
import dev.rooni.zefir.res.Res
import dev.rooni.zefir.res.back
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

val LocalPlatform = staticCompositionLocalOf<Platform> { error("no platform") }

private val RevealEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
val SettleEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

val RevealEnter = fadeIn(tween(220)) + expandVertically(tween(340, easing = SettleEasing), expandFrom = Alignment.Top)
val RevealExit = fadeOut(tween(160)) + shrinkVertically(tween(300, easing = SettleEasing), shrinkTowards = Alignment.Top)

fun Modifier.pressTracking(onChange: (Boolean) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        onChange(true)
        while (true) {
            val change = awaitPointerEvent(PointerEventPass.Final).changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed || change.isConsumed || change.isOutOfBounds(size, extendedTouchPadding)) break
        }
        onChange(false)
    }
}

fun Modifier.instantPress(source: MutableInteractionSource): Modifier = pointerInput(source) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val press = androidx.compose.foundation.interaction.PressInteraction.Press(down.position)
        source.tryEmit(press)
        while (true) {
            val change = awaitPointerEvent(PointerEventPass.Final).changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed || change.isOutOfBounds(size, extendedTouchPadding)) break
            if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
        }
        source.tryEmit(androidx.compose.foundation.interaction.PressInteraction.Release(press))
    }
}

val LocalRowTint = compositionLocalOf<IconTint?> { null }

@Composable
fun GapColumn(modifier: Modifier = Modifier, gap: Dp = 12.dp, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val gapPx = gap.toPx()
        val placeables = measurables.map { it.measure(constraints.copy(minHeight = 0)) }
        val tops = IntArray(placeables.size)
        var y = 0f
        var first = true
        placeables.forEachIndexed { index, placeable ->
            if (placeable.height > 0) {
                if (!first) y += minOf(gapPx, placeable.height.toFloat())
                first = false
            }
            tops[index] = y.toInt()
            y += placeable.height
        }
        val width = constraints.constrainWidth(placeables.maxOfOrNull { it.width } ?: 0)
        layout(width, constraints.constrainHeight(y.toInt())) {
            placeables.forEachIndexed { index, placeable -> placeable.place(0, tops[index]) }
        }
    }
}

suspend fun LazyListState.smoothToTop() {
    if (firstVisibleItemIndex > 12) scrollToItem(12, firstVisibleItemScrollOffset)
    val sizes = layoutInfo.visibleItemsInfo
    if (sizes.isEmpty()) return
    val step = sizes.sumOf { it.size }.toFloat() / sizes.size + layoutInfo.mainAxisItemSpacing
    fun left() = firstVisibleItemIndex * step + firstVisibleItemScrollOffset
    val start = left()
    scroll {
        animate(0f, 1f, animationSpec = tween(380, easing = RevealEasing)) { progress, _ ->
            scrollBy(start * (1f - progress) - left())
        }
    }
}

class MorphShape(private val morph: Morph, private val progress: Float, private val turn: Float = 0f) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = Path()
        var first = true
        morph.forEachCubic(progress) { cubic ->
            if (first) {
                path.moveTo(cubic.anchor0X, cubic.anchor0Y)
                first = false
            }
            path.cubicTo(cubic.control0X, cubic.control0Y, cubic.control1X, cubic.control1Y, cubic.anchor1X, cubic.anchor1Y)
        }
        path.close()
        val matrix = Matrix()
        matrix.translate(size.width / 2, size.height / 2)
        matrix.rotateZ(turn)
        matrix.scale(size.width, size.height)
        matrix.translate(-0.5f, -0.5f)
        path.transform(matrix)
        return Outline.Generic(path)
    }
}

class PolygonShape(polygon: RoundedPolygon) : Shape {
    private val inner = MorphShape(Morph(polygon, polygon), 0f)
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density) =
        inner.createOutline(size, layoutDirection, density)
}

class RevealShape(private val center: Offset, private val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = Path()
        path.addOval(androidx.compose.ui.geometry.Rect(center, radius))
        return Outline.Generic(path)
    }
}

fun positionShape(index: Int, last: Int, outer: Dp = 24.dp, inner: Dp = 6.dp): RoundedCornerShape {
    val top = if (index == 0) outer else inner
    val bottom = if (index == last) outer else inner
    return RoundedCornerShape(top, top, bottom, bottom)
}

@Composable
fun SectionGroup(
    modifier: Modifier = Modifier,
    title: String? = null,
    footer: Boolean = true,
    rows: List<@Composable (Shape) -> Unit>,
) {
    Column(modifier.fillMaxWidth()) {
        if (title != null) GroupTitle(title)
        val tints = LocalIconTints.current
        rows.forEachIndexed { index, row ->
            val shape = positionShape(index, rows.lastIndex)
            Box(if (index > 0) Modifier.gapIfVisible(2.dp) else Modifier) {
                CompositionLocalProvider(LocalRowTint provides tints[index % tints.size], LocalLastRowShape provides shape.takeIf { footer && index == rows.lastIndex }) {
                    row(shape)
                }
            }
        }
    }
}

val LocalLastRowShape = compositionLocalOf<Shape?> { null }

class GroupRow(val key: Any, val visible: Boolean = true, val content: @Composable (Shape) -> Unit)

@Composable
fun LiveSectionGroup(rows: List<GroupRow>, modifier: Modifier = Modifier, title: String? = null) {
    val shown = rows.filter { it.visible }
    Column(modifier.fillMaxWidth()) {
        if (title != null) GroupTitle(title)
        val tints = LocalIconTints.current
        rows.forEachIndexed { index, row ->
            key(row.key) {
                val place = shown.indexOfFirst { it.key == row.key }
                val top by animateDpAsState(if (place <= 0) 24.dp else 6.dp, tween(260, easing = RevealEasing), label = "groupTop")
                val bottom by animateDpAsState(if (place < 0 || place == shown.lastIndex) 24.dp else 6.dp, tween(260, easing = RevealEasing), label = "groupBottom")
                AnimatedVisibility(row.visible, enter = RevealEnter, exit = RevealExit) {
                    Box(if (index > 0) Modifier.gapIfVisible(2.dp) else Modifier) {
                        val shape = RoundedCornerShape(top, top, bottom, bottom)
                        CompositionLocalProvider(LocalRowTint provides tints[index % tints.size], LocalLastRowShape provides shape.takeIf { place == shown.lastIndex }) {
                            row.content(shape)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CardRow(shape: Shape, title: String? = null, spotlight: String? = title, content: @Composable () -> Unit) {
    val body: @Composable () -> Unit = {
        Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            GapColumn(Modifier.fillMaxWidth().padding(16.dp)) {
                if (title != null) Text(title, style = MaterialTheme.typography.titleMedium)
                content()
            }
        }
    }
    if (spotlight != null) SpotlightAnchor(spotlight, shape, body) else body()
}

val LocalPlainField = compositionLocalOf { false }

@Composable
fun FieldRow(shape: Shape, icon: ImageVector? = null, content: @Composable () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val outline by animateColorAsState(if (focused) scheme.primary else Color.Transparent, tween(200), label = "rowOutline")
    val color by animateColorAsState(if (focused) scheme.surfaceContainerHigh else scheme.surfaceContainer, tween(200), label = "rowColor")
    Surface(
        shape = shape,
        color = color,
        modifier = Modifier.fillMaxWidth().border(2.dp, outline, shape).onFocusChanged { focused = it.hasFocus },
    ) {
        Row(
            Modifier.padding(start = if (icon != null) 16.dp else 0.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) Icon(icon, null, tint = LocalRowTint.current?.icon ?: scheme.primary, modifier = Modifier.size(24.dp))
            Box(Modifier.weight(1f)) {
                CompositionLocalProvider(LocalPlainField provides true) { content() }
            }
        }
    }
}

private fun Modifier.gapIfVisible(gap: Dp) = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val top = minOf(gap.roundToPx(), placeable.height)
    layout(placeable.width, placeable.height + top) { placeable.place(0, top) }
}

@Composable
fun GroupTitle(title: String, tight: Boolean = false) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmallEmphasized,
        color = MaterialTheme.colorScheme.primary,
        modifier = if (tight) {
            Modifier.padding(start = 16.dp, top = 12.dp).layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, (placeable.height - 4.dp.roundToPx()).coerceAtLeast(0)) { placeable.place(0, 0) }
            }
        } else {
            Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp)
        },
    )
}

@Composable
fun SettingRow(
    shape: Shape,
    title: String,
    description: String? = null,
    icon: ImageVector? = null,
    value: String? = null,
    enabled: Boolean = true,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    haptic: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    val platform = LocalPlatform.current
    val alpha by animateFloatAsState(if (enabled) 1f else 0.38f, label = "row")
    SpotlightAnchor(title, shape) {
    Surface(
        shape = shape,
        color = color,
        modifier = Modifier.fillMaxWidth().clip(shape).then(
            if (onClick != null) {
                Modifier.clickable(enabled = enabled) {
                    if (haptic) platform.haptic(Haptic.Tick)
                    onClick()
                }
            } else {
                Modifier
            },
        ),
    ) {
        Row(
            Modifier.alpha(alpha).padding(contentPadding).heightIn(min = 32.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            leading?.invoke()
            if (icon != null) {
                Icon(icon, null, tint = LocalRowTint.current?.icon ?: MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            }
            Column(Modifier.weight(1f).animateContentSize()) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                var lastDescription by remember { mutableStateOf(description) }
                if (description != null) lastDescription = description
                AnimatedVisibility(description != null, enter = RevealEnter, exit = RevealExit) {
                    Text(
                        lastDescription.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (value != null) {
                    AnimatedContent(value, label = "value") { text ->
                        Text(
                            text,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
            trailing?.invoke()
        }
    }
    }
}

@Composable
fun SwitchRow(
    shape: Shape,
    title: String,
    description: String?,
    checked: Boolean,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    val platform = LocalPlatform.current
    val change = { value: Boolean ->
        platform.haptic(Haptic.Toggle)
        onChange(value)
    }
    val footer = LocalLastRowShape.current == shape
    val row: @Composable () -> Unit = {
        SettingRow(shape, title, description.takeUnless { footer }, icon, enabled = enabled, onClick = { change(!checked) }, haptic = false) {
            Switch(checked = checked, onCheckedChange = change, enabled = enabled)
        }
    }
    if (!footer) {
        row()
        return
    }
    Column(Modifier.fillMaxWidth()) {
        row()
        var last by remember { mutableStateOf(description) }
        if (description != null) last = description
        AnimatedVisibility(description != null, enter = RevealEnter, exit = RevealExit) {
            Text(
                last.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
            )
        }
    }
}

@Composable
fun SwitchNavRow(shape: Shape, title: String, description: String?, checked: Boolean, icon: ImageVector?, onOpen: () -> Unit, onChange: (Boolean) -> Unit) {
    val platform = LocalPlatform.current
    SettingRow(shape, title, description, icon, onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            VerticalDivider(Modifier.height(32.dp))
            Switch(checked, {
                platform.haptic(Haptic.Toggle)
                onChange(it)
            })
        }
    }
}

@Composable
fun DependsOn(enabled: Boolean, content: @Composable () -> Unit) {
    val alpha by animateFloatAsState(if (enabled) 1f else 0.38f, tween(200), label = "dependsOn")
    val blocker = if (enabled) {
        Modifier
    } else {
        Modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial).changes.forEach {
                        if (it.changedToDown() || it.changedToUp()) it.consume()
                    }
                }
            }
        }
    }
    Box(Modifier.fillMaxWidth().graphicsLayer { this.alpha = alpha }.then(blocker).semantics { if (!enabled) disabled() }) {
        GapColumn(content = content)
    }
}

@Composable
fun MasterSwitch(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val platform = LocalPlatform.current
    val container by animateColorAsState(
        if (checked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        label = "master",
    )
    val content by animateColorAsState(
        if (checked) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        label = "masterText",
    )
    val shape = androidx.compose.foundation.shape.CircleShape
    SpotlightAnchor(title, shape) {
        Surface(
            shape = shape,
            color = container,
            contentColor = content,
            modifier = Modifier.fillMaxWidth().clip(shape).clickable {
                platform.haptic(Haptic.Toggle)
                onChange(!checked)
            },
        ) {
            Row(Modifier.padding(start = 26.dp, end = 20.dp, top = 20.dp, bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMediumEmphasized, modifier = Modifier.weight(1f))
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                    Switch(checked, {
                        platform.haptic(Haptic.Toggle)
                        onChange(it)
                    })
                }
            }
        }
    }
}

@Composable
fun NavRow(shape: Shape, title: String, description: String?, icon: ImageVector?, value: String? = null, onClick: () -> Unit) {
    SettingRow(shape, title, description, icon, value, onClick = onClick) {
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun LinkRow(shape: Shape, title: String, description: String?, icon: ImageVector?, value: String? = null, onClick: () -> Unit) {
    SettingRow(shape, title, description, icon, value, onClick = onClick) {
        Icon(Icons.Rounded.ArrowOutward, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ButtonText(text: String, modifier: Modifier = Modifier, style: androidx.compose.ui.text.TextStyle = LocalTextStyle.current) {
    var box by remember { mutableStateOf(0) }
    var full by remember(text) { mutableStateOf(0) }
    Text(
        text,
        modifier = modifier
            .onSizeChanged { box = it.width }
            .fadeEdges(full > box + 1)
            .basicMarquee(iterations = Int.MAX_VALUE, repeatDelayMillis = 1500),
        maxLines = 1,
        softWrap = false,
        style = style,
        onTextLayout = { full = it.size.width },
    )
}

private val FadeEdge = 12.dp

fun Modifier.fadeEdges(enabled: Boolean): Modifier = if (!enabled) this else this
    .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val edge = (FadeEdge.toPx() / size.width).coerceIn(0f, 0.3f)
        drawRect(
            androidx.compose.ui.graphics.Brush.horizontalGradient(
                0f to Color.Transparent,
                edge to Color.Black,
                1f - edge to Color.Black,
                1f to Color.Transparent,
            ),
            blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
        )
    }

@Composable
fun AppTextField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    maxLines: Int = 1,
    enabled: Boolean = true,
    hint: String? = null,
    modifier: Modifier = Modifier,
    textStyle: androidx.compose.ui.text.TextStyle = LocalTextStyle.current,
    trailing: (@Composable () -> Unit)? = null,
) {
    val plain = LocalPlainField.current
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val corner by animateDpAsState(if (focused) 10.dp else 16.dp, tween(200), label = "fieldCorner")
    val outline by animateColorAsState(if (focused && !plain) MaterialTheme.colorScheme.primary else Color.Transparent, tween(200), label = "fieldOutline")
    val shape = RoundedCornerShape(corner)
    val requester = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    val keyboard = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current)
    LaunchedEffect(focused, keyboard) {
        if (focused && keyboard > 0) {
            kotlinx.coroutines.delay(60)
            requester.bringIntoView()
        }
    }
    val fill = if (plain) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerHighest
    val state = rememberTextFieldState(value)
    val latest by rememberUpdatedState(value)
    val notify by rememberUpdatedState(onChange)
    LaunchedEffect(value) {
        if (state.text.toString() != value) state.setTextAndPlaceCursorAtEnd(value)
    }
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }.collect { text ->
            if (text == latest) return@collect
            notify(text)
            withFrameNanos { }
            if (state.text.toString() != latest) state.setTextAndPlaceCursorAtEnd(latest)
        }
    }
    Column(Modifier.fillMaxWidth()) {
        TextField(
            state,
            modifier = modifier.fillMaxWidth().bringIntoViewRequester(requester).border(2.dp, outline, shape),
            enabled = enabled,
            label = { Text(label) },
            lineLimits = if (maxLines == 1) TextFieldLineLimits.SingleLine else TextFieldLineLimits.MultiLine(1, maxLines),
            trailingIcon = trailing,
            textStyle = textStyle,
            shape = shape,
            interactionSource = source,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = fill,
                unfocusedContainerColor = fill,
                disabledContainerColor = if (plain) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
            ),
        )
        if (hint != null) {
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = if (plain) 0.dp else 6.dp, bottom = if (plain) 8.dp else 0.dp),
            )
        }
    }
}

@Composable
fun <T> ChoiceButtons(options: List<Pair<T, String>>, selected: T, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    val platform = LocalPlatform.current
    MaterialExpressiveTheme(motionScheme = CalmMotion) {
      CompositionLocalProvider(LocalRippleConfiguration provides null) {
      Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        options.forEachIndexed { index, (value, label) ->
            val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            val pressed by source.collectIsPressedAsState()
            val grow by animateFloatAsState(if (pressed) 1.18f else 1f, spring(0.55f, 600f), label = "choiceGrow")
            ToggleButton(
                checked = value == selected,
                onCheckedChange = {
                    platform.haptic(Haptic.Tick)
                    onSelect(value)
                },
                interactionSource = source,
                modifier = Modifier.weight(grow).height(52.dp).instantPress(source),
                contentPadding = PaddingValues(horizontal = 8.dp),

                colors = ToggleButtonDefaults.toggleButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    checkedContainerColor = MaterialTheme.colorScheme.primary,
                    checkedContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
            ) {
                ButtonText(label, style = MaterialTheme.typography.labelLarge)
            }
        }
      }
      }
    }
}

@Composable
fun DialogButtons(secondary: String, primary: String, onSecondary: () -> Unit, onPrimary: () -> Unit, modifier: Modifier = Modifier, outer: Dp = 28.dp, height: Dp = 56.dp) {
    val inner = 6.dp
    val text = MaterialTheme.typography.titleMedium
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(
            onClick = onSecondary,
            modifier = Modifier.weight(1f).height(height),
            shape = RoundedCornerShape(outer, inner, inner, outer),
        ) { ButtonText(secondary, style = text) }
        Button(
            onClick = onPrimary,
            modifier = Modifier.weight(1f).height(height),
            shape = RoundedCornerShape(inner, outer, outer, inner),
        ) { ButtonText(primary, style = text) }
    }
}

@Composable
fun MessageBanner(text: String?, modifier: Modifier = Modifier, icon: ImageVector = Icons.Rounded.ErrorOutline) {
    var last by remember { mutableStateOf(text.orEmpty()) }
    if (text != null) last = text
    AnimatedVisibility(
        text != null,
        modifier = modifier.fillMaxWidth(),
        enter = RevealEnter,
        exit = RevealExit,
    ) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(icon, null, Modifier.size(22.dp))
                Text(last, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private val DIALOG_CORNER = 32.dp
private val DIALOG_INSET = 10.dp

@Composable
fun SheetHandle() {
    Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(width = 32.dp, height = 4.dp).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), CircleShape))
    }
}

@Composable
fun AppSheet(
    title: String,
    onDismiss: () -> Unit,
    canScrollUp: () -> Boolean,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    description: String? = null,
    content: @Composable () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        dragHandle = null,
    ) {
        SheetHandle()
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleLargeEmphasized)
            if (description != null) Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val raised by remember { derivedStateOf { canScrollUp() } }
        val shade by animateFloatAsState(if (raised) 1f else 0f, tween(160), label = "sheetShade")
        Box(Modifier.weight(1f, fill = false)) {
            content()
            Box(
                Modifier.fillMaxWidth().height(8.dp).graphicsLayer { alpha = shade }
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.18f), Color.Transparent))),
            )
        }
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.ime.exclude(WindowInsets.navigationBars)))
    }
}

@Composable
fun AppDialog(
    title: String,
    text: String,
    secondary: String,
    primary: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    icon: ImageVector? = null,
    warning: Boolean = false,
) {
    val host = LocalDialogHost.current
    val dismiss by rememberUpdatedState(onDismiss)
    val confirm by rememberUpdatedState(onConfirm)
    DisposableEffect(host) {
        val entry = DialogEntry({ dismiss() }) {
            Column {
                Column(
                    Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = if (icon != null) Alignment.CenterHorizontally else Alignment.Start,
                ) {
                    if (icon != null) {
                        val scheme = MaterialTheme.colorScheme
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(bottom = 4.dp).size(64.dp)) {
                            Surface(
                                color = if (warning) scheme.errorContainer else scheme.secondaryContainer,
                                shape = remember { PolygonShape(MaterialShapes.Cookie9Sided) },
                                modifier = Modifier.fillMaxSize(),
                            ) {}
                            Icon(icon, null, tint = if (warning) scheme.onErrorContainer else scheme.onSecondaryContainer, modifier = Modifier.size(30.dp))
                        }
                    }
                    Text(title, style = MaterialTheme.typography.headlineSmallEmphasized, textAlign = if (icon != null) TextAlign.Center else TextAlign.Start)
                    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = if (icon != null) TextAlign.Center else TextAlign.Start)
                }

                DialogButtons(secondary, primary, { dismiss() }, { confirm() }, Modifier.padding(DIALOG_INSET), outer = DIALOG_CORNER - DIALOG_INSET, height = 64.dp)
            }
        }
        host.entries += entry
        onDispose { entry.visible = false }
    }
}

class DialogEntry(val onDismiss: () -> Unit, val content: @Composable () -> Unit) {
    var visible by mutableStateOf(true)
}

@Stable
class DialogHost {
    val entries = mutableStateListOf<DialogEntry>()
}

val LocalDialogHost = staticCompositionLocalOf { DialogHost() }

@Composable
fun DialogLayer(host: DialogHost) {
    host.entries.toList().forEach { entry -> key(entry) { DialogFrame(entry) { host.entries -= entry } } }
}

@Composable
private fun DialogFrame(entry: DialogEntry, onGone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val state = remember { MutableTransitionState(false) }
    state.targetState = entry.visible
    if (state.isIdle && !state.currentState && !entry.visible) SideEffect(onGone)
    val transition = rememberTransition(state, "dialog")
    val shown by transition.animateFloat({ tween(if (targetState) 260 else 160, easing = RevealEasing) }, label = "dialogShown") { if (it) 1f else 0f }
    val back = remember { Animatable(0f) }
    androidx.compose.ui.backhandler.PredictiveBackHandler(enabled = entry.visible) { events ->
        try {
            events.collect { back.snapTo(it.progress) }
            entry.onDismiss()
        } catch (e: kotlinx.coroutines.CancellationException) {
            scope.launch { back.animateTo(0f, tween(180, easing = RevealEasing)) }
            throw e
        }
    }
    val noRipple = remember { MutableInteractionSource() }
    Box(
        Modifier.fillMaxSize().graphicsLayer { alpha = shown * (1f - 0.5f * back.value) }.background(Color.Black.copy(alpha = 0.42f))
            .clickable(noRipple, null, onClick = { if (entry.visible) entry.onDismiss() }),
    )
    Box(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp), contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(DIALOG_CORNER),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.widthIn(min = 280.dp, max = 480.dp).graphicsLayer {
                alpha = shown
                val scale = (0.9f + 0.1f * shown) * (1f - 0.1f * back.value)
                scaleX = scale
                scaleY = scale
            }.clickable(noRipple, null) {},
        ) { entry.content() }
    }
}

@Composable
fun SubScreen(
    title: String,
    onBack: (() -> Unit)?,
    collapsing: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
    belowBar: (@Composable () -> Unit)? = null,
    titleContent: (@Composable () -> Unit)? = null,
    solidBar: Boolean = false,
    barReset: Int = 0,
    forceCollapsed: Boolean = false,
    content: @Composable (PaddingValues) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val small = !collapsing || maxHeight < 480.dp
        val state = rememberTopAppBarState()
        val behavior = if (small) {
            TopAppBarDefaults.pinnedScrollBehavior(state)
        } else {
            TopAppBarDefaults.exitUntilCollapsedScrollBehavior(state)
        }
        val raised by remember { derivedStateOf { state.overlappedFraction > 0.01f || (!small && state.collapsedFraction > 0.98f) } }
        LaunchedEffect(barReset) {
            if (barReset > 0) {
                animate(state.heightOffset, 0f, animationSpec = tween(380, easing = RevealEasing)) { value, _ -> state.heightOffset = value }
                state.contentOffset = 0f
            }
        }

        var restoreTo by remember { mutableStateOf<Float?>(null) }
        LaunchedEffect(forceCollapsed) {
            if (forceCollapsed) {
                restoreTo = state.heightOffset
                animate(state.heightOffset, state.heightOffsetLimit, animationSpec = tween(320, easing = RevealEasing)) { value, _ -> state.heightOffset = value }
            } else {
                restoreTo?.let { target ->
                    animate(state.heightOffset, target, animationSpec = tween(320, easing = RevealEasing)) { value, _ -> state.heightOffset = value }
                }
                restoreTo = null
            }
        }
        val shadowSize by animateDpAsState(if (raised || solidBar) 8.dp else 0.dp, label = "barShadow")
        val barColor by animateColorAsState(
            if (raised || solidBar) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surface,
            label = "barColor",
        )
        val colors = TopAppBarDefaults.topAppBarColors(containerColor = barColor, scrolledContainerColor = barColor)
        CaptionColor { barColor }
        val navIcon: @Composable () -> Unit = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(Res.string.back))
                }
            }
        }
        Scaffold(
            modifier = Modifier.nestedScroll(behavior.nestedScrollConnection),
            containerColor = MaterialTheme.colorScheme.surface,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                Surface(color = barColor, shadowElevation = shadowSize) {
                    Column {
                        if (small) {
                            TopAppBar(
                                title = {
                                    AnimatedContent(titleContent != null, label = "barTitle", transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) }) { custom ->
                                        if (custom) titleContent?.invoke() else ButtonText(title)
                                    }
                                },
                                navigationIcon = navIcon,
                                actions = actions,
                                scrollBehavior = behavior,
                                colors = colors,
                            )
                        } else {
                            LargeFlexibleTopAppBar(
                                title = { Text(title, maxLines = 2) },
                                navigationIcon = navIcon,
                                actions = actions,
                                scrollBehavior = behavior,
                                colors = colors,
                            )
                        }
                        belowBar?.invoke()
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.widthIn(max = ContentMaxWidth).fillMaxSize()) { content(padding) }
            }
        }
    }
}

val ContentMaxWidth = 720.dp

@Composable
fun Modifier.edgeFade(scroll: androidx.compose.foundation.ScrollState, width: Dp = 24.dp): Modifier {
    val start by animateFloatAsState(if (scroll.canScrollBackward) 1f else 0f, tween(if (scroll.canScrollBackward) 40 else 180), label = "fadeStart")
    val end by animateFloatAsState(if (scroll.canScrollForward) 1f else 0f, tween(if (scroll.canScrollForward) 40 else 180), label = "fadeEnd")
    return this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val edge = width.toPx()
            if (start > 0f) {
                drawRect(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 1f - start), Color.Black), 0f, edge), blendMode = BlendMode.DstIn)
            }
            if (end > 0f) {
                drawRect(Brush.horizontalGradient(listOf(Color.Black, Color.Black.copy(alpha = 1f - end)), size.width - edge, size.width), blendMode = BlendMode.DstIn)
            }
        }
}

@Composable
fun ScrollColumn(modifier: Modifier = Modifier, scroll: androidx.compose.foundation.ScrollState = rememberScrollState(), content: @Composable () -> Unit) {
    GapColumn(modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp).navigationBarsPadding().padding(bottom = LocalNavBarSpace.current), content = content)
}

private fun cyrillicUnits() = java.util.Locale.getDefault().language in setOf("ru", "uk", "be")

fun formatBytes(bytes: Long): String {
    val cyrillic = cyrillicUnits()
    return when {
        bytes >= 1L shl 40 -> "%.1f %s".format(bytes / (1L shl 40).toDouble(), if (cyrillic) "ТБ" else "TB")
        bytes >= 1L shl 30 -> "%.1f %s".format(bytes / (1L shl 30).toDouble(), if (cyrillic) "ГБ" else "GB")
        bytes >= 1L shl 20 -> "%.1f %s".format(bytes / (1L shl 20).toDouble(), if (cyrillic) "МБ" else "MB")
        bytes >= 1L shl 10 -> "${bytes shr 10} ${if (cyrillic) "КБ" else "KB"}"
        else -> "$bytes ${if (cyrillic) "Б" else "B"}"
    }
}

fun formatSpeed(bytes: Long) = formatBytes(bytes) + if (cyrillicUnits()) "/с" else "/s"

fun formatDate(epochSeconds: Long): String {
    val date = java.util.Date(epochSeconds * 1000)
    return java.text.SimpleDateFormat("d MMMM yyyy", java.util.Locale.getDefault()).format(date)
}

package dev.rooni.zefir.ui.screens

import androidx.compose.ui.unit.Dp
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import dev.rooni.zefir.app.AUTO_GROUP
import dev.rooni.zefir.app.AppController
import dev.rooni.zefir.model.*
import dev.rooni.zefir.platform.Haptic
import dev.rooni.zefir.platform.nowMillis
import dev.rooni.zefir.res.*
import dev.rooni.zefir.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import org.jetbrains.compose.resources.stringResource

private val LocalWindowHeight = compositionLocalOf { 800.dp }

@Composable
fun HomeScreen(controller: AppController, nav: Navigator, wide: Boolean) {
    BoxWithConstraints {
        CompositionLocalProvider(LocalWindowHeight provides maxHeight) { HomeContent(controller, nav, wide) }
    }
}

@Composable
private fun HomeContent(controller: AppController, nav: Navigator, wide: Boolean) {
    val settings by controller.settings.collectAsState()
    val tunnel by controller.tunnel.collectAsState()
    val profiles by controller.profiles.collectAsState()
    val hasProfile = profiles.any { it.id == settings.activeProfile }

    val header: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
            FilledTonalIconButton(onClick = {
                controller.platform.haptic(Haptic.Tick)
                nav.push(Route.Widgets)
            }) {
                Icon(Icons.Rounded.Edit, stringResource(Res.string.home_edit))
            }
        }
    }

    if (wide) {
        val surface = MaterialTheme.colorScheme.surface
        CaptionColor(nav.stack.size == 1 && nav.tab == Tab.Home) { surface }
        Row(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                ConnectHero(tunnel, hasProfile, controller, nav.stack.size == 1 && nav.tab == Tab.Home) { if (hasProfile) controller.pressToggle() else nav.push(Route.Add) }
            }

            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(top = 48.dp),
                    verticalArrangement = Arrangement.spacedBy(WidgetGap, Alignment.CenterVertically),
                ) {
                    Widgets(controller, nav, settings)
                }
                Box(Modifier.align(Alignment.TopEnd)) { header() }
            }
        }
    } else {
        val height = LocalWindowHeight.current
        val scroll = rememberScrollState()
        ReselectEffect(nav, Tab.Home) { scroll.animateScrollTo(0) }
        val barColor = MaterialTheme.colorScheme.surfaceContainer
        val fadeDistance = with(androidx.compose.ui.platform.LocalDensity.current) { 48.dp.toPx() }
        val homeShown = nav.stack.size == 1 && nav.tab == Tab.Home
        val surface = MaterialTheme.colorScheme.surface
        CaptionColor(homeShown) { androidx.compose.ui.graphics.lerp(surface, barColor, (scroll.value / fadeDistance).coerceIn(0f, 1f)) }
        val shadowHeight = with(androidx.compose.ui.platform.LocalDensity.current) { 6.dp.toPx() }
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(scroll).heightIn(min = height).statusBarsPadding().padding(top = 56.dp, bottom = 16.dp + LocalNavBarSpace.current),
                horizontalAlignment = Alignment.CenterHorizontally,

                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Spacer(Modifier.height(0.dp))
                ConnectHero(tunnel, hasProfile, controller, nav.stack.size == 1 && nav.tab == Tab.Home) { if (hasProfile) controller.pressToggle() else nav.push(Route.Add) }
                Column(
                    Modifier.widthIn(max = ContentMaxWidth).padding(horizontal = 16.dp).padding(top = 12.dp).animateContentSize(),
                    verticalArrangement = Arrangement.spacedBy(WidgetGap),
                ) {
                    Widgets(controller, nav, settings)
                }
            }

            Box(
                Modifier.fillMaxWidth().align(Alignment.TopCenter).drawBehind {
                    val shown = (scroll.value / fadeDistance).coerceIn(0f, 1f)
                    if (shown > 0f) {
                        drawRect(barColor, alpha = shown)
                        drawRect(
                            Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.2f), Color.Transparent), size.height, size.height + shadowHeight),
                            Offset(0f, size.height),
                            Size(size.width, shadowHeight),
                            alpha = shown,
                        )
                    }
                },
            ) {
                Box(Modifier.statusBarsPadding().padding(vertical = 4.dp)) { header() }
            }
        }
    }
}

private val HeroPath = listOf(MaterialShapes.Circle, HeroTriangle, MaterialShapes.Cookie9Sided, MaterialShapes.Square, MaterialShapes.Circle)
private const val HERO_OFF = 0f
private const val HERO_ON = 2f
private const val HERO_OFF_AGAIN = 4f
private const val HERO_STEP_MILLIS = 340f
private const val HERO_STEP_DEGREES = 90f

private const val HERO_SYMMETRY = 40f

private const val SPIN_IDLE = 15f
private const val SPIN_BUSY = 110f
private const val SPIN_START = 120f
private const val SPIN_LOW_BYTES = 32 * 1024f
private const val SPIN_HIGH_BYTES = 8 * 1024 * 1024f

private fun spinRate(speed: Traffic): Float {
    val total = (speed.up + speed.down).toFloat().coerceAtLeast(SPIN_LOW_BYTES)
    val part = log10(total / SPIN_LOW_BYTES) / log10(SPIN_HIGH_BYTES / SPIN_LOW_BYTES)
    return SPIN_IDLE + (SPIN_BUSY - SPIN_IDLE) * part.coerceIn(0f, 1f)
}

private const val BUSY_DELAY = 350L
private const val BUSY_MIN_SHOWN = 600L

@Composable
private fun ConnectHero(state: TunnelState, hasProfile: Boolean, controller: AppController, visible: Boolean, onClick: () -> Unit) {
    val heroSize = minOf(LocalWindowHeight.current * 0.28f, 240.dp).coerceAtLeast(130.dp)
    val on = state == TunnelState.On
    val working = state == TunnelState.Starting || state == TunnelState.Stopping

    var busy by remember { mutableStateOf(false) }
    var busySince by remember { mutableStateOf(0L) }
    LaunchedEffect(working) {
        if (working) {
            delay(BUSY_DELAY)
            busySince = nowMillis()
            busy = true
        } else if (busy) {
            delay((BUSY_MIN_SHOWN - (nowMillis() - busySince)).coerceAtLeast(0))
            busy = false
        }
    }

    var settled by remember { mutableStateOf(state) }
    if (!working) settled = state
    val shownState = if (busy && working) state else settled
    val morphs = remember { HeroPath.zipWithNext { from, to -> Morph(from, to) } }
    val catMorphs = remember { listOf(Morph(MaterialShapes.Circle, CatPolygon), Morph(MaterialShapes.Cookie9Sided, CatPolygon)) }
    val live = on || state == TunnelState.Starting
    val position = remember { Animatable(if (live) HERO_ON else HERO_OFF) }

    val cat = remember { Animatable(0f) }
    var catShown by remember { mutableStateOf(false) }
    var catFromOn by remember { mutableStateOf(false) }
    LaunchedEffect(catShown) { cat.animateTo(if (catShown) 1f else 0f, tween(380, easing = FastOutSlowInEasing)) }
    LaunchedEffect(live) {
        snapshotFlow { catShown || cat.value > 0f }.first { !it }
        if (live && position.value == HERO_OFF_AGAIN) position.snapTo(HERO_OFF)
        val target = if (live) HERO_ON else if (position.value >= HERO_ON) HERO_OFF_AGAIN else HERO_OFF
        while (position.value != target) {
            val now = position.value
            val next = if (target > now) floor(now) + 1f else ceil(now) - 1f
            position.animateTo(next, tween((HERO_STEP_MILLIS * abs(next - now)).toInt().coerceAtLeast(60), easing = FastOutSlowInEasing))
        }
        if (target == HERO_OFF_AGAIN) position.snapTo(HERO_OFF)
    }
    val turn = remember { mutableFloatStateOf(0f) }

    val still = controller.settings.collectAsState().value.noAnimations
    LaunchedEffect(live, on, visible, still) {
        if (live && visible && !still) {
            var rate = if (on) SPIN_IDLE else SPIN_START
            var last = withFrameNanos { it }
            while (true) {
                withFrameNanos { frame ->
                    val seconds = (frame - last) / 1_000_000_000f
                    last = frame
                    val wanted = if (on) spinRate(controller.speed.value) else SPIN_START
                    rate += (wanted - rate) * (seconds * 2f).coerceAtMost(1f)
                    val next = turn.floatValue + rate * seconds
                    turn.floatValue = if (position.value == HERO_ON) next % HERO_SYMMETRY else next
                }
            }
        }
    }
    val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, spring(0.6f, 800f), label = "scale")
    val pathAt = position.value
    val catAt = cat.value
    val shapeNow = remember(pathAt, catAt) {
        if (catAt > 0f) {
            MorphShape(catMorphs[if (catFromOn) 1 else 0], catAt)
        } else {
            val step = floor(pathAt).toInt().coerceAtMost(morphs.lastIndex)
            MorphShape(morphs[step], pathAt - step)
        }
    }

    val container by androidx.compose.animation.animateColorAsState(
        when {
            on -> MaterialTheme.colorScheme.primary
            state is TunnelState.Failed -> MaterialTheme.colorScheme.errorContainer
            else -> MaterialTheme.colorScheme.surfaceContainerHighest
        },
        label = "container",
    )
    val content by androidx.compose.animation.animateColorAsState(
        when {
            on -> MaterialTheme.colorScheme.onPrimary
            state is TunnelState.Failed -> MaterialTheme.colorScheme.onErrorContainer
            else -> MaterialTheme.colorScheme.primary
        },
        label = "content",
    )

    val title = when {
        !hasProfile -> Res.string.home_no_profile
        shownState == TunnelState.Off -> Res.string.home_off
        shownState == TunnelState.Starting -> Res.string.home_starting
        shownState == TunnelState.On -> Res.string.home_on
        shownState == TunnelState.Stopping -> Res.string.home_stopping
        else -> Res.string.home_failed
    }
    val hint = when {
        !hasProfile -> Res.string.servers_add
        shownState == TunnelState.On -> Res.string.home_on_hint
        shownState is TunnelState.Failed -> Res.string.home_failed_hint
        shownState != TunnelState.Off -> null
        else -> Res.string.home_off_hint
    }

    val detailed = controller.settings.collectAsState().value.detailedErrors
    val failure = (shownState as? TunnelState.Failed)?.message
    fun heroLong() {
        val at = position.value
        if (catShown) {
            catShown = false
            controller.platform.haptic(Haptic.Press)
        } else if (at == HERO_OFF || at == HERO_ON) {
            catFromOn = at == HERO_ON
            catShown = true
            controller.platform.haptic(Haptic.Press)
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 8.dp)) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(heroSize)) {
            Surface(
                color = container,
                shape = shapeNow,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale
                    scaleY = scale

                    val at = position.value
                    val starShare = (1f - abs(at - HERO_ON)).coerceIn(0f, 1f)
                    rotationZ = (at * HERO_STEP_DEGREES + turn.floatValue * starShare) * (1f - cat.value)
                }.clip(shapeNow).pressTracking { pressed = it }.onSecondaryClick { heroLong() }.combinedClickable(
                    interactionSource = press,
                    indication = androidx.compose.foundation.LocalIndication.current,
                    onLongClick = { heroLong() },
                    onClick = {
                        controller.platform.haptic(Haptic.Press)
                        catShown = false
                        onClick()
                    },
                ),
            ) {}
            AnimatedContent(busy, modifier = Modifier.graphicsLayer {
                alpha = 1f - cat.value

                val small = 1f - 0.15f * (1f - abs(position.value - 1f)).coerceIn(0f, 1f)
                scaleX = small
                scaleY = small
            }, label = "heroIcon", transitionSpec = { (fadeIn(tween(200)) + scaleIn(tween(240), initialScale = 0.85f)) togetherWith (fadeOut(tween(140)) + scaleOut(tween(180), targetScale = 0.85f)) }) { loading ->
                if (loading) {
                    LoadingIndicator(Modifier.size(96.dp), color = content)
                } else {
                    Icon(Icons.Rounded.PowerSettingsNew, null, tint = content, modifier = Modifier.size(heroSize * 0.36f))
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        AnimatedContent(title, label = "title") { res ->
            Text(
                stringResource(res),
                style = MaterialTheme.typography.headlineMediumEmphasized,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            )
        }
        Text(
            if (failure != null && hasProfile) {
                if (detailed) failure else stringResource(dev.rooni.zefir.app.friendlyError(failure))
            } else {
                hint?.let { stringResource(it) } ?: " "
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
    }
}

private val WidgetGap = 2.dp
private val SectionGap = 8.dp
private val WidgetOuter = 24.dp
private val WidgetInner = 6.dp

@Composable
private fun Widgets(controller: AppController, nav: Navigator, settings: AppSettings) {
    val list = remember(settings.widgets) { tidyWidgets(settings.widgets) }
    var dividers = 0
    list.forEachIndexed { index, widget ->
        if (widget == HomeWidget.Divider) {
            key("Divider#${dividers++}") { Spacer(Modifier.height(SectionGap)) }
            return@forEachIndexed
        }
        key(widget) {
            val first = index == 0 || list[index - 1] == HomeWidget.Divider
            val last = index == list.lastIndex || list[index + 1] == HomeWidget.Divider
            val top by animateDpAsState(if (first) WidgetOuter else WidgetInner, tween(260), label = "widgetTop")
            val bottom by animateDpAsState(if (last) WidgetOuter else WidgetInner, tween(260), label = "widgetBottom")
            val shape: Shape = RoundedCornerShape(top, top, bottom, bottom)
            when (widget) {
                HomeWidget.Server -> ServerWidget(controller, nav, shape)
                HomeWidget.Subscription -> SubscriptionWidget(controller, nav, shape, top, bottom)
                HomeWidget.Mode -> ModeWidget(controller, settings, shape)
                HomeWidget.Speed -> SpeedWidget(controller, top, bottom)
                HomeWidget.Session -> SessionWidget(controller, shape)
                HomeWidget.Traffic -> TrafficWidget(controller, top, bottom)
                HomeWidget.SplitApps -> AppsWidget(settings, nav, shape)
                HomeWidget.ExitIp -> ExitIpWidget(controller, shape)
                HomeWidget.Divider -> Unit
            }
        }
    }
}

@Composable
private fun ServerWidget(controller: AppController, nav: Navigator, shape: Shape) {
    val proxies by controller.proxies.collectAsState()
    val main = proxies.main
    val current = main?.now
    val resolved = proxies.resolve(current)?.let(::parseServerName)
    val label = when {
        main == null -> stringResource(Res.string.w_server_none)
        current == AUTO_GROUP -> stringResource(Res.string.w_server_auto) + (resolved?.label?.takeIf { it != current }?.let { " · $it" } ?: "")
        else -> resolved?.label ?: current.orEmpty()
    }
    SettingRow(
        shape,
        stringResource(Res.string.w_server),
        label,
        onClick = { nav.openServers(0) },

        contentPadding = PaddingValues(start = 12.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        leading = { if (resolved?.flag != null || resolved?.tags?.isNotEmpty() == true) FlagBox(resolved.flag, 44.dp, resolved.tags.firstOrNull()) else Icon(Icons.Rounded.Dns, null, Modifier.size(44.dp).padding(10.dp), tint = MaterialTheme.colorScheme.primary) },
    ) {
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ExitIpWidget(controller: AppController, shape: Shape) {
    val tunnel by controller.tunnel.collectAsState()
    val proxies by controller.proxies.collectAsState()
    var ip by remember { mutableStateOf<dev.rooni.zefir.app.ExitIp?>(null) }
    var loading by remember { mutableStateOf(true) }
    val exit = remember(proxies) { proxies.resolve(proxies.main?.name) }
    LaunchedEffect(tunnel, exit) {
        loading = true
        ip = null
        ip = controller.exitIp()
        loading = false
    }
    val flag = ip?.country?.takeIf { it.length == 2 }?.uppercase()?.let { code ->
        String(Character.toChars(0x1F1E6 + (code[0] - 'A'))) + String(Character.toChars(0x1F1E6 + (code[1] - 'A')))
    }
    SettingRow(
        shape,
        stringResource(Res.string.w_ip),
        ip?.let { listOfNotNull(it.ip, it.city).joinToString(" · ") } ?: stringResource(if (loading) Res.string.w_ip_loading else Res.string.w_ip_none),
        contentPadding = PaddingValues(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        trailing = { if (flag != null) FlagBox(flag, 44.dp) else Icon(Icons.Rounded.Public, null, Modifier.size(44.dp).padding(10.dp), tint = MaterialTheme.colorScheme.primary) },
    )
}

@Composable
private fun SubscriptionWidget(controller: AppController, nav: Navigator, shape: Shape, top: Dp, bottom: Dp) {
    val settings by controller.settings.collectAsState()
    val profiles by controller.profiles.collectAsState()
    val profile = profiles.firstOrNull { it.id == settings.activeProfile }
    if (profile == null) {
        NavRow(shape, stringResource(Res.string.servers_add), stringResource(Res.string.servers_empty_text), Icons.Rounded.Add) {
            nav.push(Route.Add)
        }
        return
    }

    Column(Modifier.fillMaxWidth().clip(shape).clickable { nav.openServers(1) }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Surface(shape = RoundedCornerShape(top, top, WidgetInner, WidgetInner), color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(profile.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1)
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        SubscriptionStats(profile, flat = true, bottom = bottom)
    }
}

@Composable
private fun ModeWidget(controller: AppController, settings: AppSettings, shape: Shape) {
    Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(Res.string.mode_title), style = MaterialTheme.typography.titleMedium)
            ChoiceButtons(
                listOf(
                    RoutingMode.Smart to stringResource(Res.string.mode_smart),
                    RoutingMode.Global to stringResource(Res.string.mode_global),
                ),
                settings.mode,
            ) { mode -> controller.update({ it.copy(mode = mode) }, reapply = true) }
        }
    }
}


@Composable
private fun StatTile(label: String, value: String, shape: Shape, modifier: Modifier = Modifier) {
    Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLargeEmphasized)
        }
    }
}

@Composable
private fun SpeedWidget(controller: AppController, top: Dp, bottom: Dp) {
    val speed by controller.speed.collectAsState()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        StatTile(stringResource(Res.string.w_download), formatSpeed(speed.down), RoundedCornerShape(top, WidgetInner, WidgetInner, bottom), Modifier.weight(1f))
        StatTile(stringResource(Res.string.w_upload), formatSpeed(speed.up), RoundedCornerShape(WidgetInner, top, bottom, WidgetInner), Modifier.weight(1f))
    }
}

@Composable
private fun SessionWidget(controller: AppController, shape: Shape) {
    val since by controller.connectedAt.collectAsState()
    val now by produceState(nowMillis(), since) {
        while (since != null) {
            value = nowMillis()
            delay(1000)
        }
    }
    val time = since?.let { formatDuration((now - it) / 1000) } ?: stringResource(Res.string.w_session_off)
    Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(Res.string.w_session_time),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(time, style = MaterialTheme.typography.titleLargeEmphasized.copy(fontFeatureSettings = "tnum"), maxLines = 1)
        }
    }
}

@Composable
private fun TrafficWidget(controller: AppController, top: Dp, bottom: Dp) {
    val total by controller.total.collectAsState()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        StatTile(stringResource(Res.string.w_download), formatBytes(total.down), RoundedCornerShape(top, WidgetInner, WidgetInner, bottom), Modifier.weight(1f))
        StatTile(stringResource(Res.string.w_upload), formatBytes(total.up), RoundedCornerShape(WidgetInner, top, bottom, WidgetInner), Modifier.weight(1f))
    }
}

private fun formatDuration(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0)
    return "%d:%02d:%02d".format(safe / 3600, safe / 60 % 60, safe % 60)
}

@Composable
private fun AppsWidget(settings: AppSettings, nav: Navigator, shape: Shape) {
    val value = when (settings.splitMode) {
        SplitMode.Off -> stringResource(Res.string.w_apps_off)
        SplitMode.Exclude -> stringResource(Res.string.w_apps_count, settings.excludedApps.size)
        SplitMode.Only -> stringResource(Res.string.w_apps_only, settings.onlyApps.size)
    }
    NavRow(shape, stringResource(Res.string.w_apps), value, Icons.Rounded.Apps) { nav.push(Route.Apps) }
}

fun HomeWidget.title() = when (this) {
    HomeWidget.Server -> Res.string.w_server
    HomeWidget.Subscription -> Res.string.w_subscription
    HomeWidget.Mode -> Res.string.w_mode
    HomeWidget.Speed -> Res.string.w_speed
    HomeWidget.Session -> Res.string.w_session
    HomeWidget.Traffic -> Res.string.w_traffic
    HomeWidget.SplitApps -> Res.string.w_apps
    HomeWidget.ExitIp -> Res.string.w_ip
    HomeWidget.Divider -> Res.string.w_divider
}

fun HomeWidget.description() = when (this) {
    HomeWidget.Server -> Res.string.w_server_desc
    HomeWidget.Subscription -> Res.string.w_subscription_desc
    HomeWidget.Mode -> Res.string.w_mode_desc
    HomeWidget.Speed -> Res.string.w_speed_desc
    HomeWidget.Session -> Res.string.w_session_desc
    HomeWidget.Traffic -> Res.string.w_traffic_desc
    HomeWidget.SplitApps -> Res.string.w_apps_desc
    HomeWidget.ExitIp -> Res.string.w_ip_desc
    HomeWidget.Divider -> Res.string.w_divider
}

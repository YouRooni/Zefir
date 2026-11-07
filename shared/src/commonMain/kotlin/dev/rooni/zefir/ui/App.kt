package dev.rooni.zefir.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuOpen
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.outlined.Dns as OutlinedDns
import androidx.compose.material.icons.outlined.Home as OutlinedHome
import androidx.compose.material.icons.outlined.Settings as OutlinedSettings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.PredictiveBackHandler
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.rooni.zefir.app.AppController
import dev.rooni.zefir.app.text
import dev.rooni.zefir.model.TunnelState
import dev.rooni.zefir.platform.Haptic
import dev.rooni.zefir.res.*
import dev.rooni.zefir.ui.screens.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

sealed interface Route {
    data object Tabs : Route
    data object Add : Route
    data object Basic : Route
    data object AppSettingsScreen : Route
    data object Advanced : Route
    data object Power : Route
    data object Subscriptions : Route
    data object ToolsSettings : Route
    data object IdleOff : Route
    data object SpeedTest : Route
    data object Apps : Route
    data object Rules : Route
    data object Notifications : Route
    data object Widgets : Route
    data object Logs : Route
    data object Help : Route
    data object About : Route
    data object Connections : Route
    data object Diagnostics : Route
    data object DnsLog : Route
    data class SiteCheck(val host: String? = null) : Route
    data object AutoRules : Route
    data class EditProfile(val id: String) : Route
    data class MergeProfiles(val id: String? = null) : Route
    data class ManualServer(val name: String? = null) : Route
}

enum class Tab(val label: StringResource, val icon: ImageVector, val selected: ImageVector) {
    Home(Res.string.tab_home, Icons.Outlined.OutlinedHome, Icons.Rounded.Home),
    Servers(Res.string.tab_servers, Icons.Outlined.OutlinedDns, Icons.Rounded.Dns),
    Settings(Res.string.tab_settings, Icons.Outlined.OutlinedSettings, Icons.Rounded.Settings),
}

private val NavEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

class Navigator(private val scope: CoroutineScope) {
    val stack = mutableStateListOf<Route>(Route.Tabs)
    var tab by mutableStateOf(Tab.Home)

    val leaving = mutableStateListOf<Route>()

    private val shown = mutableMapOf<Route, Animatable<Float, AnimationVector1D>>()
    fun shownOf(route: Route) = shown.getOrPut(route) { Animatable(1f) }
    val progress get() = shownOf(stack.last())

    private val leavingOrder = mutableStateMapOf<Route, Float>()
    fun leavingZ(route: Route) = leavingOrder[route] ?: 0f

    fun coverOf(route: Route): Float {
        val index = stack.indexOf(route)
        if (index < 0) return 0f
        var cover = 0f
        for (i in index + 1..stack.lastIndex) cover = maxOf(cover, shownOf(stack[i]).value)
        leaving.forEach { if (leavingZ(it) > index) cover = maxOf(cover, shownOf(it).value) }
        return cover.coerceIn(0f, 1f)
    }
    val current get() = stack.last()
    val reselects = mutableStateMapOf<Tab, Int>()

    var serversPage by mutableStateOf<Int?>(null)
    val spotlight = SpotlightController()

    fun openSetting(spot: SettingSpot) {
        if (spot.flash) {
            spotlight.target = spot

            scope.launch {
                delay(3000)
                if (spotlight.target === spot) spotlight.target = null
            }
        }
        push(spot.route)
    }

    fun trackBack(value: Float) {
        if (stack.size <= 1) return
        scope.launch(start = CoroutineStart.UNDISPATCHED) { progress.snapTo(value.coerceIn(0f, 1f)) }
    }

    fun settleBack(close: Boolean) {
        if (close) pop() else if (stack.size > 1) scope.launch { progress.animateTo(1f, tween(260, easing = NavEasing)) }
    }

    fun openServers(page: Int) {
        serversPage = page
        tab = Tab.Servers
    }

    fun reselect(target: Tab) {
        reselects[target] = (reselects[target] ?: 0) + 1
    }

    fun push(route: Route) {
        if (route in stack) return

        if (!leaving.remove(route)) shown[route] = Animatable(0f)
        leavingOrder.remove(route)
        stack.add(route)

        val page = shownOf(route)
        scope.launch { page.animateTo(1f, tween(380, easing = NavEasing)) }
    }

    fun pop() {
        if (stack.size <= 1) return
        val route = stack.removeAt(stack.lastIndex)
        leavingOrder[route] = stack.lastIndex + 0.5f
        leaving.add(route)
        val page = shownOf(route)
        scope.launch {
            page.animateTo(0f, tween(320, easing = NavEasing))
            leaving.remove(route)
            leavingOrder.remove(route)
            shown.remove(route)
        }
    }
}

@Composable
fun App(controller: AppController) {
    val settings by controller.settings.collectAsState()
    val scope = rememberCoroutineScope()
    var reveal by remember { mutableStateOf<Offset?>(null) }
    val radius = remember { Animatable(0f) }
    var size by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val dialogs = remember { DialogHost() }

    CompositionLocalProvider(LocalPlatform provides controller.platform, LocalDialogHost provides dialogs) {
        AppTheme(settings, controller.platform) {
          val tvRipple = if (controller.platform.isTv) {
              androidx.compose.material3.RippleConfiguration(rippleAlpha = androidx.compose.material.ripple.RippleAlpha(0.16f, 0.32f, 0.16f, 0.12f))
          } else {
              androidx.compose.material3.LocalRippleConfiguration.current
          }
          CompositionLocalProvider(androidx.compose.material3.LocalRippleConfiguration provides tvRipple) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).onSizeChanged { size = it }) {
                if (!settings.onboarded || reveal != null) {
                    OnboardingScreen(controller) { origin ->
                        reveal = origin
                        controller.update({ it.copy(onboarded = true) })
                        scope.launch {
                            withFrameNanos { }
                            withFrameNanos { }
                            val far = max(
                                hypot(origin.x, origin.y),
                                hypot(size.width - origin.x, size.height - origin.y),
                            ).let { max(it, hypot(size.width - origin.x, origin.y)) }.let { max(it, hypot(origin.x, size.height - origin.y)) }
                            radius.animateTo(far, tween(650, easing = CubicBezierEasing(0.3f, 0f, 0.1f, 1f)))
                            reveal = null
                        }
                    }
                }
                if (settings.onboarded) {
                    Box(
                        Modifier.fillMaxSize().graphicsLayer {
                            val center = reveal
                            if (center != null) {
                                clip = true
                                shape = RevealShape(center, radius.value)
                            } else {
                                clip = false
                                shape = RectangleShape
                            }
                        }.background(MaterialTheme.colorScheme.surface),
                    ) {
                        MainFlow(controller)
                    }
                }
                DialogLayer(dialogs)
            }
          }
        }
    }
}

@Composable
private fun MainFlow(controller: AppController) {
    val scope = rememberCoroutineScope()
    val nav = remember { Navigator(scope) }
    val snackbar = remember { SnackbarHostState() }
    val pendingImport by controller.pendingImport.collectAsState()
    val tunnel by controller.tunnel.collectAsState()
    val platform = controller.platform

    LaunchedEffect(Unit) {
        controller.message.collect { message ->
            snackbar.showSnackbar(message.text(controller.settings.value.detailedErrors))
        }
    }
    LaunchedEffect(pendingImport) {
        if (pendingImport != null) nav.push(Route.Add)
    }
    var lastTunnel by remember { mutableStateOf(tunnel) }
    LaunchedEffect(tunnel) {
        when {
            tunnel == TunnelState.On && lastTunnel != TunnelState.On -> if (controller.takeHandAction()) platform.haptic(Haptic.Connected)
            tunnel == TunnelState.Off && lastTunnel == TunnelState.Stopping -> if (controller.takeHandAction()) platform.haptic(Haptic.Disconnected)
            tunnel is TunnelState.Failed -> if (controller.takeHandAction()) platform.haptic(Haptic.Failed)
        }
        lastTunnel = tunnel
    }

    PredictiveBackHandler(enabled = nav.stack.size > 1) { events ->
        val page = nav.progress
        try {
            var from: Float? = null
            events.collect { event ->
                val start = from ?: page.value.also { from = it }
                page.snapTo(start * (1f - event.progress * 0.3f))
            }
            nav.pop()
        } catch (e: CancellationException) {
            scope.launch { page.animateTo(1f, tween(260, easing = NavEasing)) }
            throw e
        }
    }

    Box(Modifier.fillMaxSize()) {
      CompositionLocalProvider(LocalSpotlight provides nav.spotlight) {
        RouteHost(nav) { route ->
            when (route) {
                Route.Tabs -> TabsScreen(controller, nav)
                Route.Add -> AddSubscriptionScreen(controller, onDone = { nav.pop() }, onManual = { nav.push(Route.ManualServer()) })
                Route.Basic -> BasicSettingsScreen(controller, nav)
                Route.AppSettingsScreen -> AppSettingsScreen(controller, nav)
                Route.Advanced -> AdvancedSettingsScreen(controller, nav)
                Route.Power -> PowerSettingsScreen(controller, nav)
                Route.Subscriptions -> SubscriptionSettingsScreen(controller, nav)
                Route.ToolsSettings -> ToolsSettingsScreen(controller, nav)
                Route.IdleOff -> IdleOffScreen(controller, onBack = { nav.pop() })
                Route.SpeedTest -> SpeedTestScreen(controller, onBack = { nav.pop() })
                Route.Apps -> SplitAppsScreen(controller, onBack = { nav.pop() })
                Route.Rules -> RulesScreen(controller, onBack = { nav.pop() })
                Route.Notifications -> NotificationSettingsScreen(controller, onBack = { nav.pop() })
                Route.Widgets -> WidgetsScreen(controller, onBack = { nav.pop() })
                Route.Logs -> LogsScreen(controller, onBack = { nav.pop() })
                Route.Help -> HelpScreen(controller, nav)
                Route.Connections -> ConnectionsScreen(controller, onBack = { nav.pop() })
                Route.Diagnostics -> DiagnosticsScreen(controller, onBack = { nav.pop() })
                Route.DnsLog -> DnsLogScreen(controller, onBack = { nav.pop() })
                is Route.SiteCheck -> SiteCheckScreen(controller, onBack = { nav.pop() }, route.host)
                Route.AutoRules -> AutoRulesScreen(controller, onBack = { nav.pop() })
                is Route.EditProfile -> EditProfileScreen(controller, route.id, onBack = { nav.pop() })
                is Route.MergeProfiles -> MergeProfilesScreen(controller, route.id, onBack = { nav.pop() })
                is Route.ManualServer -> ManualServerScreen(controller, route.name, onBack = { nav.pop() }, onSaved = {
                    nav.pop()
                    if (nav.stack.lastOrNull() == Route.Add) nav.pop()
                })
                Route.About -> AboutScreen(controller, onBack = { nav.pop() })
            }
        }
      }
        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp),
        )
    }
}

@Composable
private fun RouteHost(nav: Navigator, content: @Composable (Route) -> Unit) {
    val surface = MaterialTheme.colorScheme.surface

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        (nav.stack.toList() + nav.leaving).forEach { route ->
            key(route) {
                val isLeaving = route in nav.leaving
                val isTop = route == nav.stack.last()
                val capture = rememberGraphicsLayer()

                Box(
                    Modifier.zIndex(if (isLeaving) -1f else nav.stack.indexOf(route).toFloat()).fillMaxSize()
                        .then(if (isTop && route != Route.Tabs) Modifier.swipeBack(nav) else Modifier),
                ) {
                    Box(
                        Modifier.fillMaxSize().graphicsLayer {
                            if (route in nav.leaving) {
                                translationX = 0f
                                alpha = 1f
                                scaleX = 1f
                                scaleY = 1f
                                clip = false
                                shape = RectangleShape
                                shadowElevation = 0f
                            } else {
                                pageTransform(nav.shownOf(route).value.coerceIn(0f, 1f), nav.coverOf(route))
                            }
                        }.drawWithContent {
                            if (route in nav.leaving) capture.record { this@drawWithContent.drawContent() } else drawContent()
                        }.background(surface).then(
                            if (isTop) {
                                Modifier
                            } else {
                                Modifier.pointerInput(Unit) {
                                    awaitPointerEventScope {
                                        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                                    }
                                }
                            },
                        ),
                    ) { content(route) }
                }
                if (isLeaving) {
                    Box(
                        Modifier.zIndex(nav.leavingZ(route)).fillMaxSize().graphicsLayer {
                            pageTransform(nav.shownOf(route).value.coerceIn(0f, 1f), 0f)
                        }.drawBehind { drawLayer(capture) },
                    )
                }
            }
        }
    }
}

private fun GraphicsLayerScope.pageTransform(shown: Float, cover: Float) {
    val away = 1f - shown
    val lift = minOf(1f, away * 6f)
    translationX = size.width * away - size.width * 0.1f * cover
    val scale = 1f - 0.08f * away
    scaleX = scale
    scaleY = scale
    alpha = if (cover >= 1f) 0f else 1f - 0.35f * cover
    val corner = maxOf(lift, cover)
    if (corner > 0f) {
        clip = true
        shape = RoundedCornerShape(32.dp * corner)
        shadowElevation = 24f * lift
    } else {
        clip = false
        shape = RectangleShape
        shadowElevation = 0f
    }
}

private fun Modifier.swipeBack(nav: Navigator): Modifier = pointerInput(nav) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (down.type == androidx.compose.ui.input.pointer.PointerType.Mouse) return@awaitEachGesture
        val tracker = VelocityTracker()
        tracker.addPointerInputChange(down)
        var dragging = false
        var total = Offset.Zero
        var startX = 0f
        var startShown = 1f
        while (true) {
            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            tracker.addPointerInputChange(change)
            if (!dragging) {
                if (change.isConsumed) return@awaitEachGesture
                total += change.positionChange()
                val slop = viewConfiguration.touchSlop
                if (abs(total.y) > slop || total.x < -slop) return@awaitEachGesture
                if (total.x > slop) {
                    dragging = true

                    startX = change.position.x
                    startShown = nav.progress.value
                }
            }
            if (dragging) {
                change.consume()
                nav.trackBack(startShown - (change.position.x - startX) / size.width)
            }
        }
        if (dragging) {
            val fast = tracker.calculateVelocity().x > 1000f

            nav.settleBack(fast || startShown - nav.progress.value > 0.35f * startShown)
        }
    }
}

val LocalTabsPager = compositionLocalOf<PagerState?> { null }

private const val HANDOFF_VELOCITY = 300f
private const val HANDOFF_DISTANCE = 0.12f

private suspend fun PagerState.settleTabs(velocity: Float) {
    val moved = currentPage + currentPageOffsetFraction - settledPage
    val step = when {
        velocity < -HANDOFF_VELOCITY -> 1
        velocity > HANDOFF_VELOCITY -> -1
        moved > HANDOFF_DISTANCE -> 1
        moved < -HANDOFF_DISTANCE -> -1
        else -> 0
    }
    animateScrollToPage((settledPage + step).coerceIn(0, pageCount - 1), animationSpec = tween(280, easing = NavEasing))
}

@Composable
fun Modifier.tabsHandoff(): Modifier {
    val tabs = LocalTabsPager.current ?: return this
    val scope = rememberCoroutineScope()
    val connection = remember(tabs) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (source != androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput || abs(tabs.currentPageOffsetFraction) < 0.001f) return androidx.compose.ui.geometry.Offset.Zero
                return androidx.compose.ui.geometry.Offset(-tabs.dispatchRawDelta(-available.x), 0f)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (abs(tabs.currentPageOffsetFraction) < 0.001f) return Velocity.Zero
                tabs.settleTabs(available.x)
                return available
            }
        }
    }
    return nestedScroll(connection).pointerInput(tabs) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
            do {
                val event = awaitPointerEvent(PointerEventPass.Final)
            } while (event.changes.any { it.pressed })
            scope.launch {
                delay(120)
                if (!tabs.isScrollInProgress && abs(tabs.currentPageOffsetFraction) > 0.001f) tabs.settleTabs(0f)
            }
        }
    }
}

@Composable
private fun NavIcon(tab: Tab, selected: Boolean) {
    AnimatedContent(
        selected,
        label = "navIcon",
        contentAlignment = Alignment.Center,
        transitionSpec = {
            (fadeIn(tween(180, 60)) + scaleIn(tween(260, easing = NavEasing), initialScale = 0.7f)) togetherWith
                (fadeOut(tween(100)) + scaleOut(tween(160), targetScale = 0.7f)) using SizeTransform(clip = false)
        },
    ) { on -> Icon(if (on) tab.selected else tab.icon, null) }
}

@Composable
private fun TabsScreen(controller: AppController, nav: Navigator) {
    val holder = rememberSaveableStateHolder()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val rail = maxWidth >= 600.dp
        val tallRail = maxHeight >= 600.dp
        val split = rail && (maxWidth >= 840.dp || maxWidth > maxHeight)
        val scope = rememberCoroutineScope()
        val pager = rememberPagerState(nav.tab.ordinal) { Tab.entries.size }
        var jumping by remember { mutableStateOf(false) }
        var jumpId by remember { mutableStateOf(0) }

        LaunchedEffect(nav.tab) {
            if (pager.currentPage != nav.tab.ordinal || abs(pager.currentPageOffsetFraction) > 0.001f) {
                val id = ++jumpId
                jumping = true
                try {
                    pager.animateScrollToPage(nav.tab.ordinal, animationSpec = tween(320, easing = NavEasing))
                } finally {
                    if (jumpId == id) jumping = false
                }
            }
        }

        LaunchedEffect(pager) {
            snapshotFlow { pager.currentPage }.collect { page ->
                if (!jumping && nav.tab.ordinal != page) {
                    controller.platform.haptic(Haptic.Tick)
                    nav.tab = Tab.entries[page]
                }
            }
        }

        LaunchedEffect(pager) {
            snapshotFlow { pager.isScrollInProgress }.collect { scrolling ->
                if (!scrolling && !jumping && abs(pager.currentPageOffsetFraction) > 0.001f) {
                    pager.animateScrollToPage(pager.currentPage, animationSpec = tween(240, easing = NavEasing))
                }
            }
        }

        PredictiveBackHandler(enabled = nav.stack.size == 1 && nav.tab != Tab.Home) { events ->
            val start = pager.currentPage
            val fraction = pager.currentPageOffsetFraction
            try {
                events.collect { event -> pager.scrollToPage(start, (fraction - 0.45f * event.progress).coerceIn(-0.5f, 0.5f)) }
                nav.tab = Tab.Home
            } catch (e: CancellationException) {
                scope.launch { pager.animateScrollToPage(nav.tab.ordinal, animationSpec = tween(240, easing = NavEasing)) }
                throw e
            }
        }

        val content: @Composable (Modifier) -> Unit = { modifier ->

            CompositionLocalProvider(LocalTabsPager provides pager) {
                HorizontalPager(pager, modifier, beyondViewportPageCount = Tab.entries.lastIndex) { page ->
                    val tab = Tab.entries[page]
                    holder.SaveableStateProvider(tab.name) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                            when (tab) {
                                Tab.Home -> HomeScreen(controller, nav, split)
                                Tab.Servers -> ServersScreen(controller, nav)
                                Tab.Settings -> SettingsHubScreen(controller, nav)
                            }
                        }
                    }
                }
            }
        }
        if (rail) {
            Row(Modifier.fillMaxSize()) {
                NavRail(controller, nav, centered = tallRail)
                content(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.surface,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                bottomBar = {
                    ShortNavigationBar(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.shadow(12.dp),
                    ) {
                        Tab.entries.forEach { tab ->
                            ShortNavigationBarItem(
                                selected = nav.tab == tab,
                                onClick = { switchTab(controller, nav, tab) },
                                icon = { NavIcon(tab, nav.tab == tab) },
                                label = { Text(stringResource(tab.label)) },
                            )
                        }
                    }
                },
            ) { padding -> content(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize()) }
        }
    }
}

@Composable
private fun NavRail(controller: AppController, nav: Navigator, centered: Boolean) {
    val scope = rememberCoroutineScope()
    val state = rememberWideNavigationRailState(
        if (controller.settings.collectAsState().value.railExpanded) WideNavigationRailValue.Expanded else WideNavigationRailValue.Collapsed,
    )
    val expanded = state.targetValue == WideNavigationRailValue.Expanded
    WideNavigationRail(
        state = state,
        modifier = Modifier.zIndex(1f).shadow(8.dp),

        arrangement = if (centered) Arrangement.Center else Arrangement.Top,
        colors = WideNavigationRailDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        header = {
            IconButton(
                onClick = {
                    controller.platform.haptic(Haptic.Tick)
                    controller.update({ it.copy(railExpanded = !expanded) })
                    scope.launch { state.toggle() }
                },
                modifier = Modifier.padding(start = 24.dp),
            ) {
                AnimatedContent(expanded, label = "railIcon", transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(100)) }) { open ->
                    Icon(
                        if (open) Icons.AutoMirrored.Rounded.MenuOpen else Icons.Rounded.Menu,
                        stringResource(if (open) Res.string.nav_collapse else Res.string.nav_expand),
                    )
                }
            }
        },
    ) {
        Tab.entries.forEach { tab ->
            WideNavigationRailItem(
                railExpanded = expanded,
                selected = nav.tab == tab,
                onClick = { switchTab(controller, nav, tab) },
                icon = { NavIcon(tab, nav.tab == tab) },
                label = { Text(stringResource(tab.label)) },
            )
        }
    }
}

private fun switchTab(controller: AppController, nav: Navigator, tab: Tab) {
    controller.platform.haptic(Haptic.Tick)
    if (nav.tab == tab) nav.reselect(tab) else nav.tab = tab
}

@Composable
fun ReselectEffect(nav: Navigator, tab: Tab, block: suspend () -> Unit) {
    val count = nav.reselects[tab] ?: 0
    val initial = remember { count }
    LaunchedEffect(count) { if (count != initial) block() }
}

package dev.rooni.zefir.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.backhandler.PredictiveBackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import dev.rooni.zefir.app.AppController
import dev.rooni.zefir.app.UpdateCheck
import dev.rooni.zefir.app.Updates
import dev.rooni.zefir.platform.Haptic
import dev.rooni.zefir.res.*
import dev.rooni.zefir.ui.*
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private class HubTile(val title: StringResource, val icon: ImageVector, val route: Route, val shape: Shape, val badge: String? = null)

@Composable
fun SettingsHubScreen(controller: AppController, nav: Navigator) {
    val settings by controller.settings.collectAsState()
    val shapes = remember {
        listOf(
            MaterialShapes.Cookie9Sided, MaterialShapes.Clover4Leaf, MaterialShapes.Sunny,
            MaterialShapes.Cookie4Sided, MaterialShapes.Gem, MaterialShapes.Flower,
        ).map(::PolygonShape)
    }
    val tiles = buildList {
        add(HubTile(Res.string.hub_routing, Icons.AutoMirrored.Rounded.AltRoute, Route.Basic, shapes[0]))
        add(HubTile(Res.string.hub_power, Icons.Rounded.PowerSettingsNew, Route.Power, shapes[1]))
        add(HubTile(Res.string.hub_subs, Icons.Rounded.Dns, Route.Subscriptions, shapes[2]))
        add(HubTile(Res.string.set_app, Icons.Rounded.Palette, Route.AppSettingsScreen, shapes[3]))
        if (controller.platform.hasNotificationSettings) add(HubTile(Res.string.notif_title, Icons.Rounded.Notifications, Route.Notifications, shapes[4]))
        add(HubTile(Res.string.hub_tools, Icons.Rounded.Handyman, Route.ToolsSettings, shapes[5]))
    }
    val scroll = rememberScrollState()
    var barReset by remember { mutableStateOf(0) }
    ReselectEffect(nav, Tab.Settings) {
        barReset++
        scroll.animateScrollTo(0)
    }
    var searching by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(nav.tab) { if (nav.tab != Tab.Settings) searching = false }
    val backShare = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val searchShown = remember { MutableTransitionState(false) }
    searchShown.targetState = searching
    LaunchedEffect(searching) { if (searching) backShare.snapTo(0f) }
    PredictiveBackHandler(enabled = searching) { events ->
        try {
            events.collect { backShare.snapTo(it.progress) }
            searching = false
        } catch (e: kotlinx.coroutines.CancellationException) {
            scope.launch { backShare.animateTo(0f, tween(200)) }
            throw e
        }
    }
    Box(Modifier.fillMaxSize()) {
    SubScreen(
        stringResource(Res.string.tab_settings),
        null,
        barReset = barReset,
        forceCollapsed = searching || !searchShown.isIdle || searchShown.currentState,
        actions = {
            IconButton(onClick = {
                controller.platform.haptic(Haptic.Tick)
                searching = true
            }) { Icon(Icons.Rounded.Search, stringResource(Res.string.search_hint)) }
        },
    ) { padding ->
        ScrollColumn(Modifier.padding(padding), scroll) {
            BoxWithConstraints {
                val columns = (maxWidth / 170.dp).toInt().coerceIn(2, 4)
                val tints = LocalIconTints.current
                EqualGrid(columns, 10.dp) {
                    tiles.forEachIndexed { index, tile ->
                        HubTileView(tile, tints[index % tints.size], Modifier) {
                            controller.platform.haptic(Haptic.Tick)
                            nav.push(tile.route)
                        }
                    }
                }
            }
            SectionGroup(
                modifier = Modifier.padding(top = 4.dp),
                rows = listOf(
                    { NavRow(it, stringResource(Res.string.set_advanced), null, Icons.Rounded.Build) { nav.push(Route.Advanced) } },
                    { NavRow(it, stringResource(Res.string.help_title), null, Icons.AutoMirrored.Rounded.HelpOutline) { nav.push(Route.Help) } },
                    { NavRow(it, stringResource(Res.string.set_about), null, Icons.Rounded.Info) { nav.push(Route.About) } },
                ),
            )
        }
    }
    AnimatedVisibility(
        searchShown,
        modifier = Modifier.graphicsLayer {
            val progress = backShare.value
            scaleX = 1f - progress * 0.1f
            scaleY = 1f - progress * 0.1f
            translationX = progress * 24.dp.toPx()
            shape = RoundedCornerShape(32.dp * progress)
            clip = progress > 0f
        },
        enter = fadeIn(tween(200, 60)),
        exit = fadeOut(tween(180)) + scaleOut(tween(220), targetScale = 0.94f),
    ) {
        SettingsSearch(controller, nav) { searching = false }
    }
    }
}

@Composable
private fun EqualGrid(columns: Int, gap: androidx.compose.ui.unit.Dp, content: @Composable () -> Unit) {
    androidx.compose.ui.layout.Layout(content) { measurables, constraints ->
        val gapPx = gap.roundToPx()
        val total = constraints.maxWidth
        val wanted = measurables.map { it.maxIntrinsicWidth(androidx.compose.ui.unit.Constraints.Infinity) }

        fun split(rowCount: Int): List<List<Int>> {
            val rows = mutableListOf<List<Int>>()
            var start = 0
            for (row in 0 until rowCount) {
                val size = (measurables.size - start + (rowCount - row - 1)) / (rowCount - row)
                rows += (start until start + size).toList()
                start += size
            }
            return rows
        }

        fun fits(row: List<Int>) = row.sumOf { wanted[it] } + gapPx * (row.size - 1) <= total

        val first = (measurables.size + columns - 1) / columns

        val rows = (first..measurables.size).map(::split).firstOrNull { it.all(::fits) } ?: split(first)

        fun widths(row: List<Int>): Map<Int, Int> {
            val free = total - gapPx * (row.size - 1)
            val fixed = mutableMapOf<Int, Int>()

            while (true) {
                val rest = row.filter { it !in fixed }
                if (rest.isEmpty()) break
                val share = (free - fixed.values.sum()) / rest.size
                val big = rest.filter { wanted[it] > share }
                if (big.isEmpty() || big.size == rest.size && fixed.isEmpty() && !fits(row)) {
                    rest.forEach { fixed[it] = share }
                    break
                }
                big.forEach { fixed[it] = wanted[it] }
            }

            val leftover = free - fixed.values.sum()
            if (leftover != 0) fixed[row.last()] = fixed.getValue(row.last()) + leftover
            return fixed
        }

        val cellWidths = rows.flatMap { row -> widths(row).toList() }.toMap()
        val height = measurables.indices.maxOfOrNull { measurables[it].minIntrinsicHeight(cellWidths.getValue(it)) } ?: 0
        val placeables = measurables.mapIndexed { index, measurable ->
            measurable.measure(androidx.compose.ui.unit.Constraints.fixed(cellWidths.getValue(index), height))
        }
        layout(total, height * rows.size + gapPx * (rows.size - 1).coerceAtLeast(0)) {
            rows.forEachIndexed { rowIndex, row ->
                var x = 0
                row.forEach { index ->
                    placeables[index].place(x, rowIndex * (height + gapPx))
                    x += placeables[index].width + gapPx
                }
            }
        }
    }
}

@Composable
private fun HubTileView(tile: HubTile, tint: IconTint, modifier: Modifier, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, spring(0.6f, 800f), label = "tile")
    val shape = RoundedCornerShape(28.dp)
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
        }.clip(shape).pressTracking { pressed = it }.clickable(press, androidx.compose.foundation.LocalIndication.current, onClick = onClick),
    ) {
        Box {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp)) {
                    Surface(color = tint.container, shape = tile.shape, modifier = Modifier.fillMaxSize()) {}
                    Icon(tile.icon, null, tint = tint.onContainer, modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.height(10.dp))
                Text(stringResource(tile.title), style = MaterialTheme.typography.titleMedium, maxLines = 2)
            }

            androidx.compose.animation.AnimatedVisibility(
                tile.badge != null,
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
                enter = fadeIn() + scaleIn(tween(200)),
                exit = fadeOut() + scaleOut(tween(150)),
            ) {
                var last by remember { mutableStateOf(tile.badge.orEmpty()) }
                tile.badge?.let { last = it }
                Surface(shape = RoundedCornerShape(12.dp), color = tint.container, contentColor = tint.onContainer) {
                    Text(
                        last,
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(min = 24.dp).padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
        }
    }
}

private class Faq(val question: StringResource, val answer: StringResource, val action: StringResource? = null, val external: Boolean = false, val onAction: (() -> Unit)? = null)

@Composable
fun HelpScreen(controller: AppController, nav: Navigator) {
    val uri = LocalUriHandler.current
    val platform = controller.platform
    val playBuild = PLAY_BUILD
    val profile = controller.activeProfile
    val sections = listOf(
        Res.string.help_sec_subs to buildList {
            add(Faq(Res.string.faq_sub_q, Res.string.faq_sub_a))
            add(Faq(Res.string.faq_add_q, Res.string.faq_add_a, Res.string.faq_add_action) { nav.push(Route.Add) })
            if (!playBuild) {
                add(Faq(Res.string.faq_client_q, Res.string.faq_client_a, Res.string.faq_client_action.takeIf { profile != null }) {
                    profile?.let { nav.push(Route.EditProfile(it.id)) }
                })
            }
            add(Faq(Res.string.faq_update_q, Res.string.faq_update_a, Res.string.logs) { nav.push(Route.Logs) })
        },
        Res.string.help_sec_connection to buildList {
            add(Faq(Res.string.faq_slow_q, Res.string.faq_slow_a, Res.string.diag_title) { nav.push(Route.Diagnostics) })
            add(Faq(Res.string.faq_speed_q, Res.string.faq_speed_a, Res.string.speed_title) { nav.push(Route.SpeedTest) })
            add(Faq(Res.string.faq_bank_q, Res.string.faq_bank_a, Res.string.tile_apps.takeIf { platform.hasPerAppRouting }) { nav.push(Route.Apps) })
            add(Faq(Res.string.faq_local_q, Res.string.faq_local_a, Res.string.hub_routing) { nav.push(Route.Basic) })
            add(Faq(Res.string.faq_battery_q, Res.string.faq_battery_a, Res.string.hub_power) { nav.push(Route.Power) })
        },
        Res.string.help_sec_app to listOf(
            Faq(Res.string.faq_backup_q, Res.string.faq_backup_a, Res.string.hub_tools) { nav.push(Route.ToolsSettings) },
            Faq(Res.string.faq_bug_q, Res.string.faq_bug_a, Res.string.faq_bug_action, external = true) { uri.openUri("https://t.me/YouRooni") },
        ),
    )
    SubScreen(stringResource(Res.string.help_title), { nav.pop() }) { padding ->
        ScrollColumn(Modifier.padding(padding)) {
            sections.forEach { (title, items) ->
                SectionGroup(title = stringResource(title), rows = items.map { faq -> { shape: Shape -> FaqRow(shape, faq) } })
            }
        }
    }
}

@Composable
private fun FaqRow(shape: Shape, faq: Faq) {
    val platform = LocalPlatform.current
    var open by remember { mutableStateOf(false) }
    val turn by animateFloatAsState(if (open) 180f else 0f, label = "faq")
    val container by animateColorAsState(if (open) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer, label = "faqColor")
    Surface(shape = shape, color = container, modifier = Modifier.fillMaxWidth().clip(shape).clickable {
        platform.haptic(Haptic.Tick)
        open = !open
    }) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(faq.question), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Icon(Icons.Rounded.ExpandMore, null, Modifier.graphicsLayer { rotationZ = turn }, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AnimatedVisibility(open, enter = RevealEnter, exit = RevealExit) {
                Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(faq.answer), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val action = faq.action
                    val onAction = faq.onAction
                    if (action != null && onAction != null) {
                        FilledTonalButton(onClick = {
                            platform.haptic(Haptic.Tick)
                            onAction()
                        }, shapes = ButtonDefaults.shapes()) {
                            Text(stringResource(action))
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                if (faq.external) Icons.Rounded.ArrowOutward else Icons.AutoMirrored.Rounded.ArrowForward,
                                null,
                                Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AboutScreen(controller: AppController, onBack: () -> Unit) {
    val platform = controller.platform
    val uri = LocalUriHandler.current
    val polygons = remember {
        listOf(
            MaterialShapes.Cookie9Sided, MaterialShapes.Sunny, MaterialShapes.Clover8Leaf, MaterialShapes.Gem,
            MaterialShapes.Flower, MaterialShapes.Puffy, MaterialShapes.Cookie12Sided, MaterialShapes.Circle,
        )
    }
    var taps by remember { mutableStateOf(0) }
    val morph = remember { Animatable(1f) }
    var from by remember { mutableStateOf(0) }
    var to by remember { mutableStateOf(0) }
    val shape = remember(from, to) { Morph(polygons[from], polygons[to]) }
    val spinning by animateFloatAsState(taps * 45f, spring(0.7f, 150f), label = "spin")
    val pulse = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    var update by remember { mutableStateOf<UpdateCheck>(UpdateCheck.Idle) }
    val playBuild = PLAY_BUILD

    fun tap() {
        platform.haptic(Haptic.Tick)
        taps++
        scope.launch {
            pulse.snapTo(0.9f)
            pulse.animateTo(1f, spring(0.5f, 500f))
        }

        if (!morph.isRunning) {
            from = to
            to = (to + 1) % polygons.size
            scope.launch {
                morph.snapTo(0f)
                morph.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
            }
        }
    }

    fun checkUpdate() {
        val found = update
        when {
            found is UpdateCheck.Available -> uri.openUri(found.url)
            found == UpdateCheck.Checking -> Unit
            else -> scope.launch {
                update = UpdateCheck.Checking
                update = if (playBuild) platform.checkStoreUpdate() ?: UpdateCheck.Failed else Updates.check(APP_VERSION)
                platform.haptic(if (update is UpdateCheck.Failed) Haptic.Failed else Haptic.Toggle)
            }
        }
    }

    SubScreen(stringResource(Res.string.set_about), onBack) { padding ->
        ScrollColumn(Modifier.padding(padding)) {
            Column(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(168.dp).graphicsLayer {
                        rotationZ = spinning
                        scaleX = pulse.value
                        scaleY = pulse.value
                    }.clip(MorphShape(shape, morph.value)).clickable(remember { MutableInteractionSource() }, null, onClick = ::tap),
                ) {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxSize()) {}

                    Icon(
                        ZefirMark, null,
                        Modifier.size(80.dp).graphicsLayer { rotationZ = -spinning },
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(APP_NAME, style = MaterialTheme.typography.displaySmallEmphasized, textAlign = TextAlign.Center)
                Text(
                    stringResource(Res.string.about_text),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
            SectionGroup(
                rows = listOf(
                    { shape -> VersionRow(shape, update, ::checkUpdate) },
                    { LinkRow(it, stringResource(Res.string.about_author), null, Icons.AutoMirrored.Rounded.Send, "YouRooni.t.me") { uri.openUri("https://t.me/YouRooni") } },
                    { LinkRow(it, "GitHub", null, Icons.Rounded.Code, "YouRooni/Zefir") { uri.openUri("https://github.com/YouRooni/Zefir") } },
                ),
            )
            SectionGroup(
                title = stringResource(Res.string.about_inside),
                rows = listOf(
                    { LinkRow(it, stringResource(Res.string.about_core), stringResource(Res.string.about_core_desc), Icons.Rounded.Memory, "mihomo") { uri.openUri("https://github.com/MetaCubeX/mihomo") } },
                    { LinkRow(it, stringResource(Res.string.about_license), stringResource(Res.string.about_license_desc), Icons.Rounded.Gavel, "GPL-3.0") { uri.openUri("https://www.gnu.org/licenses/gpl-3.0.html") } },
                ),
            )
        }
    }
}

@Composable
private fun VersionRow(shape: Shape, update: UpdateCheck, onClick: () -> Unit) {
    val description = when (update) {
        UpdateCheck.Idle -> stringResource(Res.string.update_tap)
        UpdateCheck.Checking -> stringResource(Res.string.update_checking)
        UpdateCheck.Latest -> stringResource(Res.string.update_latest)
        UpdateCheck.Failed -> stringResource(Res.string.update_failed)
        // Google Play номер версии не отдаёт, только факт обновления
        is UpdateCheck.Available -> if (update.version.isEmpty()) stringResource(Res.string.update_available_play) else stringResource(Res.string.update_available, update.version)
    }
    val available = update is UpdateCheck.Available
    SettingRow(
        shape,
        stringResource(Res.string.about_version, APP_VERSION),
        description,
        if (available) Icons.Rounded.NewReleases else Icons.Rounded.Update,
        onClick = onClick,
    ) {
        AnimatedContent(
            when {
                available -> 2
                update == UpdateCheck.Checking -> 1
                else -> 0
            },
            transitionSpec = { (fadeIn(tween(160)) + scaleIn(tween(200), 0.6f)) togetherWith (fadeOut(tween(120)) + scaleOut(tween(160), 0.6f)) },
            label = "updateState",
        ) { state ->
            Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                when (state) {
                    1 -> LoadingIndicator(Modifier.size(28.dp))
                    2 -> Icon(Icons.Rounded.ArrowOutward, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

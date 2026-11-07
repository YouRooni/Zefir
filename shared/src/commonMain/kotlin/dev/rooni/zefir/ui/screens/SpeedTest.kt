package dev.rooni.zefir.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import dev.rooni.zefir.app.AppController
import dev.rooni.zefir.app.SpeedPhase
import dev.rooni.zefir.app.SpeedState
import dev.rooni.zefir.platform.Haptic
import dev.rooni.zefir.res.*
import dev.rooni.zefir.ui.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private enum class GaugeMode { Idle, Ping, Download, Upload, Done }

private fun mbps(value: Double) = if (value < 10) "%.1f".format(value) else "%.0f".format(value)

@Composable
fun SpeedTestScreen(controller: AppController, onBack: () -> Unit) {
    val platform = controller.platform
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<SpeedState?>(null) }
    var running by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var attempt by remember { mutableStateOf(0) }
    val curves = remember { mutableStateMapOf<SpeedPhase, List<Float>>() }
    val tunnel by controller.tunnel.collectAsState()
    val server = remember(tunnel, running) { controller.currentServer() }

    fun start() {
        platform.haptic(Haptic.Press)
        curves.clear()
        failed = false
        val token = ++attempt
        job = scope.launch {
            running = true
            state = SpeedState()
            try {
                controller.speedTester().run { next ->
                    if (attempt != token) return@run
                    if (next.phase == SpeedPhase.Download || next.phase == SpeedPhase.Upload) curves[next.phase] = next.samples
                    state = next
                }
                if (attempt == token) platform.haptic(Haptic.Connected)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (attempt == token) {
                    failed = true
                    platform.haptic(Haptic.Failed)
                }
            } finally {
                if (attempt == token) running = false
            }
        }
    }

    fun stop() {
        platform.haptic(Haptic.Tick)
        attempt++
        job?.cancel()
        running = false
        state = null
        curves.clear()
    }

    val current = state
    val mode = when {
        failed || current == null -> GaugeMode.Idle
        current.phase == SpeedPhase.Ping -> GaugeMode.Ping
        current.phase == SpeedPhase.Download -> GaugeMode.Download
        current.phase == SpeedPhase.Upload -> GaugeMode.Upload
        else -> GaugeMode.Done
    }
    LaunchedEffect(mode) { if (mode == GaugeMode.Download || mode == GaugeMode.Upload) platform.haptic(Haptic.Tick) }

    SubScreen(stringResource(Res.string.speed_title), onBack, collapsing = false) { padding ->
        ScrollColumn(Modifier.padding(padding)) {
            Gauge(mode, current, onStart = ::start)
            Text(
                when {
                    server != null -> stringResource(Res.string.speed_via, server)
                    else -> stringResource(Res.string.speed_no_vpn)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            )
            StatRow(mode, current)
            AnimatedVisibility(mode != GaugeMode.Idle && mode != GaugeMode.Ping, enter = RevealEnter, exit = RevealExit) {
                Chart(mode, curves)
            }
            MessageBanner(if (failed) stringResource(Res.string.speed_failed) else null)
            AnimatedVisibility(mode == GaugeMode.Done && current != null, enter = RevealEnter, exit = RevealExit) {
                var last by remember { mutableStateOf(current) }
                if (current != null && current.phase == SpeedPhase.Done) last = current
                last?.let { Summary(it, server) }
            }
            AnimatedContent(
                when {
                    running -> 1
                    mode == GaugeMode.Done || failed -> 2
                    else -> 0
                },
                label = "speedAction",
            ) { action ->
                when (action) {
                    1 -> OutlinedButton(onClick = ::stop, modifier = Modifier.fillMaxWidth().height(56.dp), shapes = ButtonDefaults.shapes()) {
                        ButtonText(stringResource(Res.string.speed_stop))
                    }
                    2 -> Button(onClick = ::start, modifier = Modifier.fillMaxWidth().height(56.dp), shapes = ButtonDefaults.shapes()) {
                        Icon(Icons.Rounded.Refresh, null)
                        Spacer(Modifier.width(8.dp))
                        ButtonText(stringResource(Res.string.speed_again))
                    }
                    else -> Text(
                        stringResource(Res.string.speed_data_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Gauge(mode: GaugeMode, state: SpeedState?, onStart: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val polygons = remember {
        mapOf(
            GaugeMode.Idle to MaterialShapes.Cookie12Sided,
            GaugeMode.Ping to MaterialShapes.Sunny,
            GaugeMode.Download to MaterialShapes.Cookie9Sided,
            GaugeMode.Upload to MaterialShapes.Clover8Leaf,
            GaugeMode.Done to MaterialShapes.SoftBurst,
        )
    }
    var from by remember { mutableStateOf(mode) }
    var to by remember { mutableStateOf(mode) }
    val morph = remember { Animatable(1f) }
    LaunchedEffect(mode) {
        if (mode == to) return@LaunchedEffect
        from = to
        to = mode
        morph.snapTo(0f)
        morph.animateTo(1f, spring(0.6f, 220f))
    }
    val shape = remember(from, to) { Morph(polygons.getValue(from), polygons.getValue(to)) }
    val busy = mode != GaugeMode.Idle && mode != GaugeMode.Done
    val speedUp by animateFloatAsState(if (busy) 1f else 0f, tween(600), label = "spinSpeed")
    val currentSpeed by rememberUpdatedState(speedUp)
    var turn by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val seconds = (now - last) / 1e9f
                last = now
                turn = (turn + seconds * 360f / 14f * (0.25f + currentSpeed)) % 360f
            }
        }
    }
    val container by animateColorAsState(
        when (mode) {
            GaugeMode.Idle -> scheme.primary
            GaugeMode.Ping -> scheme.secondaryContainer
            GaugeMode.Download -> scheme.primaryContainer
            GaugeMode.Upload -> scheme.tertiaryContainer
            GaugeMode.Done -> scheme.surfaceContainerHigh
        },
        tween(400),
        label = "gaugeColor",
    )
    val content by animateColorAsState(
        when (mode) {
            GaugeMode.Idle -> scheme.onPrimary
            GaugeMode.Ping -> scheme.onSecondaryContainer
            GaugeMode.Download -> scheme.onPrimaryContainer
            GaugeMode.Upload -> scheme.onTertiaryContainer
            GaugeMode.Done -> scheme.onSurface
        },
        tween(400),
        label = "gaugeText",
    )
    val ring by animateColorAsState(if (mode == GaugeMode.Upload) scheme.tertiary else scheme.primary, tween(400), label = "ring")
    val progress by animateFloatAsState(state?.progress ?: 0f, tween(260), label = "gaugeProgress")
    var pressed by remember { mutableStateOf(false) }
    val press by animateFloatAsState(if (pressed) 0.92f else 1f, spring(0.5f, 600f), label = "gaugePress")
    val pulse by animateFloatAsState(
        if (mode == GaugeMode.Download || mode == GaugeMode.Upload) (1f + (state?.live ?: 0.0).coerceAtMost(500.0).toFloat() / 500f * 0.06f) else 1f,
        spring(0.7f, 120f),
        label = "gaugePulse",
    )
    val stroke = with(LocalDensity.current) { Stroke(6.dp.toPx(), cap = StrokeCap.Round) }

    Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(272.dp), contentAlignment = Alignment.Center) {
            androidx.compose.animation.AnimatedVisibility(busy || mode == GaugeMode.Done, enter = fadeIn(tween(300)) + scaleIn(tween(400), 0.9f), exit = fadeOut(tween(200)) + scaleOut(tween(300), 0.9f)) {
                CircularWavyProgressIndicator(
                    progress = { if (mode == GaugeMode.Done) 1f else progress },
                    modifier = Modifier.fillMaxSize(),
                    color = ring,
                    trackColor = scheme.surfaceContainerHighest,
                    stroke = stroke,
                    trackStroke = stroke,
                )
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(224.dp).graphicsLayer {
                    val scale = press * pulse
                    scaleX = scale
                    scaleY = scale
                }.then(
                    if (mode == GaugeMode.Idle) {
                        Modifier.clip(androidx.compose.foundation.shape.CircleShape).pressTracking { pressed = it }
                            .clickable(remember { MutableInteractionSource() }, null, onClick = onStart)
                    } else {
                        Modifier
                    },
                ),
            ) {
                Box(
                    Modifier.fillMaxSize().graphicsLayer { rotationZ = turn }
                        .clip(MorphShape(shape, morph.value)),
                ) {
                    Surface(color = container, modifier = Modifier.fillMaxSize()) {}
                }
                AnimatedContent(
                    mode,
                    transitionSpec = {
                        (fadeIn(tween(220, 80)) + scaleIn(tween(320), 0.8f)) togetherWith (fadeOut(tween(120)) + scaleOut(tween(200), 1.1f))
                    },
                    label = "gaugeContent",
                ) { shown ->
                    CompositionLocalProvider(LocalContentColor provides content) {
                        GaugeContent(shown, state)
                    }
                }
            }
        }
    }
}

@Composable
private fun GaugeContent(mode: GaugeMode, state: SpeedState?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        when (mode) {
            GaugeMode.Idle -> {
                Icon(Icons.Rounded.Speed, null, Modifier.size(44.dp))
                Text(stringResource(Res.string.speed_start), style = MaterialTheme.typography.headlineMediumEmphasized)
            }
            GaugeMode.Ping -> {
                Text(stringResource(Res.string.speed_ping), style = MaterialTheme.typography.labelLarge)
                val ping = state?.ping
                AnimatedContent(ping, label = "pingValue") { value ->
                    Text(value?.toString() ?: "…", style = MaterialTheme.typography.displayLargeEmphasized)
                }
                Text(stringResource(Res.string.speed_ms), style = MaterialTheme.typography.titleMedium)
            }
            GaugeMode.Download, GaugeMode.Upload, GaugeMode.Done -> {
                val download = mode != GaugeMode.Upload
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(if (download) Icons.Rounded.ArrowDownward else Icons.Rounded.ArrowUpward, null, Modifier.size(18.dp))
                    Text(stringResource(if (download) Res.string.speed_down else Res.string.speed_up), style = MaterialTheme.typography.labelLarge)
                }
                val target = when (mode) {
                    GaugeMode.Done -> state?.download ?: 0.0
                    else -> state?.live ?: 0.0
                }
                val value by animateFloatAsState(target.toFloat(), spring(1f, 60f), label = "speedValue")
                Text(mbps(value.toDouble()), style = MaterialTheme.typography.displayLargeEmphasized)
                Text(stringResource(Res.string.speed_mbps), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun StatRow(mode: GaugeMode, state: SpeedState?) {
    val ms = stringResource(Res.string.speed_ms)
    AnimatedVisibility(state?.ping != null && mode != GaugeMode.Ping, enter = RevealEnter, exit = RevealExit) {
        val parts = buildList {
            state?.ping?.let { add(stringResource(Res.string.speed_ping) + " " + it + " " + ms) }
            state?.jitter?.let { add(stringResource(Res.string.speed_jitter).lowercase() + " " + it + " " + ms) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.NetworkPing, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(parts.joinToString(" · "), style = MaterialTheme.typography.titleSmall)
        }
    }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        val outer = 24.dp
        val inner = 6.dp
        StatTile(
            Modifier.weight(1f), RoundedCornerShape(outer, inner, inner, outer), Icons.Rounded.ArrowDownward, Res.string.speed_down,
            state?.download?.toFloat() ?: state?.live?.toFloat()?.takeIf { mode == GaugeMode.Download }, stringResource(Res.string.speed_mbps), mode == GaugeMode.Download, { mbps(it.toDouble()) },
        )
        StatTile(
            Modifier.weight(1f), RoundedCornerShape(inner, outer, outer, inner), Icons.Rounded.ArrowUpward, Res.string.speed_up,
            state?.upload?.toFloat() ?: state?.live?.toFloat()?.takeIf { mode == GaugeMode.Upload }, stringResource(Res.string.speed_mbps), mode == GaugeMode.Upload, { mbps(it.toDouble()) },
        )
    }
}

@Composable
private fun StatTile(modifier: Modifier, shape: Shape, icon: ImageVector, label: StringResource, value: Float?, unit: String, active: Boolean, format: (Float) -> String) {
    val scheme = MaterialTheme.colorScheme
    val container by animateColorAsState(if (active) scheme.primaryContainer else scheme.surfaceContainer, tween(300), label = "tile")
    val content by animateColorAsState(if (active) scheme.onPrimaryContainer else scheme.onSurface, tween(300), label = "tileText")
    val shown by animateFloatAsState(value ?: 0f, spring(1f, 80f), label = "tileValue")
    Surface(shape = shape, color = container, contentColor = content, modifier = modifier.fillMaxHeight()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(icon, null, Modifier.size(16.dp), tint = if (active) content else scheme.primary)
                ButtonText(stringResource(label), style = MaterialTheme.typography.labelMedium)
            }
            AnimatedContent(value != null, label = "tileHas") { has ->
                Text(if (has) format(shown) else "—", style = MaterialTheme.typography.headlineSmallEmphasized, maxLines = 1)
            }
            Text(unit, style = MaterialTheme.typography.labelSmall, color = content.copy(alpha = 0.7f))
        }
    }
}

@Composable
private fun Chart(mode: GaugeMode, curves: Map<SpeedPhase, List<Float>>) {
    val scheme = MaterialTheme.colorScheme
    val down = curves[SpeedPhase.Download].orEmpty()
    val up = curves[SpeedPhase.Upload].orEmpty()
    val top = (down + up).maxOrNull()?.coerceAtLeast(1f) ?: 1f
    val scale by animateFloatAsState(top, tween(400), label = "chartScale")
    val upAlpha by animateFloatAsState(if (up.isNotEmpty()) 1f else 0f, tween(300), label = "chartUp")
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Legend(scheme.primary, stringResource(Res.string.speed_down))
                if (up.isNotEmpty() || mode == GaugeMode.Upload) Legend(scheme.tertiary, stringResource(Res.string.speed_up))
            }
            Canvas(Modifier.fillMaxWidth().height(96.dp)) {
                fun curve(points: List<Float>, color: Color, alpha: Float) {
                    if (points.size < 2 || alpha <= 0f) return
                    val total = 45f
                    val step = size.width / total
                    val path = Path()
                    points.forEachIndexed { index, value ->
                        val x = index * step
                        val y = size.height - (value / scale).coerceIn(0f, 1f) * size.height * 0.92f
                        if (index == 0) {
                            path.moveTo(x, y)
                        } else {
                            val px = (index - 1) * step
                            val py = size.height - (points[index - 1] / scale).coerceIn(0f, 1f) * size.height * 0.92f
                            path.cubicTo(px + step / 2, py, x - step / 2, y, x, y)
                        }
                    }
                    val fill = Path().apply {
                        addPath(path)
                        lineTo((points.size - 1) * step, size.height)
                        lineTo(0f, size.height)
                        close()
                    }
                    drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.28f * alpha), Color.Transparent)))
                    drawPath(path, color.copy(alpha = alpha), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
                    val last = points.last()
                    drawCircle(color.copy(alpha = alpha), 4.5.dp.toPx(), Offset((points.size - 1) * step, size.height - (last / scale).coerceIn(0f, 1f) * size.height * 0.92f))
                }
                curve(down, scheme.primary, 1f)
                curve(up, scheme.tertiary, upAlpha)
            }
        }
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.size(10.dp)) { drawCircle(color) }
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Summary(result: SpeedState, server: String?) {
    val down = result.download ?: 0.0
    val up = result.upload ?: 0.0
    val ping = result.ping ?: 999
    val jitter = result.jitter ?: 0
    val grade = when {
        down >= 100 && ping < 60 -> Res.string.speed_grade_great to Res.string.speed_grade_great_desc
        down >= 25 -> Res.string.speed_grade_good to Res.string.speed_grade_good_desc
        down >= 5 -> Res.string.speed_grade_ok to Res.string.speed_grade_ok_desc
        else -> Res.string.speed_grade_slow to Res.string.speed_grade_slow_desc
    }
    var step by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        repeat(3) {
            kotlinx.coroutines.delay(140)
            step++
        }
    }
    GapColumn {
      Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        AnimatedVisibility(step >= 1, enter = RevealEnter) {
            val scheme = MaterialTheme.colorScheme
            Surface(shape = RoundedCornerShape(24.dp, 24.dp, 6.dp, 6.dp), color = scheme.secondaryContainer, contentColor = scheme.onSecondaryContainer, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(grade.first), style = MaterialTheme.typography.titleLargeEmphasized)
                    Text(stringResource(grade.second), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        AnimatedVisibility(step >= 2, enter = RevealEnter) {
            val checks = listOf(
                Triple(Res.string.speed_can_4k, Res.string.speed_can_4k_desc, down >= 25),
                Triple(Res.string.speed_can_calls, Res.string.speed_can_calls_desc, down >= 3 && up >= 2 && ping < 150 && jitter < 40),
                Triple(Res.string.speed_can_games, Res.string.speed_can_games_desc, ping < 60 && jitter < 20),
                Triple(Res.string.speed_can_upload, Res.string.speed_can_upload_desc, up >= 20),
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                checks.forEachIndexed { index, (title, desc, ok) ->
                    val shape = RoundedCornerShape(6.dp, 6.dp, if (index == checks.lastIndex) 24.dp else 6.dp, if (index == checks.lastIndex) 24.dp else 6.dp)
                    SettingRow(shape, stringResource(title), stringResource(desc)) {
                        Icon(
                            if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.Cancel,
                            null,
                            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
      }
        AnimatedVisibility(step >= 3, enter = RevealEnter) {
            val place = result.place
            SectionGroup(
                title = stringResource(Res.string.speed_details),
                rows = buildList<@Composable (Shape) -> Unit> {
                    result.loadedPing?.let { loaded ->
                        add { SettingRow(it, stringResource(Res.string.speed_loaded), stringResource(Res.string.speed_loaded_desc), Icons.Rounded.NetworkCheck, "$loaded ${stringResource(Res.string.speed_ms)}") }
                    }
                    if (down > 0) {
                        val seconds = (8 * 1024.0 / down).toInt().coerceAtLeast(1)
                        val time = if (seconds < 90) stringResource(Res.string.speed_sec, seconds) else stringResource(Res.string.speed_min, (seconds + 30) / 60)
                        add { SettingRow(it, stringResource(Res.string.speed_file), null, Icons.Rounded.Download, time) }
                    }
                    add { SettingRow(it, stringResource(Res.string.speed_through), null, Icons.Rounded.VpnLock, server ?: stringResource(Res.string.speed_direct)) }
                    if (place != null) {
                        val where = listOfNotNull(place.city, place.colo?.let { "($it)" }).joinToString(" ").ifEmpty { null }
                        if (where != null) add { SettingRow(it, stringResource(Res.string.speed_place), null, Icons.Rounded.Place, "Cloudflare · $where") }
                        val ip = listOfNotNull(place.ip, place.isp).joinToString(" · ").ifEmpty { null }
                        if (ip != null) add { SettingRow(it, stringResource(Res.string.speed_ip), null, Icons.Rounded.Public, ip) }
                    }
                    add { SettingRow(it, stringResource(Res.string.speed_used), null, Icons.Rounded.DataUsage, formatBytes(result.used)) }
                },
            )
        }
    }
}

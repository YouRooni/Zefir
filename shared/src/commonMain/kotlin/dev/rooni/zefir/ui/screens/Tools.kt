package dev.rooni.zefir.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.rooni.zefir.app.AppController
import dev.rooni.zefir.app.ConnectionInfo
import dev.rooni.zefir.app.ExitIp
import dev.rooni.zefir.model.LogLine
import dev.rooni.zefir.model.TunnelState
import dev.rooni.zefir.platform.Haptic
import dev.rooni.zefir.platform.WifiAccess
import dev.rooni.zefir.res.*
import dev.rooni.zefir.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

private enum class LogFilter { All, Important, Errors }

private fun LogLine.important() = level == "error" || level == "warning"

private val LogTime = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT)

@Composable
fun LogsScreen(controller: AppController, onBack: () -> Unit) {
    val live by controller.logs.collectAsState()
    val scope = rememberCoroutineScope()
    val platform = controller.platform
    val list = rememberLazyListState()
    var frozen by remember { mutableStateOf<List<LogLine>?>(null) }
    var filter by remember { mutableStateOf(LogFilter.All) }
    val source = frozen ?: live
    val shown = remember(source, filter) {
        when (filter) {
            LogFilter.All -> source
            LogFilter.Important -> source.filter { it.important() }
            LogFilter.Errors -> source.filter { it.level == "error" }
        }.asReversed()
    }
    val fresh = frozen?.lastOrNull()?.id?.let { last -> live.count { it.id > last } } ?: 0

    fun pause() {
        if (frozen == null) frozen = controller.logs.value
    }

    fun resume() {
        platform.haptic(Haptic.Tick)
        frozen = null
        scope.launch { list.animateScrollToItem(0) }
    }

    SubScreen(
        stringResource(Res.string.logs),
        onBack,
        collapsing = false,
        actions = {
            IconButton(onClick = { if (frozen == null) { platform.haptic(Haptic.Tick); pause() } else resume() }) {
                AnimatedContent(frozen != null, label = "logsPause") { paused ->
                    Icon(
                        if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                        stringResource(if (paused) Res.string.logs_resume else Res.string.logs_pause),
                    )
                }
            }
            IconButton(onClick = { scope.launch { controller.shareLogs() } }) {
                Icon(Icons.Rounded.Share, stringResource(Res.string.logs_share))
            }
        },
        belowBar = {
            val important = source.count { it.important() }
            val errors = source.count { it.level == "error" }
            ChoiceButtons(
                listOf(
                    LogFilter.All to stringResource(Res.string.logs_all),
                    LogFilter.Important to stringResource(Res.string.logs_important, important),
                    LogFilter.Errors to stringResource(Res.string.logs_errors, errors),
                ),
                filter,
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            ) { filter = it }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            AnimatedContent(shown.isEmpty(), Modifier.fillMaxSize(), label = "logs") { empty ->
                if (empty) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(if (source.isEmpty()) Res.string.logs_empty else Res.string.logs_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    SelectionContainer {
                        LazyColumn(
                            Modifier.fillMaxSize().pressTracking { if (it) pause() },
                            state = list,
                            reverseLayout = true,
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 88.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            items(shown.size, key = { shown[it].id }) { index ->
                                LogRow(shown[index], positionShape(shown.lastIndex - index, shown.lastIndex, 20.dp, 4.dp))
                            }
                        }
                    }
                }
            }
            androidx.compose.animation.AnimatedVisibility(
                frozen != null,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp),
                enter = fadeIn(tween(160)) + scaleIn(tween(220), initialScale = 0.8f),
                exit = fadeOut(tween(120)) + scaleOut(tween(160), targetScale = 0.8f),
            ) {
                Button(onClick = ::resume, modifier = Modifier.height(52.dp), shapes = ButtonDefaults.shapes()) {
                    Icon(Icons.Rounded.KeyboardDoubleArrowDown, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    AnimatedContent(fresh, label = "logsFresh") { count ->
                        Text(if (count > 0) stringResource(Res.string.logs_new, count) else stringResource(Res.string.logs_resume))
                    }
                }
            }
        }
    }
}

@Composable
private fun LogRow(line: LogLine, shape: androidx.compose.ui.graphics.Shape) {
    val scheme = MaterialTheme.colorScheme
    val (mark, container) = when (line.level) {
        "error" -> scheme.error to scheme.errorContainer.copy(alpha = 0.45f)
        "warning" -> scheme.tertiary to scheme.surfaceContainer
        "debug" -> scheme.outline to scheme.surfaceContainerLow
        else -> scheme.primary to scheme.surfaceContainer
    }
    Surface(shape = shape, color = container, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.height(IntrinsicSize.Min).padding(start = 10.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(mark, RoundedCornerShape(2.dp)))
            Column(Modifier.weight(1f)) {
                Text(
                    LogTime.format(java.util.Date(line.time)) + "  " + line.level,
                    style = MaterialTheme.typography.labelSmall,
                    color = mark,
                )
                Text(line.text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = scheme.onSurface)
            }
        }
    }
}

private enum class ConnFilter { All, Vpn, Direct }

@Composable
fun ConnectionsScreen(controller: AppController, onBack: () -> Unit) {
    var items by remember { mutableStateOf<List<ConnectionInfo>?>(null) }
    var filter by remember { mutableStateOf(ConnFilter.All) }
    var confirmClose by remember { mutableStateOf(false) }
    var why by remember { mutableStateOf<ConnectionInfo?>(null) }
    val speed by controller.speed.collectAsState()
    val scope = rememberCoroutineScope()
    val platform = controller.platform
    LaunchedEffect(Unit) {
        while (true) {
            items = controller.connections()
            delay(1500)
        }
    }
    val all = items.orEmpty().sortedByDescending { it.startMillis }
    val vpn = all.count { !it.direct && !it.blocked }
    val shown = when (filter) {
        ConnFilter.All -> all
        ConnFilter.Vpn -> all.filter { !it.direct && !it.blocked }
        ConnFilter.Direct -> all.filter { it.direct || it.blocked }
    }
    SubScreen(
        stringResource(Res.string.conn_title),
        onBack,
        collapsing = false,
        actions = {
            IconButton(onClick = { confirmClose = true }, enabled = all.isNotEmpty()) {
                Icon(Icons.Rounded.ClearAll, stringResource(Res.string.conn_close_all))
            }
        },
        belowBar = {
            ChoiceButtons(
                listOf(
                    ConnFilter.All to stringResource(Res.string.conn_all, all.size),
                    ConnFilter.Vpn to stringResource(Res.string.conn_vpn, vpn),
                    ConnFilter.Direct to stringResource(Res.string.conn_direct, all.size - vpn),
                ),
                filter,
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            ) { filter = it }
        },
    ) { padding ->
        val list = items
        when {
            list == null -> Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
            else -> LazyColumn(
                Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                item(key = "summary") {
                    Box(Modifier.animateItem().padding(bottom = 10.dp)) { ConnSummary(speed, shown) }
                }
                if (shown.isEmpty()) {
                    item(key = "empty") {
                        Column(Modifier.animateItem().fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Rounded.Hub, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline)
                            Text(stringResource(Res.string.conn_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                items(shown.size, key = { shown[it].id }) { index ->
                    val item = shown[index]
                    Box(Modifier.animateItem()) {
                        ConnRow(item, positionShape(index, shown.lastIndex), onOpen = {
                            platform.haptic(Haptic.Tick)
                            why = item
                        }) {
                            platform.haptic(Haptic.Tick)
                            scope.launch { items = controller.closeConnection(item.id) }
                        }
                    }
                }
            }
        }
    }
    why?.let { item ->
        ConnectionWhySheet(controller, item.host.substringBeforeLast(':'), item.exit, item.rule, item.rulePayload) { why = null }
    }
    if (confirmClose) {
        AppDialog(
            stringResource(Res.string.conn_close_all_q),
            stringResource(Res.string.conn_close_all_desc),
            stringResource(Res.string.cancel),
            stringResource(Res.string.conn_close_all),
            onDismiss = { confirmClose = false },
            onConfirm = {
                confirmClose = false
                scope.launch { items = controller.closeAllConnections() }
            },
        )
    }
}

@Composable
private fun ConnSummary(speed: dev.rooni.zefir.model.Traffic, shown: List<ConnectionInfo>) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        listOf(
            Triple(Icons.Rounded.ArrowDownward, formatSpeed(speed.down), formatBytes(shown.sumOf { it.download })),
            Triple(Icons.Rounded.ArrowUpward, formatSpeed(speed.up), formatBytes(shown.sumOf { it.upload })),
        ).forEachIndexed { index, (icon, now, total) ->
            val shape = if (index == 0) RoundedCornerShape(24.dp, 6.dp, 6.dp, 24.dp) else RoundedCornerShape(6.dp, 24.dp, 24.dp, 6.dp)
            Surface(shape = shape, color = scheme.secondaryContainer, contentColor = scheme.onSecondaryContainer, modifier = Modifier.weight(1f).fillMaxHeight()) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(icon, null, Modifier.size(22.dp))
                    Column {
                        AnimatedContent(now, transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) }, label = "connSpeed") {
                            Text(it, style = MaterialTheme.typography.titleMediumEmphasized, maxLines = 1)
                        }
                        Text(total, style = MaterialTheme.typography.labelMedium, color = scheme.onSecondaryContainer.copy(alpha = 0.75f), maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnRow(item: ConnectionInfo, shape: androidx.compose.ui.graphics.Shape, onOpen: () -> Unit, onClose: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val (icon, tint, container) = when {
        item.blocked -> Triple(Icons.Rounded.Block, scheme.onErrorContainer, scheme.errorContainer)
        item.direct -> Triple(Icons.Rounded.Language, scheme.onSurfaceVariant, scheme.surfaceContainerHighest)
        else -> Triple(Icons.Rounded.VpnLock, scheme.onPrimaryContainer, scheme.primaryContainer)
    }
    val host = item.host.substringBeforeLast(':')
    val port = item.host.substringAfterLast(':', "")
    val route = when {
        item.blocked -> stringResource(Res.string.conn_blocked)
        item.direct -> stringResource(Res.string.conn_route_direct)
        else -> item.exit
    }
    val now = remember(item.id) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(item.id) {
        while (true) {
            delay(1000)
            now.value = System.currentTimeMillis()
        }
    }
    val age = if (item.startMillis > 0) ageLabel((now.value - item.startMillis) / 1000) else null
    Surface(onClick = onOpen, shape = shape, color = scheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(40.dp).background(container, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(20.dp), tint = tint)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(host, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (port.isNotEmpty()) Text(":$port", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1)
                }
                Text(
                    listOfNotNull(route, item.process.ifEmpty { null }, item.network.uppercase().ifEmpty { null }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.blocked) scheme.error else scheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull("↓ ${formatBytes(item.download)}  ↑ ${formatBytes(item.upload)}", age, item.rule.ifEmpty { null }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Close, stringResource(Res.string.conn_close))
            }
        }
    }
}

@Composable
private fun ageLabel(seconds: Long) = when {
    seconds < 60 -> stringResource(Res.string.conn_age_sec, seconds.coerceAtLeast(0))
    seconds < 3600 -> stringResource(Res.string.conn_age_min, seconds / 60)
    else -> stringResource(Res.string.conn_age_hour, seconds / 3600)
}

private enum class StepState { Waiting, Running, Ok, Failed }

private class DiagStep(val title: org.jetbrains.compose.resources.StringResource) {
    var state by mutableStateOf(StepState.Waiting)
    var detail by mutableStateOf<String?>(null)
}

@Composable
fun DiagnosticsScreen(controller: AppController, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val steps = remember {
        listOf(DiagStep(Res.string.diag_sub), DiagStep(Res.string.diag_net), DiagStep(Res.string.diag_server), DiagStep(Res.string.diag_ip))
    }
    var verdict by remember { mutableStateOf<org.jetbrains.compose.resources.StringResource?>(null) }
    var running by remember { mutableStateOf(false) }
    var services by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var servicesRunning by remember { mutableStateOf(false) }
    val platform = controller.platform

    suspend fun run() {
        running = true
        verdict = null
        steps.forEach {
            it.state = StepState.Waiting
            it.detail = null
        }
        suspend fun step(index: Int, block: suspend () -> Pair<Boolean, String?>): Boolean {
            steps[index].state = StepState.Running
            val (ok, detail) = runCatching { block() }.getOrDefault(false to null)
            steps[index].detail = detail
            steps[index].state = if (ok) StepState.Ok else StepState.Failed
            return ok
        }
        verdict = when {
            !step(0) { (controller.activeProfile != null) to controller.activeProfile?.name } -> Res.string.diag_v_sub
            !step(1) {
                val ms = controller.pingDirect()
                (ms > 0) to (if (ms > 0) getString(Res.string.servers_ms, ms) else null)
            } -> Res.string.diag_v_net
            !step(2) {
                val ms = controller.pingCurrent()
                (ms > 0) to (if (ms > 0) getString(Res.string.servers_ms, ms) else null)
            } -> Res.string.diag_v_server
            !step(3) {
                val ip: ExitIp? = controller.exitIp()
                (ip != null) to ip?.let { listOfNotNull(it.ip, it.country).joinToString(" · ") }
            } -> Res.string.diag_v_ip
            controller.tunnel.value != TunnelState.On -> Res.string.diag_v_off
            else -> Res.string.diag_v_ok
        }
        platform.haptic(if (verdict == Res.string.diag_v_ok || verdict == Res.string.diag_v_off) Haptic.Toggle else Haptic.Failed)
        running = false
    }

    LaunchedEffect(Unit) { run() }

    SubScreen(stringResource(Res.string.diag_title), onBack, collapsing = false) { padding ->
        ScrollColumn(Modifier.padding(padding)) {
            SectionGroup(
                rows = steps.map { step ->
                    { shape: androidx.compose.ui.graphics.Shape ->
                        SettingRow(shape, stringResource(step.title), step.detail) {
                            AnimatedContent(step.state, label = "diagStep") { state ->
                                Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                                    when (state) {
                                        StepState.Waiting -> Icon(Icons.Rounded.Circle, null, tint = MaterialTheme.colorScheme.outlineVariant)
                                        StepState.Running -> LoadingIndicator(Modifier.size(32.dp))
                                        StepState.Ok -> Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary)
                                        StepState.Failed -> Icon(Icons.Rounded.Close, null, tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                },
            )
            AnimatedVisibility(verdict != null, enter = RevealEnter, exit = RevealExit) {
                val ok = verdict == Res.string.diag_v_ok
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = if (ok) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    verdict?.let { Text(stringResource(it), Modifier.padding(16.dp)) }
                }
            }
            Button(
                onClick = { scope.launch { run() } },
                enabled = !running,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shapes = ButtonDefaults.shapes(),
            ) { ButtonText(stringResource(Res.string.diag_again)) }

            GroupTitle(stringResource(Res.string.diag_services))
            Text(stringResource(Res.string.diag_services_desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
            OutlinedButton(
                onClick = {
                    servicesRunning = true
                    scope.launch {
                        services = controller.serviceCheck()
                        servicesRunning = false
                    }
                },
                enabled = !servicesRunning,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shapes = ButtonDefaults.shapes(),
            ) {
                if (servicesRunning) LoadingIndicator(Modifier.size(24.dp)) else ButtonText(stringResource(Res.string.diag_services_run))
            }
            AnimatedVisibility(services != null, enter = RevealEnter, exit = RevealExit) {
                val list = services.orEmpty()
                SectionGroup(
                    rows = list.map { (name, status) ->
                        { shape: androidx.compose.ui.graphics.Shape ->
                            SettingRow(shape, name, serviceLabel(status)) {
                                val good = status == "available"
                                Icon(
                                    if (good) Icons.Rounded.Check else Icons.Rounded.Close,
                                    null,
                                    tint = if (good) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun serviceLabel(status: String) = stringResource(
    when (status) {
        "available" -> Res.string.svc_available
        "timeout" -> Res.string.svc_timeout
        "blocked", "disallowed-isp" -> Res.string.svc_blocked
        "unsupported-region", "restricted", "originals-only", "coming-soon" -> Res.string.svc_region
        else -> Res.string.svc_failed
    },
)

@Composable
fun AutoRulesScreen(controller: AppController, onBack: () -> Unit) {
    val settings by controller.settings.collectAsState()
    val platform = controller.platform
    val network by platform.network.collectAsState()
    val scope = rememberCoroutineScope()
    var ssid by remember { mutableStateOf("") }

    SubScreen(stringResource(Res.string.auto_title), onBack) { padding ->
        ScrollColumn(Modifier.padding(padding).imePadding()) {
            MasterSwitch(stringResource(Res.string.auto_master), settings.autoRules) { on ->
                scope.launch {
                    if (on) platform.requestWifiAccess()
                    controller.update({ it.copy(autoRules = on, autoPausedOn = null, autoStopped = false) })
                    controller.applyAutoRules(force = true)
                }
            }
            Text(stringResource(Res.string.auto_desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
            val resumed by platform.resumed.collectAsState()
            val access = remember(resumed, settings.autoRules) { platform.wifiAccess() }
            val battery = remember(resumed, settings.autoRules) { !platform.hasBatterySettings || platform.isBatteryUnrestricted }
            AnimatedVisibility(settings.autoRules && access != WifiAccess.Ok, enter = RevealEnter, exit = RevealExit) {
                var last by remember { mutableStateOf(access) }
                if (access != WifiAccess.Ok) last = access
                NavRow(
                    RoundedCornerShape(24.dp),
                    stringResource(
                        when (last) {
                            WifiAccess.NoBackground -> Res.string.auto_need_background
                            WifiAccess.LocationOff -> Res.string.auto_location_off
                            else -> Res.string.auto_need_location
                        },
                    ),
                    stringResource(Res.string.auto_need_desc),
                    Icons.Rounded.LocationOn,
                ) { scope.launch { platform.fixWifiAccess(last) } }
            }
            AnimatedVisibility(settings.autoRules && !battery, enter = RevealEnter, exit = RevealExit) {
                NavRow(RoundedCornerShape(24.dp), stringResource(Res.string.auto_battery), stringResource(Res.string.auto_battery_desc), Icons.Rounded.BatteryChargingFull) {
                    platform.openBatterySettings()
                }
            }
            DependsOn(settings.autoRules) {
            SwitchRow(
                RoundedCornerShape(24.dp),
                stringResource(Res.string.auto_connect),
                stringResource(Res.string.auto_connect_desc),
                settings.autoConnectElsewhere,
                Icons.Rounded.PlayCircleOutline,
            ) { on ->
                controller.update({ it.copy(autoConnectElsewhere = on) })
                controller.applyAutoRules(force = true)
            }

            val current = network.ssid
            var lastCurrent by remember { mutableStateOf(current) }
            if (current != null) lastCurrent = current
            val trusted = settings.trustedWifi.toList().sorted()
            LiveSectionGroup(
                title = stringResource(Res.string.auto_trusted),
                rows = buildList {
                    add(GroupRow("current", current != null && current !in settings.trustedWifi && settings.autoRules) { shape ->
                        SettingRow(shape, stringResource(Res.string.auto_add_current), lastCurrent, Icons.Rounded.WifiFind, onClick = {
                            current?.let { value -> controller.update({ it.copy(trustedWifi = it.trustedWifi + value) }) }
                            controller.applyAutoRules(force = true)
                        }) {
                            Icon(Icons.Rounded.Add, null, tint = MaterialTheme.colorScheme.primary)
                        }
                    })
                    add(GroupRow("field") { shape ->
                        FieldRow(shape, Icons.Rounded.Edit) {
                            AppTextField(ssid, { ssid = it }, stringResource(Res.string.auto_ssid), trailing = {
                                AnimatedVisibility(ssid.isNotBlank(), enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                                    IconButton(onClick = {
                                        val value = ssid.trim()
                                        controller.update({ it.copy(trustedWifi = it.trustedWifi + value) })
                                        controller.applyAutoRules(force = true)
                                        ssid = ""
                                    }) { Icon(Icons.Rounded.Check, stringResource(Res.string.save)) }
                                }
                            })
                        }
                    })
                    trusted.forEach { name ->
                        add(GroupRow("wifi-$name") { shape ->
                            SettingRow(shape, name, icon = Icons.Rounded.Wifi) {
                                IconButton(onClick = {
                                    controller.update({ it.copy(trustedWifi = it.trustedWifi - name) })
                                    controller.applyAutoRules(force = true)
                                }) {
                                    Icon(Icons.Rounded.DeleteOutline, stringResource(Res.string.delete))
                                }
                            }
                        })
                    }
                },
            )
            }
        }
    }
}

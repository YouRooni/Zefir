package dev.rooni.zefir.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import dev.rooni.zefir.app.AppController
import dev.rooni.zefir.model.*
import dev.rooni.zefir.platform.Haptic
import dev.rooni.zefir.res.*
import dev.rooni.zefir.ui.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@Composable
private fun ModeCard(controller: AppController, settings: AppSettings, shape: Shape) {
    CardRow(shape, stringResource(Res.string.mode_title)) {
            ChoiceButtons(
                listOf(
                    RoutingMode.Smart to stringResource(Res.string.mode_smart),
                    RoutingMode.Global to stringResource(Res.string.mode_global),
                ),
                if (settings.mode == RoutingMode.Direct) RoutingMode.Smart else settings.mode,
            ) { mode -> controller.update({ it.copy(mode = mode) }, reapply = true) }
            AnimatedContent(settings.mode == RoutingMode.Global, label = "modeHint") { global ->
                Text(
                    stringResource(if (global) Res.string.mode_global_desc else Res.string.mode_smart_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
    }
}

@Composable
private fun idleLabel(minutes: Int) = if (minutes < 60) stringResource(Res.string.idle_minutes, minutes) else stringResource(Res.string.idle_hours, minutes / 60)

@Composable
fun IdleOffScreen(controller: AppController, onBack: () -> Unit) {
    val settings by controller.settings.collectAsState()
    SubScreen("", onBack, collapsing = false) { padding ->
        ScrollColumn(Modifier.padding(padding)) {
            MasterSwitch(stringResource(Res.string.idle_off), settings.idleOff) { on -> controller.setIdleOff(on) }
            Text(
                stringResource(Res.string.idle_off_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            DependsOn(settings.idleOff) {
            SectionGroup(
                rows = listOf({ shape ->
                    OptionRow(
                        shape, stringResource(Res.string.idle_off_delay), stringResource(Res.string.idle_off_delay_desc), Icons.Rounded.Timer,
                        listOf(15, 30, 60, 120, 240).map { it to idleLabel(it) },
                        settings.idleOffMinutes,
                    ) { minutes -> controller.update({ it.copy(idleOffMinutes = minutes) }) }
                }, { shape ->
                    SwitchRow(shape, stringResource(Res.string.idle_night), stringResource(Res.string.idle_night_desc), settings.idleNightOnly, Icons.Rounded.Bedtime) { on ->
                        controller.update({ it.copy(idleNightOnly = on) })
                    }
                }),
            )
            }
        }
    }
}

@Composable
private fun DnsCard(controller: AppController, settings: AppSettings, shape: Shape) {
    CardRow(shape, stringResource(Res.string.group_dns)) {
                    ChoiceButtons(
                        listOf(
                            DnsMode.FromProfile to stringResource(Res.string.dns_profile),
                            DnsMode.Secure to stringResource(Res.string.dns_secure),
                            DnsMode.Custom to stringResource(Res.string.dns_custom),
                        ),
                        settings.dnsMode,
                    ) { mode -> controller.update({ it.copy(dnsMode = mode) }, reapply = true) }
                    AnimatedContent(settings.dnsMode, label = "dnsHint") { mode ->
                        Text(
                            stringResource(
                                when (mode) {
                                    DnsMode.FromProfile -> Res.string.dns_profile_desc
                                    DnsMode.Secure -> Res.string.dns_secure_desc
                                    DnsMode.Custom -> Res.string.dns_custom_desc
                                },
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    AnimatedVisibility(settings.dnsMode == DnsMode.Custom, enter = RevealEnter, exit = RevealExit) {
                        AppTextFieldSaved(stringResource(Res.string.dns_custom), settings.customDns) { value ->
                            controller.update({ it.copy(customDns = value) }, reapply = true)
                        }
                    }
    }
}

@Composable
private fun AppTextFieldSaved(label: String, value: String, onSave: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    AppTextField(text, { text = it }, label, trailing = {
        AnimatedVisibility(text != value, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
            IconButton(onClick = { onSave(text) }) { Icon(Icons.Rounded.Check, stringResource(Res.string.save)) }
        }
    })
}

@Composable
fun BasicSettingsScreen(controller: AppController, nav: Navigator) {
    val settings by controller.settings.collectAsState()
    val platform = controller.platform
    val region = controller.region
    SubScreen(stringResource(Res.string.hub_routing), { nav.pop() }) { padding ->
        ScrollColumn(Modifier.padding(padding)) {
            SectionGroup(
                rows = buildList<@Composable (Shape) -> Unit> {
                    add { shape -> ModeCard(controller, settings, shape) }
                    if (region != null) {
                        add {
                            SwitchRow(it, stringResource(Res.string.region_direct), stringResource(Res.string.region_direct_desc), settings.regionDirect, Icons.Rounded.Flag) { on ->
                                controller.update({ s -> s.copy(regionDirect = on) }, reapply = true)
                            }
                        }
                    }
                    add { shape ->
                        OptionRow(
                            shape, stringResource(Res.string.region), stringResource(Res.string.region_desc), Icons.Rounded.Public,
                            listOf<Pair<String?, String>>(null to stringResource(Res.string.region_auto), "" to stringResource(Res.string.region_off)) +
                                Regions.all.map { it.country to stringResource(it.title) },
                            settings.region,
                        ) { value -> controller.update({ it.copy(region = value) }, reapply = true) }
                    }
                },
            )
            SectionGroup(
                title = stringResource(Res.string.sec_exceptions),
                rows = buildList<@Composable (Shape) -> Unit> {
                    if (platform.hasPerAppRouting) {
                        add {
                            val apps = when (settings.splitMode) {
                                SplitMode.Off -> stringResource(Res.string.apps_row_desc)
                                SplitMode.Exclude -> stringResource(Res.string.w_apps_count, settings.excludedApps.size)
                                SplitMode.Only -> stringResource(Res.string.w_apps_only, settings.onlyApps.size)
                            }
                            NavRow(it, stringResource(Res.string.tile_apps), apps, Icons.Rounded.Apps) { nav.push(Route.Apps) }
                        }
                    }
                    add {
                        val sites = if (settings.rules.isEmpty()) stringResource(Res.string.sites_rules_none) else stringResource(Res.string.sites_rules_count, settings.rules.size)
                        NavRow(it, stringResource(Res.string.tile_sites), sites, Icons.Rounded.Language) { nav.push(Route.Rules) }
                    }
                    add {
                        SwitchRow(it, stringResource(Res.string.bypass_local), stringResource(Res.string.bypass_local_desc), settings.bypassLocal, Icons.Rounded.Lan) { on ->
                            controller.update({ s -> s.copy(bypassLocal = on) }, reapply = true)
                        }
                    }
                },
            )
        }
    }
}

@Composable
fun PowerSettingsScreen(controller: AppController, nav: Navigator) {
    val settings by controller.settings.collectAsState()
    val platform = controller.platform
    val resumed by platform.resumed.collectAsState()
    SubScreen(stringResource(Res.string.hub_power), { nav.pop() }) { padding ->
        ScrollColumn(Modifier.padding(padding)) {
            SectionGroup(
                title = stringResource(Res.string.sec_when_on),
                rows = buildList<@Composable (Shape) -> Unit> {
                    add {
                        SwitchRow(it, stringResource(Res.string.connect_on_start), stringResource(Res.string.connect_on_start_desc), settings.connectOnStart, Icons.Rounded.PlayCircleOutline) { on ->
                            controller.update({ s -> s.copy(connectOnStart = on) })
                        }
                    }
                    if (platform.hasWifiRules) add {
                        val scope = rememberCoroutineScope()
                        SwitchNavRow(it, stringResource(Res.string.auto_title), stringResource(Res.string.auto_short), settings.autoRules, Icons.Rounded.Wifi, { nav.push(Route.AutoRules) }) { on ->
                            scope.launch {
                                if (on) platform.requestWifiAccess()
                                controller.update({ s -> s.copy(autoRules = on, autoPausedOn = null, autoStopped = false) })
                                controller.applyAutoRules(force = true)
                            }
                        }
                    }
                },
            )
            val battery by remember(resumed) { mutableStateOf(platform.isBatteryUnrestricted) }
            SectionGroup(
                title = stringResource(Res.string.sec_keep_alive),
                rows = buildList<@Composable (Shape) -> Unit> {
                    add {
                        SwitchRow(it, stringResource(Res.string.auto_switch), stringResource(Res.string.auto_switch_desc), settings.autoSwitch, Icons.Rounded.SwapHoriz) { on ->
                            controller.update({ s -> s.copy(autoSwitch = on) })
                        }
                    }
                    if (platform.hasBatterySettings) {
                        add {
                            LinkRow(it, stringResource(Res.string.battery), stringResource(if (battery) Res.string.battery_on else Res.string.battery_desc), Icons.Rounded.BatteryChargingFull) {
                                platform.openBatterySettings()
                            }
                        }
                    }
                    if (platform.hasVpnSettings) {
                        add {
                            val lockdown = remember(resumed) { platform.vpnLockdown }
                            LinkRow(it, stringResource(Res.string.kill_switch), stringResource(if (lockdown == true) Res.string.kill_switch_on else Res.string.kill_switch_desc), Icons.Rounded.Shield) {
                                platform.openVpnSettings()
                            }
                        }
                    }
                },
            )
            if (platform.hasIdleOff) {
                SectionGroup(
                    title = stringResource(Res.string.sec_battery_save),
                    rows = listOf({
                        SwitchNavRow(
                            it, stringResource(Res.string.idle_off),
                            if (settings.idleOff) idleLabel(settings.idleOffMinutes) + if (settings.idleNightOnly) " · " + stringResource(Res.string.idle_night) else "" else null,
                            settings.idleOff, Icons.Rounded.Bedtime, { nav.push(Route.IdleOff) },
                        ) { on -> controller.setIdleOff(on) }
                    }),
                )
            }
        }
    }
}

@Composable
fun SubscriptionSettingsScreen(controller: AppController, nav: Navigator) {
    val settings by controller.settings.collectAsState()
    SubScreen(stringResource(Res.string.hub_subs), { nav.pop() }) { padding ->
        ScrollColumn(Modifier.padding(padding).imePadding()) {
            SectionGroup(
                title = stringResource(Res.string.sec_update),
                rows = listOf(
                    { shape -> AutoUpdateRow(controller, shape) },
                    {
                        SwitchRow(it, stringResource(Res.string.reminders), stringResource(Res.string.reminders_desc), settings.reminders, Icons.Rounded.NotificationsActive) { on ->
                            controller.update({ s -> s.copy(reminders = on) })
                        }
                    },
                ),
            )
            SectionGroup(
                title = stringResource(Res.string.sec_check),
                rows = listOf({ shape ->
                    OptionRow(
                        shape, stringResource(Res.string.ping_method),
                        if (settings.pingMethod == PingMethod.Tcp) stringResource(Res.string.ping_tcp_desc) else stringResource(Res.string.ping_url_desc_host, testHost(settings.testUrl)),
                        Icons.Rounded.Speed,
                        listOf(PingMethod.Url to stringResource(Res.string.ping_url), PingMethod.Tcp to stringResource(Res.string.ping_tcp)),
                        settings.pingMethod,
                    ) { method -> controller.setPingMethod(method) }
                }),
            )
            DeviceFields(controller, settings)
        }
    }
}

@Composable
private fun DeviceFields(controller: AppController, settings: AppSettings) {
    GroupTitle(stringResource(Res.string.device_group))
    val own = controller.platform.deviceHeaders
    var deviceEdits by remember { mutableStateOf(0) }
    val refreshNow = rememberUpdatedState {
        if (deviceEdits > 0) {
            deviceEdits = 0
            controller.activeProfile?.let { controller.refreshProfile(it.id) }
        }
    }
    DisposableEffect(Unit) { onDispose { refreshNow.value() } }
    val saveDevice = { transform: (AppSettings) -> AppSettings ->
        controller.update(transform)
        deviceEdits++
        Unit
    }
    SectionGroup(
        rows = listOf(
            { shape ->
                SwitchRow(shape, stringResource(Res.string.device_send), stringResource(Res.string.device_send_desc), settings.sendDevice, Icons.Rounded.Security) { on ->
                    saveDevice { it.copy(sendDevice = on) }
                }
            },
        ),
    )
    // в Zefir X подмены устройства нет, только вкл/выкл настоящих данных
    if (!PLAY_BUILD) {
        DependsOn(settings.sendDevice) { DeviceFieldRows(own, settings, saveDevice) }
        GroupCaption(stringResource(Res.string.device_desc))
    }
}

@Composable
private fun DeviceFieldRows(own: Map<String, String>, settings: AppSettings, saveDevice: ((AppSettings) -> AppSettings) -> Unit) {
    SectionGroup(
        rows = listOf(
            { shape ->
                LiveField(stringResource(Res.string.device_hwid), stringResource(Res.string.device_hwid_hint), settings.deviceHwid.ifEmpty { own["x-hwid"].orEmpty() }, shape, Icons.Rounded.Fingerprint) { value ->
                    saveDevice { it.copy(deviceHwid = value.trim().takeUnless { text -> text == own["x-hwid"] }.orEmpty()) }
                }
            },
            { shape ->
                LiveField(stringResource(Res.string.device_model), stringResource(Res.string.device_empty_hint), settings.deviceModel.ifEmpty { own["x-device-model"].orEmpty() }, shape, Icons.Rounded.Smartphone) { value ->
                    saveDevice { it.copy(deviceModel = value.trim().takeUnless { text -> text == own["x-device-model"] }.orEmpty()) }
                }
            },
            { shape ->
                LiveField(stringResource(Res.string.device_os), stringResource(Res.string.device_empty_hint), settings.deviceOsVersion.ifEmpty { own["x-ver-os"].orEmpty() }, shape, if (LocalPlatform.current.isDesktop) Icons.Rounded.DesktopWindows else Icons.Rounded.Android) { value ->
                    saveDevice { it.copy(deviceOsVersion = value.trim().takeUnless { text -> text == own["x-ver-os"] }.orEmpty()) }
                }
            },
        ),
    )
}

// Tasker, MacroDroid, adb: ключ и описание команд (docs/AUTOMATION.md).
// Сам переключатель живёт в «Для опытных», тут подраздел.
@Composable
fun AutomationScreen(controller: AppController, onBack: () -> Unit) {
    val settings by controller.settings.collectAsState()
    val platform = controller.platform
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    var confirmNewKey by remember { mutableStateOf(false) }
    SubScreen(stringResource(Res.string.automation), onBack) { padding ->
        ScrollColumn(Modifier.padding(padding)) {
            MasterSwitch(stringResource(Res.string.automation_master), settings.automation) { on -> controller.setAutomation(on) }
            Text(stringResource(Res.string.automation_desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
            DependsOn(settings.automation) {
                SectionGroup(
                    rows = listOf(
                        { shape ->
                            SettingRow(shape, stringResource(Res.string.automation_key), settings.automationKey.ifEmpty { "—" }, Icons.Rounded.Key, onClick = {
                                if (settings.automationKey.isNotEmpty()) platform.writeClipboard(settings.automationKey, secret = true)
                            }) {
                                FilledTonalIconButton({
                                    platform.haptic(Haptic.Tick)
                                    confirmNewKey = true
                                }, shapes = IconButtonDefaults.shapes()) {
                                    Icon(Icons.Rounded.Autorenew, stringResource(Res.string.automation_new_key))
                                }
                            }
                        },
                        { shape ->
                            NavRow(shape, stringResource(Res.string.automation_docs), stringResource(Res.string.automation_docs_desc), Icons.Rounded.Code) {
                                uri.openUri(AUTOMATION_DOCS)
                            }
                        },
                    ),
                )
            }
        }
    }
    if (confirmNewKey) {
        AppDialog(
            stringResource(Res.string.automation_new_key_q),
            stringResource(Res.string.automation_new_key_text),
            stringResource(Res.string.cancel),
            stringResource(Res.string.automation_new_key_action),
            onDismiss = { confirmNewKey = false },
            onConfirm = {
                confirmNewKey = false
                platform.haptic(Haptic.Toggle)
                controller.newAutomationKey()
            },
            icon = Icons.Rounded.Key,
            warning = true,
        )
    }
}

private const val AUTOMATION_DOCS = "https://github.com/YouRooni/Zefir/blob/main/docs/AUTOMATION.md"

@Composable
fun ToolsSettingsScreen(controller: AppController, nav: Navigator) {
    var confirmReset by remember { mutableStateOf(false) }
    var confirmExport by remember { mutableStateOf(false) }
    var backupFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    SubScreen(stringResource(Res.string.hub_tools), { nav.pop() }) { padding ->
        ScrollColumn(Modifier.padding(padding)) {
            SectionGroup(
                title = stringResource(Res.string.sec_net_check),
                rows = listOf(
                    { NavRow(it, stringResource(Res.string.speed_title), stringResource(Res.string.speed_desc), Icons.Rounded.Speed) { nav.push(Route.SpeedTest) } },
                    { NavRow(it, stringResource(Res.string.diag_title), stringResource(Res.string.diag_desc), Icons.Rounded.MedicalServices) { nav.push(Route.Diagnostics) } },
                    { NavRow(it, stringResource(Res.string.site_title), stringResource(Res.string.site_desc), Icons.AutoMirrored.Rounded.AltRoute) { nav.push(Route.SiteCheck()) } },
                ),
            )
            SectionGroup(
                title = stringResource(Res.string.sec_inside),
                rows = listOf(
                    { NavRow(it, stringResource(Res.string.logs), stringResource(Res.string.logs_desc), Icons.AutoMirrored.Rounded.Article) { nav.push(Route.Logs) } },
                    { NavRow(it, stringResource(Res.string.conn_title), stringResource(Res.string.conn_desc), Icons.Rounded.Hub) { nav.push(Route.Connections) } },
                    { NavRow(it, stringResource(Res.string.dns_title), stringResource(Res.string.dns_desc), Icons.Rounded.Dns) { nav.push(Route.DnsLog) } },
                ),
            )
            SectionGroup(
                title = stringResource(Res.string.group_backup),
                rows = listOf(
                    { SettingRow(it, stringResource(Res.string.backup_export), stringResource(Res.string.backup_export_desc), Icons.Rounded.Upload, onClick = { confirmExport = true }) },
                    {
                        SettingRow(it, stringResource(Res.string.backup_import), stringResource(Res.string.backup_import_desc), Icons.Rounded.Download, onClick = {
                            scope.launch {
                                val text = controller.platform.pickFile() ?: return@launch
                                backupFailed = !controller.importBackup(text)
                            }
                        })
                    },
                ),
            )
            MessageBanner(if (backupFailed) stringResource(Res.string.backup_failed) else null)
            SectionGroup(
                title = stringResource(Res.string.sec_reset),
                rows = listOf({ SettingRow(it, stringResource(Res.string.reset), stringResource(Res.string.reset_desc), Icons.Rounded.RestartAlt, onClick = { confirmReset = true }) }),
            )
        }
    }
    if (confirmReset) {
        AppDialog(
            stringResource(Res.string.reset_q),
            stringResource(Res.string.reset_desc),
            stringResource(Res.string.cancel),
            stringResource(Res.string.reset_action),
            onDismiss = { confirmReset = false },
            onConfirm = {
                confirmReset = false
                controller.resetSettings()
            },
        )
    }
    if (confirmExport) {
        AppDialog(
            stringResource(Res.string.backup_warn_title),
            stringResource(Res.string.backup_warn_text),
            stringResource(Res.string.cancel),
            stringResource(Res.string.backup_warn_save),
            onDismiss = { confirmExport = false },
            onConfirm = {
                confirmExport = false
                scope.launch { controller.exportBackup() }
            },
            icon = Icons.Rounded.Key,
            warning = true,
        )
    }
}

@Composable
private fun ThemeSwatch(active: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    val platform = LocalPlatform.current
    val corner by animateDpAsState(if (active) 16.dp else 28.dp, tween(220), label = "corner")
    val scale by animateFloatAsState(if (active) 1f else 0.86f, tween(220), label = "scale")
    Box(
        Modifier.size(56.dp).graphicsLayer {
            scaleX = scale
            scaleY = scale
        }.clip(RoundedCornerShape(corner)).clickable {
            platform.haptic(Haptic.Tick)
            onClick()
        },
        contentAlignment = Alignment.Center,
    ) {
        content()
        androidx.compose.animation.AnimatedVisibility(active, enter = scaleIn(tween(180)) + fadeIn(), exit = scaleOut(tween(140)) + fadeOut()) {
            Icon(Icons.Rounded.Check, null, tint = Color.White)
        }
    }
}

@Composable
private fun AccentPicker(settings: AppSettings, onSolid: (Long) -> Unit, onPalette: (Int) -> Unit) {
    val label: @Composable (org.jetbrains.compose.resources.StringResource) -> Unit = { res ->
        Text(stringResource(res), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
    }
    label(Res.string.theme_solid)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(horizontal = 16.dp)) {
        item {
            ThemeSwatch(settings.palette == MONO_PALETTE, { onPalette(MONO_PALETTE) }) {
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxHeight().background(Color(0xFFE6E6E6)))
                    Box(Modifier.weight(1f).fillMaxHeight().background(Color(0xFF303030)))
                }
            }
        }
        items(AccentChoices) { color ->
            ThemeSwatch(settings.palette == -1 && color == settings.accent, { onSolid(color) }) {
                Box(Modifier.fillMaxSize().background(Color(color)))
            }
        }
    }
    label(Res.string.theme_multi)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(horizontal = 16.dp)) {
        items(MultiPalettes.size) { index ->
            val seeds = MultiPalettes[index]
            ThemeSwatch(settings.palette == index, { onPalette(index) }) {
                Column(Modifier.fillMaxSize()) {
                    listOf(0 to 1, 2 to 3).forEach { (left, right) ->
                        Row(Modifier.weight(1f).fillMaxWidth()) {
                            Box(Modifier.weight(1f).fillMaxHeight().background(Color(seeds[left])))
                            Box(Modifier.weight(1f).fillMaxHeight().background(Color(seeds[right])))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AppSettingsScreen(controller: AppController, nav: Navigator) {
    val settings by controller.settings.collectAsState()
    val hasSystemColors = controller.platform.systemColorScheme(false) != null
    SubScreen(stringResource(Res.string.set_app_screen), { nav.pop() }) { padding ->
        ScrollColumn(Modifier.padding(padding)) {
            val pickerVisible = !settings.dynamicColor || !hasSystemColors
            LiveSectionGroup(
                rows = listOf(
                    GroupRow("theme") { shape ->
                        CardRow(shape, stringResource(Res.string.theme)) {
                            ChoiceButtons(
                                listOf(
                                    ThemeMode.System to stringResource(Res.string.theme_system),
                                    ThemeMode.Light to stringResource(Res.string.theme_light),
                                    ThemeMode.Dark to stringResource(Res.string.theme_dark),
                                ),
                                settings.theme,
                            ) { theme -> controller.update({ it.copy(theme = theme) }) }
                        }
                    },
                    GroupRow("dynamic", hasSystemColors) {
                        SwitchRow(it, stringResource(Res.string.dynamic_color), stringResource(Res.string.dynamic_color_desc), settings.dynamicColor, Icons.Rounded.ColorLens) { on ->
                            controller.update({ s -> s.copy(dynamicColor = on) })
                        }
                    },
                    GroupRow("accent", pickerVisible) { shape ->
                        SpotlightAnchor(stringResource(Res.string.accent), shape) {
                            Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainer) {
                                Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(stringResource(Res.string.accent), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
                                    AccentPicker(
                                        settings,
                                        onSolid = { color -> controller.update({ s -> s.copy(accent = color, palette = -1) }) },
                                        onPalette = { index -> controller.update({ s -> s.copy(palette = index) }) },
                                    )
                                }
                            }
                        }
                    },
                    // плавающая панель пока убрана, мб вернём
                    // GroupRow("floatingNav") {
                    //     SwitchRow(it, stringResource(Res.string.floating_nav), stringResource(Res.string.floating_nav_desc), settings.floatingNav, Icons.Rounded.CallToAction) { on ->
                    //         controller.update({ s -> s.copy(floatingNav = on) })
                    //     }
                    // },
                    GroupRow("amoled") {
                        SwitchRow(it, stringResource(Res.string.amoled), stringResource(Res.string.amoled_desc), settings.amoled, Icons.Rounded.DarkMode, settings.theme != ThemeMode.Light) { on ->
                            controller.update({ s -> s.copy(amoled = on) })
                        }
                    },
                ),
            )
            if (controller.platform.isDesktop) {
                val desk = controller.platform
                SectionGroup(
                    title = desk.desktopOsName.ifEmpty { stringResource(Res.string.sec_windows) },
                    rows = listOfNotNull<@Composable (Shape) -> Unit>(
                        rowIf(desk.hasDesktopTun) {
                            SwitchRow(it, stringResource(Res.string.desk_tun), stringResource(Res.string.desk_tun_desc), settings.desktopTun, Icons.Rounded.Lan) { on ->
                                controller.update({ s -> s.copy(desktopTun = on) })
                                controller.reconnect()
                            }
                        },
                        {
                            SwitchRow(it, stringResource(Res.string.desk_tray), stringResource(Res.string.desk_tray_desc), settings.closeToTray, Icons.Rounded.Minimize) { on ->
                                controller.update({ s -> s.copy(closeToTray = on) })
                            }
                        },
                        rowIf(desk.hasCustomFrame) {
                            SwitchRow(it, stringResource(Res.string.desk_frame), stringResource(Res.string.desk_frame_desc), settings.customTitleBar, Icons.Rounded.WebAsset) { on ->
                                controller.update({ s -> s.copy(customTitleBar = on) })
                            }
                        },
                    ),
                )
            }
            SectionGroup(
                title = stringResource(Res.string.sec_other),
                rows = buildList<@Composable (Shape) -> Unit> {
                    add { shape -> ServerLayoutCard(controller, settings, shape) }
                    add { NavRow(it, stringResource(Res.string.tile_home), stringResource(Res.string.home_row_desc), Icons.Rounded.Home) { nav.push(Route.Widgets) } }
                    add {
                        SwitchRow(it, stringResource(Res.string.no_animations), stringResource(Res.string.no_animations_desc), settings.noAnimations, Icons.Rounded.Animation) { on ->
                            controller.update({ s -> s.copy(noAnimations = on) })
                        }
                    }
                    if (controller.platform.hasHaptics) {
                        add {
                            SwitchRow(it, stringResource(Res.string.haptics), stringResource(Res.string.haptics_desc), settings.haptics, Icons.Rounded.Vibration) { on ->
                                controller.update({ s -> s.copy(haptics = on) })
                            }
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun ServerLayoutCard(controller: AppController, settings: AppSettings, shape: Shape) {
    CardRow(shape, stringResource(Res.string.servers_layout)) {
                    ChoiceButtons(
                        listOf(
                            ServerLayout.List to stringResource(Res.string.servers_layout_list),
                            ServerLayout.Grid to stringResource(Res.string.servers_layout_grid),
                        ),
                        settings.serverLayout,
                    ) { layout -> controller.update({ it.copy(serverLayout = layout) }) }
                    AnimatedVisibility(settings.serverLayout == ServerLayout.Grid, enter = RevealEnter, exit = RevealExit) {
                        GapColumn {
                            Text(stringResource(Res.string.servers_density), style = MaterialTheme.typography.titleMedium)
                            ChoiceButtons(
                                listOf(
                                    ServerDensity.Roomy to stringResource(Res.string.servers_density_roomy),
                                    ServerDensity.Normal to stringResource(Res.string.servers_density_normal),
                                    ServerDensity.Compact to stringResource(Res.string.servers_density_compact),
                                ),
                                settings.serverDensity,
                            ) { density -> controller.update({ it.copy(serverDensity = density) }) }
                        }
                    }
    }
}

@Composable
fun NotificationSettingsScreen(controller: AppController, onBack: () -> Unit) {
    val settings by controller.settings.collectAsState()

    SubScreen("", onBack, collapsing = false) { padding ->
        ScrollColumn(Modifier.padding(padding)) {
            MasterSwitch(stringResource(Res.string.notif_master), settings.notifEnabled) { on ->
                controller.update({ it.copy(notifEnabled = on) })
            }
            AnimatedVisibility(!settings.notifEnabled, enter = RevealEnter, exit = RevealExit) {
                Text(
                    stringResource(Res.string.notif_master_off_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            SectionGroup(
                footer = false,
                rows = listOf(
                    { SwitchRow(it, stringResource(Res.string.notif_stop), stringResource(Res.string.notif_stop_desc), settings.notifStopButton, Icons.Rounded.PowerSettingsNew, settings.notifEnabled) { on -> controller.update({ s -> s.copy(notifStopButton = on) }) } },
                    { SwitchRow(it, stringResource(Res.string.notif_pause), stringResource(Res.string.notif_pause_desc), settings.notifPauseButton, Icons.Rounded.Pause, settings.notifEnabled) { on -> controller.update({ s -> s.copy(notifPauseButton = on) }) } },
                    { SwitchRow(it, stringResource(Res.string.notif_speed), stringResource(Res.string.notif_speed_desc), settings.notifLiveSpeed, Icons.Rounded.Speed, settings.notifEnabled) { on -> controller.update({ s -> s.copy(notifLiveSpeed = on) }) } },
                    { SwitchRow(it, stringResource(Res.string.notif_server), stringResource(Res.string.notif_server_desc), settings.notifShowServer, Icons.Rounded.Dns, settings.notifEnabled) { on -> controller.update({ s -> s.copy(notifShowServer = on) }) } },
                ),
            )
        }
    }
}

@Composable
fun <T> OptionRow(
    shape: Shape,
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    options: List<Pair<T, String>>,
    selected: T,
    valueLabel: String? = null,
    extra: List<MenuEntry> = emptyList(),
    onSelect: (T) -> Unit,
) {
    val platform = LocalPlatform.current
    val menu = rememberMenuState()
    Box(Modifier.menuGesture(menu, platform, holdMillis = SLIDE_HOLD_MILLIS)) {
        SettingRow(shape, title, description, icon, valueLabel ?: options.firstOrNull { it.first == selected }?.second, onClick = { menu.show() }) {
            Icon(Icons.Rounded.UnfoldMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        MenuForState(menu, options.map { (value, label) -> MenuEntry(label, checked = value == selected) { onSelect(value) } } + extra)
    }
}

private const val SLIDE_HOLD_MILLIS = 320L

@Composable
private fun FieldFrame(label: String, shape: Shape?, icon: androidx.compose.ui.graphics.vector.ImageVector?, field: @Composable () -> Unit) {
    if (shape == null) {
        SpotlightAnchor(label, RoundedCornerShape(28.dp), field)
    } else {
        SpotlightAnchor(label, shape) { FieldRow(shape, icon, field) }
    }
}

@Composable
fun LiveField(label: String, hint: String, initial: String, shape: Shape? = null, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, onChange: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    FieldFrame(label, shape, icon) {
        AppTextField(text, { new ->
            text = new
            onChange(new)
        }, label, hint = hint)
    }
}

@Composable
fun SavedField(label: String, hint: String, value: String, digits: Boolean = false, shape: Shape? = null, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, onSave: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    FieldFrame(label, shape, icon) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                AppTextField(text, { new -> text = if (digits) new.filter(Char::isDigit) else new }, label, hint = hint)
            }
            AnimatedVisibility(
                text != value,
                enter = fadeIn(tween(180)) + expandHorizontally(tween(220, easing = SettleEasing), expandFrom = Alignment.Start),
                exit = fadeOut(tween(120)) + shrinkHorizontally(tween(200, easing = SettleEasing), shrinkTowards = Alignment.Start),
            ) {
                IconButton(onClick = { onSave(text) }, modifier = Modifier.padding(end = 4.dp)) { Icon(Icons.Rounded.Check, stringResource(Res.string.save)) }
            }
        }
    }
}

@Composable
fun AdvancedSettingsScreen(controller: AppController, nav: Navigator) {
    val settings by controller.settings.collectAsState()
    var confirmSkipCert by remember { mutableStateOf(false) }
    if (confirmSkipCert) {
        AppDialog(
            title = stringResource(Res.string.skip_cert_confirm_title),
            text = stringResource(Res.string.skip_cert_confirm_text),
            secondary = stringResource(Res.string.cancel),
            primary = stringResource(Res.string.skip_cert_confirm_ok),
            onDismiss = { confirmSkipCert = false },
            onConfirm = {
                confirmSkipCert = false
                controller.update({ s -> s.copy(skipCertVerify = true) }, reapply = true)
            },
            icon = Icons.Rounded.GppMaybe,
            warning = true,
        )
    }
    SubScreen(stringResource(Res.string.set_advanced), { nav.pop() }) { padding ->
        ScrollColumn(Modifier.padding(padding).imePadding()) {
            SectionGroup(
                title = stringResource(Res.string.sec_net),
                rows = listOf(
                    { DnsCard(controller, settings, it) },
                    { SwitchRow(it, stringResource(Res.string.fake_ip), stringResource(Res.string.fake_ip_desc), settings.fakeIp, Icons.Rounded.Bolt) { on -> controller.update({ s -> s.copy(fakeIp = on) }, reapply = true) } },
                    { SwitchRow(it, stringResource(Res.string.dns_hijack_all), stringResource(Res.string.dns_hijack_all_desc), settings.dnsHijackAll, Icons.Rounded.Dns) { on -> controller.update({ s -> s.copy(dnsHijackAll = on) }, reapply = true) } },
                    { SwitchRow(it, stringResource(Res.string.ipv6), stringResource(Res.string.ipv6_desc), settings.ipv6, Icons.Rounded.Lan) { on -> controller.update({ s -> s.copy(ipv6 = on) }, reapply = true) } },
                    { shape ->
                        SavedField(stringResource(Res.string.mtu), stringResource(Res.string.mtu_desc), settings.mtu.toString(), digits = true, shape = shape, icon = Icons.Rounded.Straighten) { value ->
                            controller.update({ it.copy(mtu = value.toIntOrNull()?.coerceIn(1280, 9000) ?: 1500) }, reapply = true)
                        }
                    },
                ),
            )
            SectionGroup(
                title = stringResource(Res.string.sec_traffic),
                rows = listOf(
                    { shape ->
                        OptionRow(
                            shape, stringResource(Res.string.stack), stringResource(Res.string.stack_desc), Icons.Rounded.Layers,
                            listOf("system" to "System", "gvisor" to "gVisor", "mixed" to "Mixed"),
                            settings.stack,
                        ) { stack -> controller.update({ it.copy(stack = stack) }, reapply = true) }
                    },
                    { SwitchRow(it, stringResource(Res.string.sniffer), stringResource(Res.string.sniffer_desc), settings.sniffer, Icons.Rounded.TravelExplore) { on -> controller.update({ s -> s.copy(sniffer = on) }, reapply = true) } },
                    { SwitchRow(it, stringResource(Res.string.tcp_concurrent), stringResource(Res.string.tcp_concurrent_desc), settings.tcpConcurrent, Icons.AutoMirrored.Rounded.CallSplit) { on -> controller.update({ s -> s.copy(tcpConcurrent = on) }, reapply = true) } },
                    { SwitchRow(it, stringResource(Res.string.tfo), stringResource(Res.string.tfo_desc), settings.tcpFastOpen, Icons.Rounded.FastForward) { on -> controller.update({ s -> s.copy(tcpFastOpen = on) }, reapply = true) } },
                ),
            )
            SectionGroup(
                title = stringResource(Res.string.sec_check),
                rows = listOf(
                    { SwitchRow(it, stringResource(Res.string.unified_delay), stringResource(Res.string.unified_delay_desc), settings.unifiedDelay, Icons.Rounded.Timer) { on -> controller.update({ s -> s.copy(unifiedDelay = on) }, reapply = true) } },
                    { shape ->
                        SavedField(stringResource(Res.string.test_url), stringResource(Res.string.test_url_desc), settings.testUrl, shape = shape, icon = Icons.Rounded.Link) { value ->
                            controller.update({ it.copy(testUrl = value) })
                        }
                    },
                ),
            )
            SectionGroup(
                title = stringResource(Res.string.sec_debug),
                rows = listOf(
                    { SwitchRow(it, stringResource(Res.string.detailed_errors), stringResource(Res.string.detailed_errors_desc), settings.detailedErrors, Icons.Rounded.BugReport) { on -> controller.update({ s -> s.copy(detailedErrors = on) }) } },
                    { shape ->
                        OptionRow(
                            shape, stringResource(Res.string.log_level), stringResource(Res.string.logs_desc), Icons.Rounded.FilterList,
                            listOf("error" to "Error", "warning" to "Warning", "info" to "Info", "debug" to "Debug"),
                            settings.logLevel,
                        ) { level -> controller.update({ it.copy(logLevel = level) }, reapply = true) }
                    },
                    { SwitchRow(it, stringResource(Res.string.show_advanced), stringResource(Res.string.show_advanced_desc), settings.showAdvanced, Icons.Rounded.Code) { on -> controller.update({ s -> s.copy(showAdvanced = on) }) } },
                ),
            )
            if (controller.platform.hasAutomation) {
                SectionGroup(
                    title = stringResource(Res.string.sec_automation),
                    rows = listOf { shape ->
                        SwitchNavRow(shape, stringResource(Res.string.automation), null, settings.automation, Icons.Rounded.SmartToy, { nav.push(Route.Automation) }) { on ->
                            controller.setAutomation(on)
                        }
                    },
                )
            }
            SectionGroup(
                title = stringResource(Res.string.sec_security),
                rows = listOf(
                    { SwitchRow(it, stringResource(Res.string.allow_bypass), stringResource(Res.string.allow_bypass_desc), settings.allowBypass, Icons.AutoMirrored.Rounded.AltRoute) { on -> controller.update({ s -> s.copy(allowBypass = on) }, reapply = true) } },
                    { SwitchRow(it, stringResource(Res.string.skip_cert), stringResource(Res.string.skip_cert_desc), settings.skipCertVerify, Icons.Rounded.GppMaybe) { on -> if (on) confirmSkipCert = true else controller.update({ s -> s.copy(skipCertVerify = false) }, reapply = true) } },
                ),
            )
        }
    }
}

@Composable
fun RulesScreen(controller: AppController, onBack: () -> Unit) {
    val settings by controller.settings.collectAsState()
    var domain by remember { mutableStateOf("") }
    var action by remember { mutableStateOf(RuleAction.Direct) }
    var raw by remember { mutableStateOf("") }
    val platform = controller.platform

    SubScreen(stringResource(Res.string.rules_title), onBack) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize().imePadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item(key = "only-smart") {
                AnimatedVisibility(settings.mode != RoutingMode.Smart, enter = RevealEnter, exit = RevealExit) {
                    MessageBanner(stringResource(Res.string.rules_only_smart), Modifier.padding(bottom = 8.dp), Icons.Rounded.Info)
                }
            }
            item(key = "templates") {
                SectionGroup(
                    title = stringResource(Res.string.rules_templates),
                    rows = listOf({ shape ->
                        SwitchRow(shape, stringResource(Res.string.rules_ads), stringResource(Res.string.rules_ads_desc), settings.blockAds, Icons.Rounded.Block) { on ->
                            controller.update({ it.copy(blockAds = on) }, reapply = true)
                        }
                    }),
                )
            }
            item(key = "mine-title") { GroupTitle(stringResource(Res.string.rules_mine)) }
            item(key = "add") {
                Surface(shape = animatedRowShape(0, settings.rules.size, false), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.animateItem()) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        AppTextField(domain, { domain = it.trim().lowercase() }, stringResource(Res.string.rules_domain), hint = stringResource(Res.string.rules_domain_hint))
                        ChoiceButtons(
                            listOf(
                                RuleAction.Direct to stringResource(Res.string.rule_direct),
                                RuleAction.Proxy to stringResource(Res.string.rule_proxy),
                                RuleAction.Block to stringResource(Res.string.rule_block),
                            ),
                            action,
                        ) { action = it }
                        Button(
                            onClick = {
                                val value = domain.removePrefix("https://").removePrefix("http://").removePrefix("www.").substringBefore('/')
                                platform.haptic(Haptic.Toggle)
                                controller.update({ s -> s.copy(rules = s.rules.filterNot { it.value == value } + UserRule(value, action)) }, reapply = true)
                                domain = ""
                            },
                            enabled = domain.contains('.'),
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shapes = ButtonDefaults.shapes(),
                        ) {
                            Icon(Icons.Rounded.Add, null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(Res.string.rules_add))
                        }
                    }
                }
            }
            items(settings.rules.size, key = { "rule-" + settings.rules[it].value }) { index ->
                val rule = settings.rules[index]
                Box(Modifier.animateItem()) {
                    SettingRow(
                        animatedRowShape(index + 1, settings.rules.size, false),
                        rule.value,
                        stringResource(
                            when (rule.action) {
                                RuleAction.Direct -> Res.string.rule_direct
                                RuleAction.Proxy -> Res.string.rule_proxy
                                RuleAction.Block -> Res.string.rule_block
                            },
                        ),
                    ) {
                        IconButton(onClick = { controller.update({ s -> s.copy(rules = s.rules - rule) }, reapply = true) }) {
                            Icon(Icons.Rounded.DeleteOutline, stringResource(Res.string.delete))
                        }
                    }
                }
            }
            if (settings.showAdvanced) {
                item(key = "raw-title") {
                    Column(Modifier.animateItem().padding(top = 12.dp)) {
                        GroupTitle(stringResource(Res.string.rules_raw))
                        Text(
                            stringResource(Res.string.rules_raw_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        AppTextField(raw, { raw = it }, stringResource(Res.string.rules_raw_add), hint = stringResource(Res.string.rules_raw_hint), trailing = {
                            IconButton(enabled = raw.count { it == ',' } >= 1, onClick = {
                                controller.update({ s -> s.copy(rawRules = s.rawRules + raw.trim()) }, reapply = true)
                                raw = ""
                            }) { Icon(Icons.Rounded.Add, stringResource(Res.string.rules_raw_add)) }
                        })
                        Spacer(Modifier.height(8.dp))
                    }
                }
                items(settings.rawRules.size, key = { "raw-" + settings.rawRules[it] }) { index ->
                    val line = settings.rawRules[index]
                    Box(Modifier.animateItem()) {
                        SettingRow(positionShape(index, settings.rawRules.lastIndex), line) {
                            IconButton(onClick = { controller.update({ s -> s.copy(rawRules = s.rawRules - line) }, reapply = true) }) {
                                Icon(Icons.Rounded.DeleteOutline, stringResource(Res.string.delete))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun WidgetsScreen(controller: AppController, onBack: () -> Unit) {
    val settings by controller.settings.collectAsState()
    val active = settings.widgets
    val keys = remember(active) { widgetKeys(active) }
    val available = HomeWidget.entries.filter { (it != HomeWidget.SplitApps || controller.platform.hasPerAppRouting) && it != HomeWidget.Divider }
    val hidden = available.filter { it !in active } + HomeWidget.Divider
    val listState = rememberLazyListState()
    val platform = controller.platform
    var pressed by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose { controller.update({ s -> s.copy(widgets = tidyWidgets(s.widgets)) }) }
    }

    // тут порядок сразу пишется в настройки, поэтому читаем его из controller, а не из композиции
    val reorder = rememberReorderState(
        listState,
        keys = { widgetKeys(controller.settings.value.widgets) },
        gapOf = { key ->
            val order = controller.settings.value.widgets
            widgetKeys(order).indexOf(key).takeIf { it >= 0 }?.let { widgetGap(order, it) } ?: 0.dp
        },
        haptic = platform::haptic,
        canSwap = { from, to ->
            val order = controller.settings.value.widgets
            !(order[from] == HomeWidget.Divider && order[to] == HomeWidget.Divider)
        },
    ) { from, to -> controller.update({ s -> s.copy(widgets = s.widgets.toMutableList().apply { add(to, removeAt(from)) }) }) }
    val dragged = reorder.dragged
    LaunchedEffect(dragged) { if (dragged != null) pressed = null }

    SubScreen(stringResource(Res.string.widgets_title), onBack, collapsing = false) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            state = listState,
            userScrollEnabled = dragged == null,
            contentPadding = PaddingValues(16.dp),
        ) {
            item(key = "hint") {
                Text(
                    stringResource(Res.string.widgets_text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, bottom = 12.dp).animateItem(),
                )
            }
            val gaps = dragGaps(active, keys.indexOf(dragged)) { items, i -> widgetGap(items, i) }
            items(active.size, key = { keys[it] }) { index ->
                val widget = active[index]
                val key = keys[index]
                val isDragged = dragged == key
                val lifted = isDragged || pressed == key
                val isFree = reorder.isFree(key)
                val first = index == 0 || active[index - 1] == HomeWidget.Divider || widget == HomeWidget.Divider
                val last = index == active.lastIndex || active[index + 1] == HomeWidget.Divider || widget == HomeWidget.Divider
                val shape = animatedRowShape(first, last, lifted)
                val lift by animateDpAsState(if (lifted) 10.dp else 0.dp, label = "lift")
                val grow by animateFloatAsState(if (lifted) 1.03f else 1f, spring(0.6f, 600f), label = "grow")
                val gap by animateDpAsState(gaps[index], DragGapSpring, label = "widgetGap")
                Box(
                    (if (isFree) Modifier else Modifier.animateItem())
                        .zIndex(if (isFree || lifted) 1f else 0f)
                        .padding(top = gap.coerceAtLeast(0.dp))
                        .graphicsLayer {
                            translationY = reorder.translation(key, gap.toPx())
                            scaleX = grow
                            scaleY = grow
                            shadowElevation = lift.toPx()
                            this.shape = shape
                            clip = false
                        }
                        .reorderAfterLongPress(reorder, key),
                ) {
                    WidgetRow(widget, shape, added = true, onClick = {
                        controller.update({ s -> s.copy(widgets = s.widgets.toMutableList().apply { removeAt(index) }) })
                    }) {
                        Icon(
                            Icons.Rounded.DragHandle,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(40.dp).padding(8.dp)
                                .pointerInput(key) {
                                    awaitEachGesture {
                                        awaitFirstDown(requireUnconsumed = false)
                                        pressed = key
                                        platform.haptic(Haptic.Tick)
                                        val up = waitForUpOrCancellation()
                                        up?.consume()
                                        if (pressed == key) pressed = null
                                    }
                                }
                                .reorderHandle(reorder, key),
                        )
                    }
                }
            }
            item(key = "hidden-title") {
                Box(Modifier.animateItem()) { GroupTitle(stringResource(Res.string.widgets_hidden)) }
            }
            items(hidden.size, key = { "add-" + hidden[it].name }) { index ->
                val widget = hidden[index]
                val shape = animatedRowShape(index == 0, index == hidden.lastIndex, false)
                Box(Modifier.animateItem().padding(top = if (index > 0) 2.dp else 0.dp)) {
                    WidgetRow(widget, shape, added = false, onClick = {
                        controller.update({ s -> s.copy(widgets = s.widgets + widget) })
                    }) {
                        Icon(Icons.Rounded.Add, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp).padding(8.dp))
                    }
                }
            }
        }
    }
}

private fun widgetGap(widgets: List<HomeWidget>, index: Int): androidx.compose.ui.unit.Dp = when {
    index == 0 -> 0.dp
    widgets[index] == HomeWidget.Divider || widgets[index - 1] == HomeWidget.Divider -> 10.dp
    else -> 2.dp
}

private fun widgetKeys(widgets: List<HomeWidget>): List<String> {
    var dividers = 0
    return widgets.map { if (it == HomeWidget.Divider) "Divider#${dividers++}" else it.name }
}

fun tidyWidgets(widgets: List<HomeWidget>): List<HomeWidget> {
    val out = mutableListOf<HomeWidget>()
    widgets.forEach { widget ->
        if (widget == HomeWidget.Divider && (out.isEmpty() || out.last() == HomeWidget.Divider)) return@forEach
        out += widget
    }
    while (out.lastOrNull() == HomeWidget.Divider) out.removeAt(out.lastIndex)
    return out
}

@Composable
private fun WidgetRow(widget: HomeWidget, shape: Shape, added: Boolean, onClick: () -> Unit, trailing: @Composable () -> Unit) {
    SettingRow(
        shape,
        stringResource(widget.title()),
        if (added || widget == HomeWidget.Divider) null else stringResource(widget.description()),
        onClick = onClick,
        trailing = trailing,
    )
}

@Composable
private fun animatedRowShape(first: Boolean, last: Boolean, lifted: Boolean): Shape {
    val top by animateDpAsState(if (lifted || first) 24.dp else 6.dp, label = "cornerTop")
    val bottom by animateDpAsState(if (lifted || last) 24.dp else 6.dp, label = "cornerBottom")
    return RoundedCornerShape(top, top, bottom, bottom)
}

@Composable
private fun animatedRowShape(index: Int, last: Int, lifted: Boolean): Shape {
    val top by animateDpAsState(if (lifted || index == 0) 24.dp else 6.dp, label = "cornerTop")
    val bottom by animateDpAsState(if (lifted || index == last) 24.dp else 6.dp, label = "cornerBottom")
    return RoundedCornerShape(top, top, bottom, bottom)
}

@Composable
fun AutoUpdateRow(controller: AppController, shape: Shape) {
    val settings by controller.settings.collectAsState()
    val hours = settings.autoUpdateHours
    val custom = hours > 0 && hours !in UPDATE_PRESETS
    var picking by remember { mutableStateOf(false) }
    val every = if (custom) stringResource(Res.string.auto_update_every, periodLabel(hours)) else null
    Box {
        OptionRow(
            shape, stringResource(Res.string.auto_update), stringResource(Res.string.auto_update_desc), Icons.Rounded.Update,
            listOf(
                0 to stringResource(Res.string.auto_update_off),
                6 to stringResource(Res.string.auto_update_6),
                24 to stringResource(Res.string.auto_update_24),
                72 to stringResource(Res.string.auto_update_72),
            ),
            hours,
            valueLabel = every,
            extra = listOf(MenuEntry(stringResource(Res.string.auto_update_custom), checked = custom) { picking = true }),
        ) { value -> controller.update({ it.copy(autoUpdateHours = value) }) }
    }
    if (picking) {
        PeriodSheet(hours.takeIf { it > 0 } ?: 12, onClose = { picking = false }) { value ->
            controller.update({ it.copy(autoUpdateHours = value) })
        }
    }
}

private val UPDATE_PRESETS = setOf(6, 24, 72)

private val PERIOD_STEPS = listOf(1, 2, 3, 4, 5, 6, 8, 10, 12, 16, 18, 24, 36, 48, 72, 96, 120, 168, 240, 336)

@Composable
private fun periodLabel(hours: Int): String {
    if (hours < 24) return stringResource(Res.string.idle_hours, hours)
    val days = stringResource(Res.string.period_days, hours / 24)
    return if (hours % 24 == 0) days else days + " " + stringResource(Res.string.idle_hours, hours % 24)
}

@Composable
private fun PeriodSheet(initial: Int, onClose: () -> Unit, onSave: (Int) -> Unit) {
    val platform = LocalPlatform.current
    val scope = rememberCoroutineScope()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var index by remember { mutableStateOf(PERIOD_STEPS.indices.minBy { kotlin.math.abs(PERIOD_STEPS[it] - initial) }) }
    fun close() {
        scope.launch {
            sheet.hide()
            onClose()
        }
    }
    fun move(to: Int) {
        val next = to.coerceIn(PERIOD_STEPS.indices)
        if (next == index) return
        index = next
        platform.haptic(Haptic.Tick)
    }
    AppSheet(stringResource(Res.string.auto_update_custom_title), onClose, { false }, sheet) {
        Column(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton({ move(index - 1) }, Modifier.size(56.dp), enabled = index > 0) { Icon(Icons.Rounded.Remove, null) }
                AnimatedContent(
                    index,
                    Modifier.weight(1f),
                    transitionSpec = {
                        val up = targetState > initialState
                        (slideInVertically(tween(220, easing = SettleEasing)) { if (up) it / 2 else -it / 2 } + fadeIn(tween(160))) togetherWith
                            (slideOutVertically(tween(180)) { if (up) -it / 2 else it / 2 } + fadeOut(tween(120)))
                    },
                    contentAlignment = Alignment.Center,
                    label = "period",
                ) { step ->
                    Text(
                        periodLabel(PERIOD_STEPS[step]),
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                FilledTonalIconButton({ move(index + 1) }, Modifier.size(56.dp), enabled = index < PERIOD_STEPS.lastIndex) { Icon(Icons.Rounded.Add, null) }
            }
            Slider(
                value = index.toFloat(),
                onValueChange = { move(kotlin.math.round(it).toInt()) },
                valueRange = 0f..PERIOD_STEPS.lastIndex.toFloat(),
                steps = PERIOD_STEPS.size - 2,
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                Text(periodLabel(PERIOD_STEPS.first()), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text(periodLabel(PERIOD_STEPS.last()), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DialogButtons(
                stringResource(Res.string.cancel), stringResource(Res.string.save),
                onSecondary = { close() },
                onPrimary = {
                    platform.haptic(Haptic.Toggle)
                    onSave(PERIOD_STEPS[index])
                    close()
                },
            )
        }
    }
}

@Composable
private fun GroupCaption(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
}

private fun testHost(url: String) = url.substringAfter("://").substringBefore('/').substringBefore('?').ifEmpty { url }

private fun rowIf(shown: Boolean, row: @Composable (Shape) -> Unit): (@Composable (Shape) -> Unit)? = row.takeIf { shown }

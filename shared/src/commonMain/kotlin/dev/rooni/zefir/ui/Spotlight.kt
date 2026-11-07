package dev.rooni.zefir.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import dev.rooni.zefir.platform.Platform
import dev.rooni.zefir.res.*
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

class SettingSpot(
    val title: StringResource,
    val route: Route,
    val icon: ImageVector,
    val parents: List<StringResource>,
    val description: StringResource? = null,
    val flash: Boolean = true,
    val advanced: Boolean = false,
    val keywords: String = "",
)

object SettingSpots {
    private val routing = listOf(Res.string.hub_routing)
    private val power = listOf(Res.string.hub_power)
    private val subs = listOf(Res.string.hub_subs)
    private val tools = listOf(Res.string.hub_tools)
    private val look = listOf(Res.string.set_app)
    private val notify = listOf(Res.string.notif_title)
    private val expert = listOf(Res.string.set_advanced)

    private fun page(title: StringResource, route: Route, icon: ImageVector, keywords: String = "") = SettingSpot(title, route, icon, emptyList(), flash = false, keywords = keywords)

    val PingMethod = SettingSpot(Res.string.ping_method, Route.Subscriptions, Icons.Rounded.Speed, subs, Res.string.ping_url_desc)

    fun all(platform: Platform, hasRegion: Boolean): List<SettingSpot> = buildList {
        add(page(Res.string.hub_routing, Route.Basic, Icons.Rounded.AltRoute, "маршрутизация режим исключения"))
        add(page(Res.string.hub_power, Route.Power, Icons.Rounded.PowerSettingsNew, "автозапуск автоматически"))
        add(page(Res.string.hub_subs, Route.Subscriptions, Icons.Rounded.Dns, "подписка обновление"))
        if (platform.hasPerAppRouting) add(page(Res.string.tile_apps, Route.Apps, Icons.Rounded.Apps, "банк сбер тинькофф госуслуги озон wildberries вайлдберриз раздельное split туннелирование bank"))
        add(page(Res.string.tile_sites, Route.Rules, Icons.Rounded.Language, "домен исключения обход мимо без впн vpn реклама заблокирован правила block ads"))
        add(page(Res.string.set_app, Route.AppSettingsScreen, Icons.Rounded.Palette))
        add(page(Res.string.tile_home, Route.Widgets, Icons.Rounded.Home))
        if (platform.hasNotificationSettings) add(page(Res.string.notif_title, Route.Notifications, Icons.Rounded.Notifications))
        add(page(Res.string.hub_tools, Route.ToolsSettings, Icons.Rounded.Handyman))
        add(page(Res.string.set_advanced, Route.Advanced, Icons.Rounded.Build))
        add(page(Res.string.help_title, Route.Help, Icons.Rounded.HelpOutline))
        add(page(Res.string.set_about, Route.About, Icons.Rounded.Info))

        add(SettingSpot(Res.string.mode_title, Route.Basic, Icons.Rounded.AltRoute, routing, keywords = "умный весь трафик все через глобальный smart global"))
        add(SettingSpot(Res.string.bypass_local, Route.Basic, Icons.Rounded.Lan, routing, Res.string.bypass_local_desc, keywords = "localhost локалхост лан домашняя сеть"))
        if (hasRegion) add(SettingSpot(Res.string.region_direct, Route.Basic, Icons.Rounded.Flag, routing, Res.string.region_direct_desc, keywords = "российские русские сайты напрямую"))
        add(SettingSpot(Res.string.region, Route.Basic, Icons.Rounded.Public, routing, Res.string.region_desc))

        add(SettingSpot(Res.string.connect_on_start, Route.Power, Icons.Rounded.PlayCircleOutline, power, Res.string.connect_on_start_desc))
        if (platform.hasWifiRules) add(SettingSpot(Res.string.auto_title, Route.Power, Icons.Rounded.Wifi, power, Res.string.auto_short, keywords = "дом домашний wifi вайфай автоправила"))
        add(SettingSpot(Res.string.auto_switch, Route.Power, Icons.Rounded.SwapHoriz, power, Res.string.auto_switch_desc))
        if (platform.hasBatterySettings) add(SettingSpot(Res.string.battery, Route.Power, Icons.Rounded.BatteryChargingFull, power, Res.string.battery_desc))
        if (platform.hasVpnSettings) add(SettingSpot(Res.string.kill_switch, Route.Power, Icons.Rounded.Shield, power, Res.string.kill_switch_desc, keywords = "kill switch килсвитч утечка блокировать постоянная always-on"))
        if (platform.hasIdleOff) {
            add(SettingSpot(Res.string.idle_off, Route.Power, Icons.Rounded.Bedtime, power, Res.string.idle_off_desc, keywords = "батарея заряд простой автовыключение сон ночью выключать сам"))
            add(SettingSpot(Res.string.idle_off_delay, Route.IdleOff, Icons.Rounded.Timer, power + Res.string.idle_off, Res.string.idle_off_delay_desc))
        }

        add(SettingSpot(Res.string.auto_update, Route.Subscriptions, Icons.Rounded.Update, subs, Res.string.auto_update_desc))
        add(SettingSpot(Res.string.reminders, Route.Subscriptions, Icons.Rounded.NotificationsActive, subs, Res.string.reminders_desc))
        add(PingMethod)
        add(SettingSpot(Res.string.device_hwid, Route.Subscriptions, Icons.Rounded.Fingerprint, subs, Res.string.device_desc, keywords = "hwid устройство лимит"))
        add(SettingSpot(Res.string.device_model, Route.Subscriptions, Icons.Rounded.Smartphone, subs, Res.string.device_desc))
        add(SettingSpot(Res.string.device_os, Route.Subscriptions, if (platform.isDesktop) Icons.Rounded.DesktopWindows else Icons.Rounded.Android, subs, Res.string.device_desc))

        add(SettingSpot(Res.string.theme, Route.AppSettingsScreen, Icons.Rounded.DarkMode, look, keywords = "тёмная темная светлая ночная dark light"))
        if (platform.systemColorsAvailable) add(SettingSpot(Res.string.dynamic_color, Route.AppSettingsScreen, Icons.Rounded.ColorLens, look, Res.string.dynamic_color_desc))
        add(SettingSpot(Res.string.accent, Route.AppSettingsScreen, Icons.Rounded.ColorLens, look, keywords = "моно серый чёрно-белый"))
        add(SettingSpot(Res.string.amoled, Route.AppSettingsScreen, Icons.Rounded.DarkMode, look, Res.string.amoled_desc))
        add(SettingSpot(Res.string.servers_layout, Route.AppSettingsScreen, Icons.Rounded.GridView, look))
        if (platform.hasHaptics) add(SettingSpot(Res.string.haptics, Route.AppSettingsScreen, Icons.Rounded.Vibration, look, Res.string.haptics_desc))

        if (platform.hasNotificationSettings) {
            add(SettingSpot(Res.string.notif_stop, Route.Notifications, Icons.Rounded.PowerSettingsNew, notify, Res.string.notif_stop_desc))
            add(SettingSpot(Res.string.notif_pause, Route.Notifications, Icons.Rounded.Pause, notify, Res.string.notif_pause_desc))
            add(SettingSpot(Res.string.notif_speed, Route.Notifications, Icons.Rounded.Speed, notify, Res.string.notif_speed_desc))
            add(SettingSpot(Res.string.notif_server, Route.Notifications, Icons.Rounded.Dns, notify, Res.string.notif_server_desc))
        }

        add(page(Res.string.speed_title, Route.SpeedTest, Icons.Rounded.Speed, "скорость спидтест speedtest интернет мегабит пинг"))
        add(SettingSpot(Res.string.diag_title, Route.ToolsSettings, Icons.Rounded.MedicalServices, tools, Res.string.diag_desc, keywords = "медленно тормозит лагает не работает не открывается не грузит сломалось ошибка почему slow error"))
        add(SettingSpot(Res.string.logs, Route.ToolsSettings, Icons.Rounded.Article, tools, Res.string.logs_desc, keywords = "лог журнал"))
        add(SettingSpot(Res.string.conn_title, Route.ToolsSettings, Icons.Rounded.Hub, tools, Res.string.conn_desc))
        add(page(Res.string.site_title, Route.SiteCheck(), Icons.Rounded.AltRoute, "почему мимо напрямую через vpn сайт правило не работает открывается маршрут"))
        add(page(Res.string.dns_title, Route.DnsLog, Icons.Rounded.Dns, "днс dns запросы домены резолв"))
        add(SettingSpot(Res.string.backup_export, Route.ToolsSettings, Icons.Rounded.Upload, tools, Res.string.backup_export_desc, keywords = "бэкап резервная копия"))
        add(SettingSpot(Res.string.backup_import, Route.ToolsSettings, Icons.Rounded.Download, tools, Res.string.backup_import_desc, keywords = "бэкап восстановить"))
        add(SettingSpot(Res.string.reset, Route.ToolsSettings, Icons.Rounded.RestartAlt, tools, Res.string.reset_desc))

        add(SettingSpot(Res.string.group_dns, Route.Advanced, Icons.Rounded.Dns, expert, Res.string.dns_desc))
        add(SettingSpot(Res.string.fake_ip, Route.Advanced, Icons.Rounded.Bolt, expert, Res.string.fake_ip_desc))
        add(SettingSpot(Res.string.dns_hijack_all, Route.Advanced, Icons.Rounded.Dns, expert, Res.string.dns_hijack_all_desc))
        add(SettingSpot(Res.string.ipv6, Route.Advanced, Icons.Rounded.Lan, expert, Res.string.ipv6_desc, keywords = "айпи6"))
        add(SettingSpot(Res.string.mtu, Route.Advanced, Icons.Rounded.Straighten, expert, Res.string.mtu_desc))
        add(SettingSpot(Res.string.stack, Route.Advanced, Icons.Rounded.Layers, expert, Res.string.stack_desc))
        add(SettingSpot(Res.string.sniffer, Route.Advanced, Icons.Rounded.TravelExplore, expert, Res.string.sniffer_desc))
        add(SettingSpot(Res.string.tcp_concurrent, Route.Advanced, Icons.Rounded.CallSplit, expert, Res.string.tcp_concurrent_desc))
        add(SettingSpot(Res.string.tfo, Route.Advanced, Icons.Rounded.FastForward, expert, Res.string.tfo_desc, keywords = "tfo fast open"))
        add(SettingSpot(Res.string.unified_delay, Route.Advanced, Icons.Rounded.Timer, expert, Res.string.unified_delay_desc))
        add(SettingSpot(Res.string.test_url, Route.Advanced, Icons.Rounded.Link, expert, Res.string.test_url_desc))
        add(SettingSpot(Res.string.detailed_errors, Route.Advanced, Icons.Rounded.BugReport, expert, Res.string.detailed_errors_desc))
        add(SettingSpot(Res.string.log_level, Route.Advanced, Icons.Rounded.FilterList, expert))
        add(SettingSpot(Res.string.show_advanced, Route.Advanced, Icons.Rounded.Code, expert, Res.string.show_advanced_desc, keywords = "конфиг yaml правила вручную"))
        add(SettingSpot(Res.string.allow_bypass, Route.Advanced, Icons.Rounded.AltRoute, expert, Res.string.allow_bypass_desc))
        add(SettingSpot(Res.string.skip_cert, Route.Advanced, Icons.Rounded.GppMaybe, expert, Res.string.skip_cert_desc))
    }
}

@Stable
class SpotlightController {
    var target by mutableStateOf<SettingSpot?>(null)
}

val LocalSpotlight = staticCompositionLocalOf { SpotlightController() }

private const val SETTLE_MILLIS = 400L

@Composable
fun BoxScope.SpotFlash(title: String, shape: Shape) {
    val spotlight = LocalSpotlight.current
    val spot = spotlight.target ?: return
    if (stringResource(spot.title) != title) return
    val flash = remember { Animatable(0f) }
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(spot) {
        delay(SETTLE_MILLIS)
        requester.bringIntoView()
        repeat(2) {
            flash.animateTo(1f, tween(260))
            flash.animateTo(0f, tween(420))
        }
        if (spotlight.target === spot) spotlight.target = null
    }
    Box(
        Modifier.matchParentSize().bringIntoViewRequester(requester).graphicsLayer { alpha = flash.value }
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.28f), shape),
    )
}

@Composable
fun SpotlightAnchor(title: String, shape: Shape, content: @Composable () -> Unit) {
    Box {
        content()
        SpotFlash(title, shape)
    }
}

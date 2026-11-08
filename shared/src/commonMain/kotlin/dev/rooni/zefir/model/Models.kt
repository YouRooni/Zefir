package dev.rooni.zefir.model

import dev.rooni.zefir.app.SubscriptionInfo
import dev.rooni.zefir.app.SubscriptionRouting
import kotlinx.serialization.Serializable

@Serializable
data class Profile(
    val id: String,
    val name: String,
    val url: String? = null,
    val updatedAt: Long = 0,
    val used: Long = 0,
    val total: Long = 0,
    val expire: Long = 0,
    val servers: Int = 0,
    val selected: Map<String, String> = emptyMap(),
    val pinned: Set<String> = emptySet(),
    val hiddenTags: Set<String> = emptySet(),
    val hideDead: Boolean = false,

    val updateHours: Int? = null,
    val supportUrl: String? = null,
    val webUrl: String? = null,
    val announce: String? = null,
    val info: SubscriptionInfo? = null,
    val fallbackUrl: String? = null,
    val routing: SubscriptionRouting? = null,
    val routingEnabled: Boolean = true,

    val client: String = "auto",
    val customClient: String? = null,
    val workingClient: String? = null,
    val serverNotes: Map<String, String> = emptyMap(),

    val serverLinks: Map<String, String> = emptyMap(),
    val members: List<String> = emptyList(),
    val manual: Boolean = false,
    val chains: Map<String, String> = emptyMap(),
    val balancers: Set<String> = emptySet(),
)

@Serializable
enum class RoutingMode(val core: String) { Smart("rule"), Global("global"), Direct("direct") }

@Serializable
enum class SplitMode { Off, Exclude, Only }

@Serializable
enum class ThemeMode { System, Light, Dark }

@Serializable
enum class DnsMode { FromProfile, Secure, Custom }

@Serializable
enum class HomeWidget { Server, Subscription, Mode, Speed, Session, Traffic, SplitApps, ExitIp, Divider }

@Serializable
enum class RuleAction { Direct, Proxy, Block }

@Serializable
enum class ServerLayout { List, Grid }

@Serializable
enum class ServerDensity(val cell: Int) { Roomy(168), Normal(128), Compact(96) }

@Serializable
enum class PingMethod { Url, Tcp }

@Serializable
enum class ServerSort { Default, Name, Ping }

@Serializable
data class UserRule(val value: String, val action: RuleAction)

@Serializable
data class AppSettings(
    val onboarded: Boolean = false,
    val activeProfile: String? = null,
    val mode: RoutingMode = RoutingMode.Smart,
    val splitMode: SplitMode = SplitMode.Off,
    val excludedApps: Set<String> = emptySet(),
    val onlyApps: Set<String> = emptySet(),
    val appliedPacks: Set<String> = emptySet(),

    val region: String? = null,
    val regionDirect: Boolean = false,
    val theme: ThemeMode = ThemeMode.System,
    val dynamicColor: Boolean = true,
    val railExpanded: Boolean = false,
    val haptics: Boolean = true,
    val noAnimations: Boolean = false,
    val floatingNav: Boolean = false,
    val detailedErrors: Boolean = false,
    val autoSwitch: Boolean = false,

    val searchHistory: List<String> = emptyList(),
    val amoled: Boolean = false,
    val accent: Long = 0xFF2F5D50,

    val palette: Int = -1,
    val serverLayout: ServerLayout = ServerLayout.List,
    val serverDensity: ServerDensity = ServerDensity.Normal,
    val serverSort: ServerSort = ServerSort.Default,
    val serverSortDesc: Boolean = false,
    val pingMethod: PingMethod = PingMethod.Url,
    val widgets: List<HomeWidget> = listOf(HomeWidget.Subscription, HomeWidget.Divider, HomeWidget.Server),
    val widgetsVersion: Int = 0,
    val autoUpdateHours: Int = 24,
    val connectOnStart: Boolean = false,
    val notifEnabled: Boolean = true,
    val notifStopButton: Boolean = false,
    val notifPauseButton: Boolean = false,
    val notifLiveSpeed: Boolean = false,
    val notifShowServer: Boolean = true,
    val reminders: Boolean = true,
    val idleOff: Boolean = true,
    val idleOffMinutes: Int = 60,
    val idleNightOnly: Boolean = true,

    val idleStopped: Boolean = false,
    val autoRules: Boolean = false,
    val trustedWifi: Set<String> = emptySet(),
    val autoConnectElsewhere: Boolean = true,

    val autoStopped: Boolean = false,

    val autoPausedOn: String? = null,
    val blockAds: Boolean = false,
    val rules: List<UserRule> = emptyList(),
    val rawRules: List<String> = emptyList(),
    val showAdvanced: Boolean = false,
    val fakeIp: Boolean = true,
    val bypassLocal: Boolean = true,
    val sniffer: Boolean = false,
    val tcpConcurrent: Boolean = true,
    val tcpFastOpen: Boolean = false,
    val unifiedDelay: Boolean = true,
    val dnsHijackAll: Boolean = false,
    val ipv6: Boolean = false,
    val dnsMode: DnsMode = DnsMode.FromProfile,
    val customDns: String = "",
    val stack: String = "system",
    val mtu: Int = 1500,
    val testUrl: String = "https://www.gstatic.com/generate_204",
    val allowBypass: Boolean = false,
    val skipCertVerify: Boolean = false,
    val logLevel: String = "info",

    val deviceHwid: String = "",
    val deviceModel: String = "",
    val deviceOsVersion: String = "",
    val sendDevice: Boolean = true,

    val localProxyPort: Int = 0,
    val desktopTun: Boolean = true,
    val closeToTray: Boolean = true,
    val customTitleBar: Boolean = true,

    val automation: Boolean = false,
    val automationKey: String = "",
)

data class ProxyNode(val name: String, val type: String)

data class ProxyGroup(
    val name: String,
    val type: String,
    val now: String,
    val members: List<String>,
    val fixed: String = "",
) {
    val autoPicks: Boolean get() = type in AUTO_PICK_TYPES

    companion object {
        val AUTO_PICK_TYPES = setOf("URLTest", "Fallback")
    }
}

data class ProxySnapshot(
    val groups: List<ProxyGroup> = emptyList(),
    val nodes: Map<String, ProxyNode> = emptyMap(),
) {
    val main: ProxyGroup? get() = groups.firstOrNull { it.type == "Selector" } ?: groups.firstOrNull()

    fun resolve(name: String?, depth: Int = 0): String? {
        if (name == null || depth > 8) return name
        val group = groups.firstOrNull { it.name == name } ?: return name
        return resolve(group.now.ifEmpty { null }, depth + 1) ?: name
    }
}

data class NetworkState(val wifi: Boolean = false, val ssid: String? = null, val online: Boolean = true, val known: Boolean = false)

data class Traffic(val up: Long = 0, val down: Long = 0)

sealed interface TunnelState {
    data object Off : TunnelState
    data object Starting : TunnelState
    data object On : TunnelState
    data object Stopping : TunnelState
    data class Failed(val message: String) : TunnelState
}

data class TunnelOptions(
    val splitMode: SplitMode,
    val excluded: Set<String>,
    val only: Set<String>,
    val ipv6: Boolean,
    val stack: String,
    val mtu: Int,
    val allowBypass: Boolean,
    val dnsHijackAll: Boolean = false,
    val notifEnabled: Boolean = true,
    val notifStopButton: Boolean = false,
    val notifLiveSpeed: Boolean = false,
    val notifPauseButton: Boolean = false,
    val notifShowServer: Boolean = true,
    val bypassLocal: Boolean = true,
)

data class InstalledApp(val pkg: String, val label: String, val system: Boolean)

data class LogLine(val level: String, val text: String, val time: Long = System.currentTimeMillis(), val id: Long = LogIds.incrementAndGet())

private val LogIds = java.util.concurrent.atomic.AtomicLong()

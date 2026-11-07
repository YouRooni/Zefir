package dev.rooni.zefir.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.rooni.zefir.app.AUTO_GROUP
import dev.rooni.zefir.app.AppController
import dev.rooni.zefir.app.chainCreatesLoop
import dev.rooni.zefir.app.ImportError
import dev.rooni.zefir.model.Profile
import dev.rooni.zefir.model.ProxyGroup
import dev.rooni.zefir.model.ProxySnapshot
import dev.rooni.zefir.model.ServerDensity
import dev.rooni.zefir.model.ServerLayout
import dev.rooni.zefir.model.ServerSort
import dev.rooni.zefir.platform.Haptic
import dev.rooni.zefir.platform.nowMillis
import dev.rooni.zefir.res.*
import dev.rooni.zefir.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.alexzhirkevich.qrose.rememberQrCodePainter
import org.jetbrains.compose.resources.decodeToImageBitmap
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

enum class ServerTag(val emojis: List<String>) {
    Wireless(listOf("🛜", "📶", "📡", "🤍", "⚪")),
    Fast(listOf("⚡", "🚀")),
    Gaming(listOf("🎮", "🕹")),
    Streaming(listOf("🎬", "📺", "🍿", "🎥")),
    Music(listOf("🎵", "🎧", "🎶")),
    Ai(listOf("🤖", "🧠")),
    Secure(listOf("🔒", "🔐")),
    NoAds(listOf("🛡", "🛑")),
    Split(listOf("🔄", "🔀")),
    Torrent(listOf("🧲", "📥")),
    Multihop(listOf("👥", "🔗")),
    Stealth(listOf("👻", "🕶", "🥷")),
    Onion(listOf("🧅")),
    Premium(listOf("⭐", "🌟", "💎", "👑")),
    Hot(listOf("🔥")),
    Mobile(listOf("📱")),
    Telegram(listOf("✈", "💬")),
    Global(listOf("🌐", "🌍", "🌎", "🌏")),
    Home(listOf("🏠", "🏡")),
    Test(listOf("🧪")),
    Warning(listOf("⚠", "❗")),
}

data class ServerName(val flag: String?, val label: String, val tags: List<ServerTag> = emptyList()) {
    val wireless: Boolean get() = ServerTag.Wireless in tags
}

private const val VARIATION = '️'

private fun String.cleanLabel() = trim().trim('-', '|', '·', ':').replace(Regex("\\s{2,}"), " ").trim()

private fun takeTags(raw: String): Pair<String, List<ServerTag>> {
    var text = raw
    val found = mutableListOf<Pair<Int, ServerTag>>()
    ServerTag.entries.forEach { tag ->
        tag.emojis.forEach { emoji ->
            val at = raw.indexOf(emoji)
            if (at >= 0) {
                if (found.none { it.second == tag }) found += at to tag
                text = text.replace(emoji + VARIATION, " ").replace(emoji, " ")
            }
        }
    }
    return text to found.sortedBy { it.first }.map { it.second }
}

fun parseServerName(raw: String): ServerName {
    val (text, tags) = takeTags(raw)
    var index = 0
    while (index < text.length) {
        val first = text.codePointAt(index)
        val firstSize = Character.charCount(first)
        if (first in 0x1F1E6..0x1F1FF && index + firstSize < text.length) {
            val second = text.codePointAt(index + firstSize)
            if (second in 0x1F1E6..0x1F1FF) {
                val end = index + firstSize + Character.charCount(second)
                val label = (text.substring(0, index) + text.substring(end)).cleanLabel()
                return ServerName(text.substring(index, end), label.ifEmpty { raw.trim() }, tags)
            }
        }
        index += firstSize
    }
    return ServerName(null, (if (tags.isNotEmpty()) text.cleanLabel() else raw.trim()).ifEmpty { raw.trim() }, tags)
}

val ServerTag.icon: ImageVector
    get() = when (this) {
        ServerTag.Wireless -> Icons.Rounded.Wifi
        ServerTag.Fast -> Icons.Rounded.Bolt
        ServerTag.Gaming -> Icons.Rounded.SportsEsports
        ServerTag.Streaming -> Icons.Rounded.Movie
        ServerTag.Music -> Icons.Rounded.MusicNote
        ServerTag.Ai -> Icons.Rounded.SmartToy
        ServerTag.Secure -> Icons.Rounded.Lock
        ServerTag.NoAds -> Icons.Rounded.Shield
        ServerTag.Split -> Icons.Rounded.AltRoute
        ServerTag.Torrent -> Icons.Rounded.Download
        ServerTag.Multihop -> Icons.Rounded.Link
        ServerTag.Stealth -> Icons.Rounded.VisibilityOff
        ServerTag.Onion -> Icons.Rounded.TravelExplore
        ServerTag.Premium -> Icons.Rounded.Star
        ServerTag.Hot -> Icons.Rounded.LocalFireDepartment
        ServerTag.Mobile -> Icons.Rounded.Smartphone
        ServerTag.Telegram -> Icons.AutoMirrored.Rounded.Send
        ServerTag.Global -> Icons.Rounded.Public
        ServerTag.Home -> Icons.Rounded.Home
        ServerTag.Test -> Icons.Rounded.Science
        ServerTag.Warning -> Icons.Rounded.WarningAmber
    }

const val PLAIN_TAG = "Plain"

val ServerTag.label: StringResource
    get() = when (this) {
        ServerTag.Wireless -> Res.string.tag_wireless
        ServerTag.Fast -> Res.string.tag_fast
        ServerTag.Gaming -> Res.string.tag_gaming
        ServerTag.Streaming -> Res.string.tag_streaming
        ServerTag.Music -> Res.string.tag_music
        ServerTag.Ai -> Res.string.tag_ai
        ServerTag.Secure -> Res.string.tag_secure
        ServerTag.NoAds -> Res.string.tag_noads
        ServerTag.Split -> Res.string.tag_split
        ServerTag.Torrent -> Res.string.tag_torrent
        ServerTag.Multihop -> Res.string.tag_multihop
        ServerTag.Stealth -> Res.string.tag_stealth
        ServerTag.Onion -> Res.string.tag_onion
        ServerTag.Premium -> Res.string.tag_premium
        ServerTag.Hot -> Res.string.tag_hot
        ServerTag.Mobile -> Res.string.tag_mobile
        ServerTag.Telegram -> Res.string.tag_telegram
        ServerTag.Global -> Res.string.tag_global
        ServerTag.Home -> Res.string.tag_home
        ServerTag.Test -> Res.string.tag_test
        ServerTag.Warning -> Res.string.tag_warning
    }

fun shownByTags(name: String, hidden: Set<String>): Boolean {
    if (hidden.isEmpty()) return true
    val tags = parseServerName(name).tags
    return if (tags.isEmpty()) PLAIN_TAG !in hidden else tags.any { it.name !in hidden }
}

private fun anyFlags(names: List<String>) = names.any { name -> parseServerName(name).let { it.flag != null || it.tags.isNotEmpty() } }

private object FlagCache {
    val images = java.util.concurrent.ConcurrentHashMap<String, ImageBitmap>()
}

private fun flagCode(flag: String): String {
    val first = flag.codePointAt(0) - 0x1F1E6
    val second = flag.codePointAt(Character.charCount(flag.codePointAt(0))) - 0x1F1E6
    return "${('a' + first)}${('a' + second)}"
}

@Composable
fun FlagBox(flag: String?, size: Dp = 36.dp, tag: ServerTag? = null, corner: Dp = size * 0.28f) {
    val code = remember(flag) { flag?.let(::flagCode) }
    var missing by remember(code) { mutableStateOf(false) }
    var image by remember(code) { mutableStateOf(code?.let { FlagCache.images[it] }) }
    LaunchedEffect(code) {
        if (code != null && image == null) {
            image = withContext(Dispatchers.Default) {
                runCatching { Res.readBytes("files/flags/$code.png").decodeToImageBitmap() }.getOrNull()
            }?.also { FlagCache.images[code] = it }
            missing = image == null
        }
    }
    val shape = RoundedCornerShape(corner)
    Box(
        Modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        val current = image
        if (current != null) {
            Image(current, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else if (flag == null && tag != null) {
            Icon(tag.icon, null, Modifier.fillMaxSize().padding(size * 0.2f), tint = MaterialTheme.colorScheme.primary)
        } else if (flag == null) {
            Text("?", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (missing) {
            Text(code.orEmpty().uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun ServersScreen(controller: AppController, nav: Navigator) {
    val profiles by controller.profiles.collectAsState()
    val testing by controller.testing.collectAsState()
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState { 2 }
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    var barReset by remember { mutableStateOf(0) }
    var folderTop by remember { mutableStateOf(0) }
    var folderPath by rememberSaveable { mutableStateOf(listOf<String>()) }
    val proxies by controller.proxies.collectAsState()
    val presentTags = remember(proxies) {
        val groupNames = proxies.groups.mapTo(mutableSetOf()) { it.name }
        val servers = proxies.groups.flatMapTo(mutableSetOf()) { it.members }.filter { it !in AppController.BUILT_IN && it !in groupNames }
        val parsed = servers.map { parseServerName(it).tags }
        val tags = ServerTag.entries.filter { tag -> parsed.any { tag in it } }.map { it.name }
        val all = if (tags.isNotEmpty() && parsed.any { it.isEmpty() }) tags + PLAIN_TAG else tags
        all.takeIf { it.size >= 2 }.orEmpty()
    }

    LaunchedEffect(nav.serversPage) {
        nav.serversPage?.let {
            pager.animateScrollToPage(it)
            nav.serversPage = null
        }
    }
    LaunchedEffect(pager) {
        snapshotFlow { pager.isScrollInProgress }.collect { scrolling ->
            if (!scrolling && kotlin.math.abs(pager.currentPageOffsetFraction) > 0.001f) {
                pager.animateScrollToPage(pager.currentPage, animationSpec = tween(240))
            }
        }
    }
    ReselectEffect(nav, Tab.Servers) {
        if (pager.currentPage != 0) {
            pager.animateScrollToPage(0)
        } else if (folderPath.isNotEmpty()) {
            barReset++
            folderTop++
        } else {
            barReset++
            if (controller.settings.value.serverLayout == ServerLayout.Grid) gridState.animateScrollToItem(0) else listState.smoothToTop()
        }
    }

    AnimatedContent(profiles.isEmpty(), label = "serversEmpty") { empty ->
        if (empty) {
            EmptyServers(nav)
        } else {
            SubScreen(
                stringResource(Res.string.tab_servers),
                null,
                barReset = barReset,
                actions = {
                    val onServers = pager.currentPage == 0
                    val canMerge = profiles.count { it.members.isEmpty() } >= 2
                    AnimatedContent(
                        if (onServers) 0 else if (canMerge) 2 else 1,
                        contentAlignment = Alignment.CenterEnd,
                        label = "serversActions",
                        transitionSpec = { fadeIn(tween(220, 80)) togetherWith fadeOut(tween(160)) using SizeTransform(clip = false) { _, _ -> snap() } },
                    ) { mode ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (mode == 0) {
                                Box(
                                    Modifier.size(48.dp).clip(androidx.compose.foundation.shape.CircleShape).combinedClickable(
                                        onLongClick = {
                                            controller.platform.haptic(Haptic.Toggle)
                                            nav.openSetting(SettingSpots.PingMethod)
                                        },
                                        onClick = {
                                            controller.platform.haptic(Haptic.Tick)
                                            controller.testAll()
                                        },
                                    ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Crossfade(testing, animationSpec = tween(200), label = "serversTest") { busy ->
                                        if (busy) LoadingIndicator(Modifier.size(24.dp)) else Icon(Icons.Rounded.Speed, stringResource(Res.string.servers_test))
                                    }
                                }
                                SortButton(controller, presentTags)
                            } else {
                                IconButton(onClick = {
                                    controller.platform.haptic(Haptic.Tick)
                                    nav.push(Route.Add)
                                }) { Icon(Icons.Rounded.Add, stringResource(Res.string.servers_add)) }
                                if (mode == 2) {
                                    MenuButton(listOf(MenuEntry(stringResource(Res.string.merge_title), Icons.Rounded.Layers) { nav.push(Route.MergeProfiles()) }))
                                }
                            }
                        }
                    }
                },
                belowBar = {
                    PrimaryTabRow(pager.currentPage, containerColor = androidx.compose.ui.graphics.Color.Transparent) {
                        listOf(Res.string.tab_servers, Res.string.servers_active).forEachIndexed { index, title ->
                            Tab(
                                selected = pager.currentPage == index,
                                onClick = {
                                    controller.platform.haptic(Haptic.Tick)
                                    scope.launch { pager.animateScrollToPage(index) }
                                },
                                text = { ButtonText(stringResource(title)) },
                            )
                        }
                    }
                },
            ) { padding ->
                CompositionLocalProvider(LocalServerNav provides nav) {
                    HorizontalPager(pager, Modifier.padding(padding).fillMaxSize().tabsHandoff(), beyondViewportPageCount = 1) { page ->
                        if (page == 0) ServerList(controller, nav, listState, gridState, folderPath, folderTop) { folderPath = it } else ProfilesPage(controller, nav)
                    }
                }
            }
        }
    }
}

@Composable
private fun SortButton(controller: AppController, tags: List<String>) {
    val settings by controller.settings.collectAsState()
    val profiles by controller.profiles.collectAsState()
    val profile = profiles.firstOrNull { it.id == settings.activeProfile }
    fun choose(sort: ServerSort) {
        controller.update({ it.copy(serverSort = sort) })
        if (sort == ServerSort.Ping && controller.delays.value.isEmpty() && !controller.testing.value) controller.testAll()
    }
    val filtered = profile?.hideDead == true || profile?.hiddenTags.orEmpty().any { it in tags }
    MenuButton(
        listOf(
            MenuEntry(stringResource(Res.string.sort_default), Icons.Rounded.Reorder, settings.serverSort == ServerSort.Default) { choose(ServerSort.Default) },
            MenuEntry(stringResource(Res.string.sort_name), Icons.Rounded.SortByAlpha, settings.serverSort == ServerSort.Name) { choose(ServerSort.Name) },
            MenuEntry(stringResource(Res.string.sort_ping), Icons.Rounded.Speed, settings.serverSort == ServerSort.Ping, endsSection = true) { choose(ServerSort.Ping) },
            MenuEntry(stringResource(Res.string.sort_asc), Icons.Rounded.ArrowUpward, !settings.serverSortDesc) { controller.update({ it.copy(serverSortDesc = false) }) },
            MenuEntry(stringResource(Res.string.sort_desc), Icons.Rounded.ArrowDownward, settings.serverSortDesc, endsSection = true) { controller.update({ it.copy(serverSortDesc = true) }) },
            MenuEntry(stringResource(Res.string.filter_hide_dead), Icons.Rounded.VisibilityOff, profile?.hideDead == true) { controller.updateActiveProfile { it.copy(hideDead = !it.hideDead) } },
        ),
        stringResource(Res.string.sort_title),
        if (filtered) Icons.Rounded.FilterAlt else Icons.AutoMirrored.Rounded.Sort,
        highlighted = filtered,
        extra = if (tags.isEmpty()) null else { { ServerFilter(controller, profile?.hiddenTags.orEmpty(), tags) } },
    )
}

private const val TAGS_PER_ROW = 4

@Composable
private fun ServerFilter(controller: AppController, hidden: Set<String>, tags: List<String>) {
    val platform = controller.platform
    Column(Modifier.fillMaxWidth().padding(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        tags.chunked(TAGS_PER_ROW).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { id ->
                    val tag = ServerTag.entries.firstOrNull { it.name == id }
                    val label = stringResource(tag?.label ?: Res.string.tag_plain)
                    FilledTonalIconToggleButton(
                        checked = id !in hidden,
                        onCheckedChange = { on ->
                            platform.haptic(Haptic.Tick)
                            controller.updateActiveProfile { profile ->
                                val next = if (on) profile.hiddenTags - id else profile.hiddenTags + id
                                profile.copy(hiddenTags = if (tags.all { it in next }) profile.hiddenTags - id else next)
                            }
                        },
                        shapes = IconButtonDefaults.toggleableShapes(),
                        modifier = Modifier.weight(1f).height(48.dp).semantics { contentDescription = label },
                    ) {
                        Icon(tag?.icon ?: Icons.Rounded.Dns, null, Modifier.size(22.dp))
                    }
                }
            }
        }
    }
}

private val LocalPinnedServers = compositionLocalOf<Set<String>> { emptySet() }
private val LocalServerNav = compositionLocalOf<Navigator?> { null }

private fun sortMembers(members: List<String>, sort: ServerSort, descending: Boolean, delays: Map<String, Int>, pinned: Set<String>, isGroup: (String) -> Boolean): List<String> {
    val (fixed, servers) = members.partition { isGroup(it) || it in AppController.BUILT_IN }
    val sorted = when (sort) {
        ServerSort.Default -> if (descending) servers.reversed() else servers
        ServerSort.Name -> servers.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { parseServerName(it).label }).let { if (descending) it.reversed() else it }
        ServerSort.Ping -> {
            val (alive, rest) = servers.partition { (delays[it] ?: 0) > 0 }
            alive.sortedBy { delays[it] }.let { if (descending) it.reversed() else it } + rest
        }
    }

    val (top, rest) = sorted.partition { it in pinned }
    return fixed + top + rest
}

@Composable
private fun EmptyServers(nav: Navigator) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Rounded.Dns, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(Res.string.servers_empty_title), style = MaterialTheme.typography.headlineSmallEmphasized, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(Res.string.servers_empty_text), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(onClick = { nav.push(Route.Add) }, shapes = ButtonDefaults.shapes(), modifier = Modifier.height(56.dp)) {
            ButtonText(stringResource(Res.string.servers_add))
        }
    }
}

@Composable
private fun ServerList(controller: AppController, nav: Navigator, listState: LazyListState, gridState: LazyGridState, path: List<String>, scrollTop: Int, onPath: (List<String>) -> Unit) {
    val proxies by controller.proxies.collectAsState()
    val settings by controller.settings.collectAsState()
    val delays = controller.delays.collectAsState()
    val testing = controller.testingServers.collectAsState()
    val main = proxies.main
    var servicesOpen by rememberSaveable { mutableStateOf(false) }
    val services = remember(proxies) {
        val reachable = mutableSetOf<String>()
        fun walk(name: String) {
            if (!reachable.add(name)) return
            proxies.groups.firstOrNull { it.name == name }?.members?.forEach(::walk)
        }
        main?.name?.let(::walk)
        proxies.groups.filter { it.type == "Selector" && it.name !in reachable && it.name != "GLOBAL" }
    }

    if (main == null) {
        val ready by controller.ready.collectAsState()
        val loadError by controller.loadError.collectAsState()
        val refreshing by controller.refreshing.collectAsState()
        val profile = controller.activeProfile
        val busy = !ready || (profile != null && profile.id in refreshing)
        Crossfade(busy, Modifier.fillMaxSize(), label = "serversLoad") { loading ->
            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
            } else {
                LoadFailed(loadError ?: stringResource(Res.string.servers_load_failed), profile?.takeIf { it.url != null || it.members.isNotEmpty() }?.let { { controller.refreshProfile(it.id) } })
            }
        }
        return
    }

    val unpinned = remember { mutableSetOf<String>() }
    LaunchedEffect(proxies.groups) {
        proxies.groups.filter { it.autoPicks && it.fixed.isNotEmpty() && unpinned.add(it.name) }.forEach { controller.selectPath(listOf(it.name), "") }
    }
    val validPath = remember(path, proxies.groups) { path.takeWhile { name -> proxies.groups.any { it.name == name } } }
    LaunchedEffect(validPath) { if (validPath != path) onPath(validPath) }
    val latestTop by rememberUpdatedState(scrollTop)

    val settled by remember { derivedStateOf { testing.value.isEmpty() } }
    val noPings by remember { derivedStateOf { delays.value.isEmpty() } }
    val groupNames = remember(proxies.groups) { proxies.groups.mapTo(mutableSetOf()) { it.name } }
    val profiles by controller.profiles.collectAsState()
    val pinned = remember(profiles, settings.activeProfile) { profiles.firstOrNull { it.id == settings.activeProfile }?.pinned.orEmpty() }
    val activeProfile = remember(profiles, settings.activeProfile) { profiles.firstOrNull { it.id == settings.activeProfile } }
    val hiddenTags = activeProfile?.hiddenTags.orEmpty()
    val hideDead = activeProfile?.hideDead == true
    fun serverShown(member: String) = !(hideDead && (delays.value[member] ?: 1) <= 0) && shownByTags(member, hiddenTags)
    fun folderShown(name: String, seen: Set<String> = emptySet()): Boolean {
        if (hiddenTags.isEmpty() && !hideDead) return true
        val group = proxies.groups.firstOrNull { it.name == name } ?: return true
        if (!openableType(group) || name in seen) return true
        return group.members.any { member ->
            when {
                member in HIDDEN_MEMBERS || member in AppController.BUILT_IN -> false
                member in groupNames -> folderShown(member, seen + name)
                else -> serverShown(member)
            }
        }
    }
    val ordered: (ProxyGroup) -> List<String> = { group ->
        val visible = group.members.filter { member ->
            when {
                member in HIDDEN_MEMBERS -> false
                member in AppController.BUILT_IN -> true
                member in groupNames -> folderShown(member)
                else -> serverShown(member)
            }
        }
        sortMembers(visible, settings.serverSort, settings.serverSortDesc, delays.value, pinned) { it in groupNames }
    }
    val hiddenBy: (ProxyGroup) -> Int = { group ->
        if (hiddenTags.isEmpty() && !hideDead) 0 else group.members.count { member ->
            member !in HIDDEN_MEMBERS && member !in AppController.BUILT_IN && when {
                member in groupNames -> !folderShown(member)
                else -> !serverShown(member)
            }
        }
    }
    val filterKey = hideDead to hiddenTags
    val openable: (String) -> Boolean = { name -> proxies.groups.any { it.name == name && openableType(it) } }
    val clearFilter = {
        controller.platform.haptic(Haptic.Tick)
        controller.updateActiveProfile { it.copy(hiddenTags = emptySet(), hideDead = false) }
    }

    val servicesBlock: @Composable () -> Unit = {
        val turn by animateFloatAsState(if (servicesOpen) 180f else 0f, label = "svcChevron")
        Column(Modifier.padding(bottom = 12.dp)) {
            SettingRow(
                RoundedCornerShape(24.dp),
                stringResource(Res.string.servers_services),
                stringResource(Res.string.servers_services_count, services.size),
                Icons.Rounded.Tune,
                onClick = { servicesOpen = !servicesOpen },
            ) {
                Icon(Icons.Rounded.ExpandMore, null, Modifier.graphicsLayer { rotationZ = turn })
            }
            AnimatedVisibility(servicesOpen, enter = RevealEnter, exit = RevealExit) {
                Column(Modifier.padding(top = 8.dp)) {
                    Text(
                        stringResource(Res.string.servers_services_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                    )
                    SectionGroup(
                        rows = services.map { service ->
                            { shape: Shape ->
                                val name = parseServerName(service.name)
                                val current = parseServerName(proxies.resolve(service.now) ?: service.now).label
                                NavRow(shape, name.label, current, null) {
                                    controller.platform.haptic(Haptic.Tick)
                                    onPath(listOf(service.name))
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    CompositionLocalProvider(LocalPinnedServers provides pinned) {
        AnimatedContent(
            validPath,
            Modifier.fillMaxSize(),
            label = "folder",
            transitionSpec = {
                val forward = targetState.size > initialState.size
                val enter = slideInHorizontally(tween(320, easing = FolderEasing)) { if (forward) it / 5 else -it / 5 } + fadeIn(tween(220, 60))
                val exit = slideOutHorizontally(tween(320, easing = FolderEasing)) { if (forward) -it / 5 else it / 5 } + fadeOut(tween(120))
                (enter togetherWith exit).using(SizeTransform(clip = false))
            },
        ) { chain ->
            val group = chain.lastOrNull()?.let { name -> proxies.groups.firstOrNull { it.name == name } } ?: main
            val selectChain = if (chain.isEmpty()) listOf(main.name) else chain
            val active = remember(proxies, selectChain) {
                selectChain.zipWithNext().all { (parent, child) -> proxies.groups.firstOrNull { it.name == parent }?.now == child }
            }
            val members = remember(group, groupNames, settings.serverSort, settings.serverSortDesc, settled, noPings, pinned, filterKey) { ordered(group) }
            val flagged = remember(members) { anyFlags(members) }
            val hiddenCount = remember(group, groupNames, settled, noPings, filterKey) { hiddenBy(group) }
            val choose: (String) -> Unit = { member ->
                controller.platform.haptic(Haptic.Tick)
                if (openable(member)) onPath(selectChain + member) else controller.selectPath(selectChain, member)
            }
            val filterNote: @Composable () -> Unit = { FilterNote(hiddenCount, clearFilter) }
            val header: @Composable () -> Unit = {
                FolderHeader(proxies, group, active) {
                    controller.platform.haptic(Haptic.Tick)
                    onPath(folderBack(chain, main.name))
                }
            }
            val padding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp)
            val folderGrid = rememberLazyGridState()
            val folderList = rememberLazyListState()
            if (chain.isNotEmpty()) {
                LaunchedEffect(Unit) {
                    snapshotFlow { latestTop }.drop(1).collect {
                        if (controller.settings.value.serverLayout == ServerLayout.Grid) folderGrid.animateScrollToItem(0) else folderList.smoothToTop()
                    }
                }
            }
            if (settings.serverLayout == ServerLayout.Grid) {
                val density = settings.serverDensity
                LazyVerticalGrid(
                    GridCells.Adaptive(density.cell.dp),
                    Modifier.fillMaxSize(),
                    state = if (chain.isEmpty()) gridState else folderGrid,
                    contentPadding = padding,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (chain.isNotEmpty()) item(key = "header", span = { GridItemSpan(maxLineSpan) }) { header() }
                    else if (services.isNotEmpty()) item(key = "services", span = { GridItemSpan(maxLineSpan) }) { servicesBlock() }
                    items(members.size, key = { "m-" + members[it] }, contentType = { "server" }) { index ->
                        Box(Modifier.animateItem()) {
                            MemberTile(controller, proxies, group, members[index], delays, testing, flagged, density, active, choose)
                        }
                    }
                    if (hiddenCount > 0) item(key = "filtered", span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.animateItem()) { filterNote() } }
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    state = if (chain.isEmpty()) listState else folderList,
                    contentPadding = padding,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (chain.isNotEmpty()) item(key = "header") { header() }
                    else if (services.isNotEmpty()) item(key = "services") { servicesBlock() }
                    items(members.size, key = { "m-" + members[it] }, contentType = { "server" }) { index ->
                        Box(Modifier.animateItem()) {
                            MemberRow(controller, proxies, group, members[index], delays, testing, flagged, positionShape(index, members.lastIndex), active, choose)
                        }
                    }
                    if (hiddenCount > 0) item(key = "filtered") { Box(Modifier.animateItem()) { filterNote() } }
                }
            }
        }
    }
}

@Composable
private fun LoadFailed(text: String, onRefresh: (() -> Unit)?) {
    val platform = LocalPlatform.current
    val mark = remember { PolygonShape(MaterialShapes.Cookie9Sided) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(96.dp).clip(mark).background(MaterialTheme.colorScheme.errorContainer), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.CloudOff, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
        }
        Spacer(Modifier.height(20.dp))
        Text(stringResource(Res.string.servers_load_title), style = MaterialTheme.typography.headlineSmallEmphasized, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (onRefresh != null) {
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    platform.haptic(Haptic.Tick)
                    onRefresh()
                },
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.height(56.dp),
            ) {
                Icon(Icons.Rounded.Refresh, null)
                Spacer(Modifier.width(8.dp))
                ButtonText(stringResource(Res.string.servers_update))
            }
        }
    }
}

private val FolderEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

private fun openableType(group: ProxyGroup) = group.type == "Selector"

@Composable
private fun FilterNote(count: Int, onShow: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.FilterAlt, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            stringResource(Res.string.filter_hidden_count, count),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
        )
        OutlinedButton(
            onClick = onShow,
            shapes = ButtonDefaults.shapes(),
            contentPadding = PaddingValues(horizontal = 14.dp),
            modifier = Modifier.height(36.dp),
        ) { ButtonText(stringResource(Res.string.filter_show_all), style = MaterialTheme.typography.labelMedium) }
    }
}

private fun folderBack(path: List<String>, main: String): List<String> {
    val up = path.dropLast(1)
    return if (up == listOf(main)) emptyList() else up
}

private val FolderCorner = 30.dp
private val FolderInset = 6.dp

@Composable
private fun FolderHeader(proxies: ProxySnapshot, group: ProxyGroup, active: Boolean, onBack: () -> Unit) {
    val name = remember(group.name) { parseServerName(group.name) }
    val picked = remember(proxies, group) { proxies.resolve(group.now)?.takeIf { it.isNotEmpty() }?.let { parseServerName(it).label } }
    val current = if (active) picked else stringResource(Res.string.folder_unused)
    val shape = RoundedCornerShape(FolderCorner)
    val inner = RoundedCornerShape(FolderCorner - FolderInset)
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(shape).clickable(onClick = onBack),
    ) {
        Row(Modifier.padding(FolderInset), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(inner).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(Res.string.back))
            }
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(name.label, style = MaterialTheme.typography.titleMediumEmphasized, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (current != null) {
                    Text(current, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (name.flag != null || name.tags.isNotEmpty()) {
                FlagBox(name.flag, 48.dp, name.tags.firstOrNull(), FolderCorner - FolderInset)
            } else {
                Box(Modifier.size(48.dp).clip(inner).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.FolderOpen, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
    }
}

private val HIDDEN_MEMBERS = setOf("PASS")

private class MemberInfo(val inner: ProxyGroup?, val parsed: ServerName, val title: String, val subtitle: String?, val leadIcon: ImageVector?)

@Composable
private fun memberInfo(controller: AppController, proxies: ProxySnapshot, member: String, selected: Boolean): MemberInfo {
    val inner = remember(proxies, member) { proxies.groups.firstOrNull { it.name == member } }
    val parsed = remember(member) { parseServerName(member) }
    val title = when (member) {
        AUTO_GROUP -> stringResource(Res.string.servers_auto)
        "DIRECT" -> stringResource(Res.string.rule_direct)
        "REJECT", "REJECT-DROP" -> stringResource(Res.string.rule_block)
        else -> parsed.label
    }
    val note = remember(member, proxies) { controller.activeProfile?.serverNotes?.get(member) }
    val via = controller.activeProfile?.chains?.get(member)
    val viaText = via?.let { stringResource(Res.string.chain_via, parseServerName(it).label.ifBlank { it }) }
    val subtitle = when {
        viaText != null -> viaText
        note != null -> note
        member == AUTO_GROUP && !selected -> stringResource(Res.string.servers_auto_desc)
        inner != null -> remember(proxies, member) { proxies.resolve(member)?.takeIf { it != member }?.let { parseServerName(it).label } }
        else -> null
    }
    val leadIcon: ImageVector? = when {
        member == AUTO_GROUP -> Icons.Rounded.AutoAwesome
        inner?.type == "URLTest" || inner?.type == "LoadBalance" -> if (parsed.flag == null) Icons.Rounded.AutoAwesome else null
        inner?.type == "Fallback" -> Icons.Rounded.Shuffle
        inner != null -> if (parsed.flag == null && parsed.tags.isEmpty()) Icons.Rounded.FolderOpen else null
        member == "DIRECT" -> Icons.Rounded.ArrowForward
        member == "REJECT" || member == "REJECT-DROP" -> Icons.Rounded.Block
        else -> null
    }
    return MemberInfo(inner, parsed, title, subtitle, leadIcon)
}

private const val PING_LOADING = Int.MIN_VALUE

private const val SERVER_HOLD_MILLIS = 300L

@Composable
private fun ServerMenu(controller: AppController, member: String, title: String, menu: MenuState) {
    val platform = controller.platform
    var link by remember(member) { mutableStateOf<String?>(null) }
    var showQr by remember { mutableStateOf(false) }
    var pickChain by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val nav = LocalServerNav.current
    val manual = controller.activeProfile?.manual == true
    var editable by remember(member) { mutableStateOf(false) }
    LaunchedEffect(menu.open, member) {
        if (menu.open && link == null) link = controller.serverLink(member)
        if (menu.open && manual) editable = withContext(Dispatchers.Default) { controller.manualEditable(member) }
    }
    val current = link
    MenuForState(
        menu,
        buildList {
            val isPinned = member in LocalPinnedServers.current
            add(MenuEntry(stringResource(if (isPinned) Res.string.server_unpin else Res.string.server_pin), Icons.Rounded.PushPin) { controller.togglePin(member) })
            if (current != null) {
                add(MenuEntry(stringResource(Res.string.server_copy_link), Icons.Rounded.Link) { platform.writeClipboard(current, secret = true) })
                add(MenuEntry(stringResource(Res.string.server_show_qr), Icons.Rounded.QrCode2) { showQr = true })
            }
            add(MenuEntry(stringResource(Res.string.server_copy_name), Icons.Rounded.ContentCopy) { platform.writeClipboard(title, secret = false) })
            add(MenuEntry(stringResource(Res.string.chain_menu), Icons.Rounded.Route) { pickChain = true })
            if (manual && nav != null) {
                if (editable) add(MenuEntry(stringResource(Res.string.edit_title), Icons.Rounded.Edit) { nav.push(Route.ManualServer(member)) })
                add(MenuEntry(stringResource(Res.string.delete), Icons.Rounded.DeleteOutline) { confirmDelete = true })
            }
        },
    )
    if (pickChain) ChainSheet(controller, member, title) { pickChain = false }
    if (confirmDelete) {
        AppDialog(
            stringResource(Res.string.manual_delete_q),
            title,
            stringResource(Res.string.cancel),
            stringResource(Res.string.delete),
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                scope.launch { controller.deleteManualServer(member) }
            },
        )
    }
    if (showQr && current != null) {
        ModalBottomSheet(onDismissRequest = { showQr = false }, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, dragHandle = null) {
            SheetHandle()
            Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(title, style = MaterialTheme.typography.titleLargeEmphasized, maxLines = 2, overflow = TextOverflow.Ellipsis)

                Surface(shape = RoundedCornerShape(24.dp), color = androidx.compose.ui.graphics.Color.White) {
                    Image(rememberQrCodePainter(current), null, Modifier.padding(16.dp).size(240.dp))
                }
            }
        }
    }
}

@Composable
private fun ChainSheet(controller: AppController, member: String, title: String, onClose: () -> Unit) {
    val proxies by controller.proxies.collectAsState()
    val current = controller.activeProfile?.chains?.get(member)
    val groupNames = remember(proxies) { proxies.groups.mapTo(mutableSetOf()) { it.name } }
    val chains = controller.activeProfile?.chains.orEmpty()
    val options = remember(proxies, member, chains) {
        proxies.groups.flatMap { it.members }
            .distinct()
            .filter { name ->
                val node = proxies.nodes[name]
                name != member && name !in groupNames && name !in AppController.BUILT_IN &&
                    node != null && node.type !in AppController.BUILT_IN_TYPES &&
                    !chainCreatesLoop(member, name, chains - member)
            }
    }
    ModalBottomSheet(onDismissRequest = onClose, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, dragHandle = null) {
        SheetHandle()
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text(stringResource(Res.string.chain_title), style = MaterialTheme.typography.titleLargeEmphasized, modifier = Modifier.padding(horizontal = 8.dp))
            Text(
                stringResource(Res.string.chain_desc, title),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 16.dp),
            )
            val rows = listOf<String?>(null) + options
            LazyColumn(Modifier.heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                itemsIndexed(rows) { index, option ->
                    val picked = option == current
                    val parsed = remember(option) { option?.let(::parseServerName) }
                    val label = when (option) {
                        null -> stringResource(Res.string.chain_none)
                        AUTO_GROUP -> stringResource(Res.string.servers_auto)
                        else -> parsed?.label?.ifBlank { option } ?: option
                    }
                    SettingRow(
                        positionShape(index, rows.lastIndex),
                        label,
                        color = if (picked) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                        icon = when {
                            option == null -> Icons.Rounded.LinkOff
                            option in groupNames -> Icons.Rounded.AutoAwesome
                            else -> null
                        },
                        leading = if (option != null && option !in groupNames && parsed != null) {
                            { FlagBox(parsed.flag, 32.dp, parsed.tags.firstOrNull()) }
                        } else {
                            null
                        },
                        onClick = {
                            controller.setChain(member, option)
                            onClose()
                        },
                    ) {
                        if (picked) Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun PingBadge(controller: AppController, member: String, delays: State<Map<String, Int>>, testing: State<Set<String>>, showUnit: Boolean, height: Dp) {
    val state by remember(member) { derivedStateOf { if (member in testing.value) PING_LOADING else delays.value[member] } }
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier.height(height).widthIn(min = height).clip(shape).clickable {
            controller.platform.haptic(Haptic.Tick)
            controller.testServer(member)
        },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            state,
            label = "ping",
            transitionSpec = { (fadeIn(tween(160)) + scaleIn(tween(200), initialScale = 0.8f)) togetherWith (fadeOut(tween(100)) + scaleOut(tween(140), targetScale = 0.8f)) using SizeTransform(clip = false) },
        ) { value ->
            when {
                value == PING_LOADING -> LoadingIndicator(Modifier.size(height.coerceAtMost(28.dp)))
                value == null -> Icon(Icons.Rounded.Bolt, stringResource(Res.string.servers_test), Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                value <= 0 -> Text(stringResource(Res.string.servers_dead), Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error, maxLines = 1)
                else -> Text(if (showUnit) stringResource(Res.string.servers_ms, value) else value.toString(), Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1)
            }
        }
    }
}

@Composable
private fun PinMark(member: String) {
    AnimatedVisibility(member in LocalPinnedServers.current, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
        Icon(Icons.Rounded.PushPin, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun TagMarks(parsed: ServerName) {
    val shown = if (parsed.flag != null) parsed.tags else parsed.tags.drop(1)
    shown.forEach { Icon(it.icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary) }
}

@Composable
private fun MemberTile(
    controller: AppController,
    proxies: ProxySnapshot,
    group: ProxyGroup,
    member: String,
    delays: State<Map<String, Int>>,
    testing: State<Set<String>>,
    flagged: Boolean,
    density: ServerDensity,
    active: Boolean,
    onChoose: (String) -> Unit,
) {
    val selected = active && group.now == member
    val info = memberInfo(controller, proxies, member, selected)
    val color by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        label = "serverTile",
    )
    val corner by animateDpAsState(if (selected) 28.dp else 16.dp, tween(220), label = "serverTileCorner")
    val compact = density == ServerDensity.Compact
    val lead = if (compact) 28.dp else 32.dp
    val inset = if (compact) 10.dp else 12.dp
    val shape = RoundedCornerShape(corner)
    val titleStyle = if (compact) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleSmall
    val titleHeight = with(androidx.compose.ui.platform.LocalDensity.current) { titleStyle.lineHeight.toDp() } * 2
    val menu = rememberMenuState()
    val hasMenu = info.inner == null && member !in AppController.BUILT_IN
    Surface(
        shape = shape,
        color = color,
        modifier = Modifier.fillMaxWidth().clip(shape).then(if (hasMenu) Modifier.menuGesture(menu, controller.platform, SERVER_HOLD_MILLIS) else Modifier).clickable { onChoose(member) },
    ) {
        if (menu.used) ServerMenu(controller, member, info.title, menu)
        Column(Modifier.padding(inset), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = lead), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    info.leadIcon != null -> Icon(info.leadIcon, null, Modifier.size(lead).padding(4.dp), tint = MaterialTheme.colorScheme.primary)

                    flagged -> FlagBox(info.parsed.flag, lead, info.parsed.tags.firstOrNull(), (corner - inset).coerceAtLeast(6.dp))
                }
                TagMarks(info.parsed)
                PinMark(member)
                Spacer(Modifier.weight(1f))
                if (hasMenu) PingBadge(controller, member, delays, testing, showUnit = !compact, height = lead)
                if (info.inner?.let(::openableType) == true) {
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Box(Modifier.fillMaxWidth().height(titleHeight), contentAlignment = Alignment.BottomStart) {
                Text(info.title, style = titleStyle, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (density == ServerDensity.Roomy) {
                Text(info.subtitle.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun MemberRow(
    controller: AppController,
    proxies: ProxySnapshot,
    group: ProxyGroup,
    member: String,
    delays: State<Map<String, Int>>,
    testing: State<Set<String>>,
    flagged: Boolean,
    shape: Shape,
    active: Boolean,
    onChoose: (String) -> Unit,
) {
    val selected = active && group.now == member
    val info = memberInfo(controller, proxies, member, selected)
    val inner = info.inner
    val parsed = info.parsed
    val title = info.title
    val subtitle = info.subtitle
    val leadIcon = info.leadIcon
    val color by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        label = "server",
    )
    val menu = rememberMenuState()
    val hasMenu = inner == null && member !in AppController.BUILT_IN
    Surface(
        shape = shape,
        color = color,
        modifier = Modifier.fillMaxWidth().clip(shape).then(if (hasMenu) Modifier.menuGesture(menu, controller.platform, SERVER_HOLD_MILLIS) else Modifier).clickable { onChoose(member) },
    ) {
        if (menu.used) ServerMenu(controller, member, title, menu)
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp).heightIn(min = 40.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                leadIcon != null -> Icon(leadIcon, null, Modifier.size(36.dp).padding(6.dp), tint = MaterialTheme.colorScheme.primary)
                flagged -> FlagBox(parsed.flag, tag = parsed.tags.firstOrNull())
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    TagMarks(parsed)
                    PinMark(member)
                }
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (hasMenu) PingBadge(controller, member, delays, testing, showUnit = true, height = 36.dp)
                AnimatedVisibility(selected) { Icon(Icons.Rounded.Check, null, Modifier.padding(start = 12.dp), tint = MaterialTheme.colorScheme.primary) }
            }
            if (inner?.let(::openableType) == true) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ProfilesPage(controller: AppController, nav: Navigator) {
    val settings by controller.settings.collectAsState()
    val profiles by controller.profiles.collectAsState()
    var deleting by remember { mutableStateOf<Profile?>(null) }
    val list = rememberLazyListState()
    var picked by remember { mutableStateOf<String?>(null) }
    var reveal by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(settings.activeProfile) { if (picked == settings.activeProfile) picked = null }
    val activeId = picked ?: settings.activeProfile
    val tints = LocalIconTints.current
    fun tintOf(profile: Profile) = tints[profiles.indexOf(profile).coerceAtLeast(0) % tints.size]

    fun choose(profile: Profile) {
        controller.platform.haptic(Haptic.Toggle)
        picked = profile.id
        reveal = profile.id
        controller.activate(profile.id)
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        state = list,
        contentPadding = PaddingValues(16.dp),
    ) {
        itemsIndexed(profiles, key = { _, it -> it.id }) { index, profile ->
            val expanded = profile.id == activeId
            val afterOpen = profiles.getOrNull(index - 1)?.id == activeId
            val beforeOpen = profiles.getOrNull(index + 1)?.id == activeId
            val gap by animateDpAsState(
                when {
                    index == 0 -> 0.dp
                    expanded || afterOpen -> 12.dp
                    else -> 2.dp
                },
                ProfileSpring,
                label = "profileGap",
            )
            Box(Modifier.animateItem().padding(top = gap)) {
                ProfileItem(
                    controller,
                    profile,
                    expanded,
                    first = index == 0 || afterOpen,
                    last = index == profiles.lastIndex || beforeOpen,
                    tint = tintOf(profile),
                    menu = profileMenu(controller, profile, nav, !expanded) { deleting = profile },
                    reveal = reveal == profile.id,
                    onRevealed = { if (reveal == profile.id) reveal = null },
                ) { choose(profile) }
            }
        }
        item(key = "add") {
            Box(Modifier.animateItem().padding(top = 16.dp)) {
                OutlinedButton(
                    onClick = {
                        controller.platform.haptic(Haptic.Tick)
                        nav.push(Route.Add)
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Icon(Icons.Rounded.Add, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    ButtonText(stringResource(Res.string.servers_add))
                }
            }
        }
    }

    deleting?.let { profile ->
        AppDialog(
            stringResource(Res.string.servers_delete_q),
            stringResource(Res.string.servers_delete_text),
            stringResource(Res.string.cancel),
            stringResource(Res.string.delete),
            onDismiss = { deleting = null },
            onConfirm = {
                controller.delete(profile.id)
                deleting = null
            },
        )
    }
}

@Composable
private fun profileMenu(controller: AppController, profile: Profile, nav: Navigator, withRefresh: Boolean, onDelete: () -> Unit): List<MenuEntry> {
    val scope = rememberCoroutineScope()
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    val platform = controller.platform
    return buildList {
        if (withRefresh && (profile.url != null || profile.members.isNotEmpty())) {
            add(MenuEntry(stringResource(Res.string.servers_update), Icons.Rounded.Refresh) { controller.refreshProfile(profile.id) })
        }
        add(MenuEntry(stringResource(Res.string.edit_title), Icons.Rounded.Edit) { nav.push(Route.EditProfile(profile.id)) })
        if (profile.members.isNotEmpty()) add(MenuEntry(stringResource(Res.string.merge_edit), Icons.Rounded.Layers) { nav.push(Route.MergeProfiles(profile.id)) })
        add(MenuEntry(stringResource(Res.string.sub_share), Icons.Rounded.Share) { scope.launch { controller.shareProfile(profile) } })
        if (platform.canPinShortcuts) {
            add(MenuEntry(stringResource(Res.string.sub_shortcut), Icons.Rounded.AddToHomeScreen) {
                platform.pinProfileShortcut(profile.id, profile.name)
            })
        }
        profile.supportUrl?.let { link -> add(MenuEntry(stringResource(Res.string.sub_support), Icons.Rounded.SupportAgent) { runCatching { uri.openUri(link) } }) }
        profile.webUrl?.let { link -> add(MenuEntry(stringResource(Res.string.sub_site), Icons.Rounded.Public) { runCatching { uri.openUri(link) } }) }
        add(MenuEntry(stringResource(Res.string.delete), Icons.Rounded.DeleteOutline, onClick = onDelete))
    }
}

@Composable
private fun RefreshButton(controller: AppController, profile: Profile) {
    if (profile.url == null && profile.members.isEmpty()) return
    val refreshing by controller.refreshing.collectAsState()
    val cooldown by controller.refreshCooldown.collectAsState()
    val busy = profile.id in refreshing
    val resting = profile.id in cooldown
    val fade by animateFloatAsState(if (resting) 0.38f else 1f, tween(220), label = "refreshFade")
    IconButton(
        onClick = {
            controller.platform.haptic(Haptic.Tick)
            controller.refreshProfile(profile.id)
        },
        enabled = !busy && !resting,
        colors = IconButtonDefaults.iconButtonColors(disabledContentColor = LocalContentColor.current),
        modifier = Modifier.graphicsLayer { alpha = fade },
    ) {
        AnimatedContent(busy, label = "refresh", transitionSpec = { (fadeIn(tween(180)) + scaleIn(tween(220))) togetherWith (fadeOut(tween(120)) + scaleOut(tween(160))) }) { loading ->
            if (loading) LoadingIndicator(Modifier.size(24.dp)) else Icon(Icons.Rounded.Refresh, stringResource(Res.string.servers_update))
        }
    }
}

private fun daysLeft(profile: Profile): Long? =
    if (profile.expire > 0) (profile.expire - nowMillis() / 1000).floorDiv(86_400L) else null

private val ProfileSpring = spring<Dp>(dampingRatio = 0.9f, stiffness = 380f)

@Composable
private fun ProfileItem(
    controller: AppController,
    profile: Profile,
    expanded: Boolean,
    first: Boolean,
    last: Boolean,
    tint: IconTint,
    menu: List<MenuEntry>,
    reveal: Boolean = false,
    onRevealed: () -> Unit = {},
    onPick: () -> Unit,
) {
    val platform = controller.platform
    val state = rememberMenuState()
    val days = daysLeft(profile)
    val limited = profile.total > 0
    val trafficLeft = (profile.total - profile.used).coerceAtLeast(0)
    val ended = (days != null && days < 0) || (limited && trafficLeft == 0L)
    val soon = !ended && ((days != null && days <= 3) || (limited && profile.used.toDouble() / profile.total >= 0.9))
    val summary = when {
        days != null && days < 0 -> stringResource(Res.string.subs_expired)
        limited && trafficLeft == 0L -> stringResource(Res.string.subs_traffic_out)
        else -> buildList {
            if (days != null) add(stringResource(Res.string.subs_days_left, days.toInt()))
            if (limited) add(formatBytes(trafficLeft))
        }.joinToString(" · ").ifEmpty { stringResource(Res.string.servers_count, profile.servers) }
    }
    val parsed = remember(profile.name) { parseServerName(profile.name) }

    val top by animateDpAsState(if (expanded) 28.dp else if (first) 24.dp else 6.dp, ProfileSpring, label = "profileTop")
    val bottom by animateDpAsState(if (expanded) 28.dp else if (last) 24.dp else 6.dp, ProfileSpring, label = "profileBottom")
    val shape = RoundedCornerShape(top, top, bottom, bottom)
    val requester = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    LaunchedEffect(reveal) {
        if (reveal) {
            kotlinx.coroutines.delay(420)
            requester.bringIntoView()
            onRevealed()
        }
    }

    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().bringIntoViewRequester(requester).clip(shape)
            .menuGesture(state, platform, SERVER_HOLD_MILLIS)
            .then(if (expanded) Modifier else Modifier.clickable(onClick = onPick)),
    ) {
        if (state.used) MenuForState(state, menu)
        Column {
            Row(
                Modifier.padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(tint.container),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        profile.members.isNotEmpty() -> Icon(Icons.Rounded.Layers, null, tint = tint.onContainer)
                        parsed.flag != null -> FlagBox(parsed.flag, 44.dp, null, 14.dp)
                        else -> Text(
                            parsed.label.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "•",
                            style = MaterialTheme.typography.titleLargeEmphasized,
                            color = tint.onContainer,
                        )
                    }
                }
                Column(Modifier.weight(1f).padding(start = 14.dp, end = 4.dp)) {
                    Text(parsed.label.ifBlank { profile.name }, style = MaterialTheme.typography.titleMediumEmphasized, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    AnimatedContent(
                        expanded,
                        label = "profileSubtitle",
                        transitionSpec = { fadeIn(tween(240, 90)) togetherWith fadeOut(tween(140)) using SizeTransform(clip = false) { _, _ -> spring(dampingRatio = 1f, stiffness = 500f) } },
                    ) { open ->
                        if (open) {
                            Text(stringResource(Res.string.subs_in_use), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                        } else {
                            Text(
                                summary,
                                style = MaterialTheme.typography.bodyMedium,
                                color = when {
                                    ended -> MaterialTheme.colorScheme.error
                                    soon -> MaterialTheme.colorScheme.tertiary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy((-10).dp), verticalAlignment = Alignment.CenterVertically) {
                    AnimatedVisibility(
                        expanded,
                        enter = fadeIn(tween(220, 80)) + expandHorizontally(spring(dampingRatio = 1f, stiffness = 420f), expandFrom = Alignment.End),
                        exit = fadeOut(tween(120)) + shrinkHorizontally(spring(dampingRatio = 1f, stiffness = 500f), shrinkTowards = Alignment.End),
                    ) {
                        RefreshButton(controller, profile)
                    }
                    MenuButton(menu)
                }
            }
            AnimatedVisibility(
                expanded,
                enter = expandVertically(spring(dampingRatio = 1f, stiffness = 380f), expandFrom = Alignment.Top) + fadeIn(tween(260, 100)),
                exit = shrinkVertically(spring(dampingRatio = 1f, stiffness = 380f), shrinkTowards = Alignment.Top) + fadeOut(tween(140)),
            ) {
                Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SubscriptionNotices(profile)
                    SubscriptionStats(profile)
                    SmallInfo(stringResource(Res.string.servers_count, profile.servers) + (if (profile.updatedAt > 0) " · " + stringResource(Res.string.servers_updated_at, formatDate(profile.updatedAt / 1000)) else ""))
                }
            }
        }
    }
}

private const val COLLAPSED_LINES = 2
private val FoldEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

@Composable
private fun FoldableText(text: String, foldKey: String, modifier: Modifier = Modifier) {
    val platform = LocalPlatform.current
    var open by rememberSaveable(foldKey, text) { mutableStateOf(false) }
    var folded by remember(text) { mutableStateOf<Int?>(null) }
    val fraction by animateFloatAsState(if (open) 1f else 0f, tween(280, easing = FoldEasing), label = "fold")
    val canFold = folded != null
    Row(
        modifier.then(
            if (canFold) {
                Modifier.clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null) {
                    platform.haptic(Haptic.Tick)
                    open = !open
                }
            } else {
                Modifier
            },
        ),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.weight(1f).clipToBounds().layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(maxHeight = Constraints.Infinity))
                val low = folded ?: placeable.height
                val height = low + ((placeable.height - low) * fraction).toInt()
                layout(placeable.width, height) { placeable.place(0, 0) }
            },
        ) {
            Text(text, style = MaterialTheme.typography.bodyMedium, onTextLayout = { result ->
                folded = if (result.lineCount > COLLAPSED_LINES) result.getLineBottom(COLLAPSED_LINES - 1).toInt() else null
            })
        }
        if (canFold) Icon(Icons.Rounded.ExpandMore, null, Modifier.size(20.dp).graphicsLayer { rotationZ = fraction * 180f })
    }
}

@Composable
fun SubscriptionNotices(profile: Profile) {
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    val scheme = MaterialTheme.colorScheme
    GapColumn(Modifier.fillMaxWidth(), gap = 2.dp) {
        profile.announce?.let { text ->
            Surface(shape = RoundedCornerShape(20.dp), color = scheme.surfaceContainerHigh) {
                Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Rounded.Campaign, null, tint = scheme.primary)
                    FoldableText(text, "announce-${profile.id}", Modifier.weight(1f))
                }
            }
        }
        profile.info?.let { info ->
            val (container, content) = when (info.color) {
                "red" -> scheme.errorContainer to scheme.onErrorContainer
                "green" -> scheme.tertiaryContainer to scheme.onTertiaryContainer
                else -> scheme.primaryContainer to scheme.onPrimaryContainer
            }
            Surface(shape = RoundedCornerShape(20.dp), color = container, contentColor = content) {
                GapColumn(Modifier.fillMaxWidth().padding(14.dp), gap = 10.dp) {
                    FoldableText(info.text, "info-${profile.id}")
                    if (info.buttonLink != null && info.buttonText != null) {
                        OutlinedButton(onClick = { runCatching { uri.openUri(info.buttonLink) } }, modifier = Modifier.fillMaxWidth(), shapes = ButtonDefaults.shapes()) {
                            ButtonText(info.buttonText)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SubscriptionStats(profile: Profile, flat: Boolean = false) {
    val limited = profile.total > 0
    val color = if (flat) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerHighest
    val outer = if (!flat) 12.dp else if (limited) 6.dp else 24.dp
    val inner = if (flat) 6.dp else 4.dp
    val top = if (flat) 6.dp else outer
    Column(verticalArrangement = Arrangement.spacedBy(if (flat) 2.dp else 10.dp)) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            StatCell(
                stringResource(Res.string.stat_traffic),
                if (limited) formatBytes((profile.total - profile.used).coerceAtLeast(0)) else "∞",
                if (limited) stringResource(Res.string.stat_left) else stringResource(Res.string.stat_unlimited),
                RoundedCornerShape(top, inner, inner, outer),
                color,
                Modifier.weight(1f).fillMaxHeight(),
            )
            val days = if (profile.expire > 0) ((profile.expire - nowMillis() / 1000) / 86_400).coerceAtLeast(0) else null
            StatCell(
                stringResource(Res.string.stat_term),
                if (days != null) stringResource(Res.string.stat_days, days.toInt()) else "∞",
                if (days != null) formatDate(profile.expire) else stringResource(Res.string.stat_no_term),
                RoundedCornerShape(inner, top, outer, inner),
                color,
                Modifier.weight(1f).fillMaxHeight(),
            )
        }
        if (limited) {
            val used by animateFloatAsState((profile.used.toFloat() / profile.total).coerceIn(0f, 1f), tween(800), label = "usage")
            val usage: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LinearWavyProgressIndicator(progress = { used }, modifier = Modifier.fillMaxWidth())
                    SmallInfo(stringResource(Res.string.w_sub_used, formatBytes(profile.used), formatBytes(profile.total)))
                }
            }
            if (flat) {
                Surface(shape = RoundedCornerShape(6.dp, 6.dp, 24.dp, 24.dp), color = color) {
                    Box(Modifier.fillMaxWidth().padding(16.dp)) { usage() }
                }
            } else {
                usage()
            }
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, note: String, shape: Shape, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Surface(shape = shape, color = color, modifier = modifier) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLargeEmphasized, maxLines = 1)
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SmallInfo(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun AddSubscriptionScreen(controller: AppController, onDone: () -> Unit, onManual: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val pending by controller.pendingImport.collectAsState()
    val platform = controller.platform
    var field by remember { mutableStateOf(pending.orEmpty()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<ImportError?>(null) }
    var noLink by remember { mutableStateOf(false) }

    LaunchedEffect(pending) {
        pending?.let {
            field = it
            controller.consumeImport()
        }
    }

    fun submit(text: String) {
        loading = true
        error = null
        noLink = false
        scope.launch {
            val result = controller.importSubscription(text)
            if (result == null) {
                platform.haptic(Haptic.Toggle)
                onDone()
            } else {
                platform.haptic(Haptic.Failed)
                loading = false
                error = result
            }
        }
    }

    val scroll = rememberScrollState()

    val keyboard = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
    val mark = remember { PolygonShape(MaterialShapes.Cookie9Sided) }
    val divider by animateFloatAsState(if (scroll.canScrollForward) 1f else 0f, tween(160), label = "addDivider")

    SubScreen(stringResource(Res.string.add_title), onDone, collapsing = false) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                Column(
                    Modifier.fillMaxWidth().verticalScroll(scroll).heightIn(min = maxHeight).padding(16.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    AnimatedVisibility(!keyboard, enter = RevealEnter, exit = RevealExit) {
                        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(96.dp).clip(mark).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Link, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                            Spacer(Modifier.height(20.dp))
                            Text(stringResource(Res.string.add_heading), style = MaterialTheme.typography.headlineSmallEmphasized, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                stringResource(Res.string.add_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        }
                    }
                    GapColumn {
                        AppTextField(
                            field, { field = it }, stringResource(Res.string.add_field), maxLines = 5,
                            trailing = if (field.isEmpty()) {
                                null
                            } else {
                                {
                                    IconButton(onClick = {
                                        platform.haptic(Haptic.Tick)
                                        field = ""
                                        error = null
                                    }) { Icon(Icons.Rounded.Close, null) }
                                }
                            },
                        )
                        MessageBanner(if (noLink) stringResource(Res.string.add_clipboard_empty) else error?.let { stringResource(it.label()) })
                    }
                }
            }
            HorizontalDivider(Modifier.graphicsLayer { alpha = divider })
            Box(Modifier.navigationBarsPadding().padding(16.dp)) {
                AnimatedContent(loading, label = "addState", transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) using SizeTransform(clip = false) }) { busy ->
                    if (busy) {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            ContainedLoadingIndicator(Modifier.size(64.dp))
                            Text(stringResource(Res.string.add_loading))
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            val buttonText = MaterialTheme.typography.titleMedium
                            AnimatedContent(field.isNotBlank(), label = "addPrimary", transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) }) { filled ->
                                Button(
                                    onClick = {
                                        if (filled) {
                                            submit(field)
                                        } else {
                                            val text = platform.readClipboard()?.trim().orEmpty()
                                            if (text.contains("://") || text.contains("proxies:") || dev.rooni.zefir.app.isWireguardConf(text)) {
                                                field = text
                                                submit(text)
                                            } else {
                                                platform.haptic(Haptic.Failed)
                                                noLink = true
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth().height(64.dp),
                                    shape = RoundedCornerShape(28.dp, 28.dp, 6.dp, 6.dp),
                                ) {
                                    Icon(if (filled) Icons.Rounded.Add else Icons.Rounded.ContentPaste, null)
                                    Spacer(Modifier.width(10.dp))
                                    ButtonText(stringResource(if (filled) Res.string.add_action else Res.string.add_paste), style = buttonText)
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        platform.haptic(Haptic.Tick)
                                        scope.launch { platform.pickFile()?.let(::submit) }
                                    },
                                    modifier = Modifier.weight(1f).height(56.dp),
                                    shape = RoundedCornerShape(6.dp),
                                ) {
                                    Icon(Icons.Rounded.FileOpen, null, Modifier.size(20.dp))
                                    Spacer(Modifier.width(8.dp))
                                    ButtonText(stringResource(Res.string.add_file))
                                }
                                if (platform.canScanQr) {
                                    OutlinedButton(
                                        onClick = {
                                            platform.haptic(Haptic.Tick)
                                            scope.launch { platform.scanQr()?.let(::submit) }
                                        },
                                        modifier = Modifier.weight(1f).height(56.dp),
                                        shape = RoundedCornerShape(6.dp),
                                    ) {
                                        Icon(Icons.Rounded.QrCodeScanner, null, Modifier.size(20.dp))
                                        Spacer(Modifier.width(8.dp))
                                        ButtonText(stringResource(Res.string.qr_scan))
                                    }
                                }
                            }
                            OutlinedButton(
                                onClick = {
                                    platform.haptic(Haptic.Tick)
                                    onManual()
                                },
                                modifier = Modifier.fillMaxWidth().height(56.dp),
                                shape = RoundedCornerShape(6.dp, 6.dp, 28.dp, 28.dp),
                            ) {
                                Icon(Icons.Rounded.EditNote, null, Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                ButtonText(stringResource(Res.string.manual_open))
                            }
                        }
                    }
                }
            }
        }
    }
}

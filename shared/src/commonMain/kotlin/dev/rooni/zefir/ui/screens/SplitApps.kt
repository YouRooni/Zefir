package dev.rooni.zefir.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.rooni.zefir.app.AppController
import dev.rooni.zefir.model.*
import dev.rooni.zefir.platform.Haptic
import dev.rooni.zefir.res.*
import dev.rooni.zefir.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@Composable
fun SplitAppsScreen(controller: AppController, onBack: () -> Unit) {
    val settings by controller.settings.collectAsState()
    val apps by controller.apps.collectAsState()
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var showSystem by remember { mutableStateOf(false) }
    val region = controller.region
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val platform = controller.platform

    LaunchedEffect(Unit) { controller.loadApps() }

    var dirty by remember { mutableStateOf(0) }
    LaunchedEffect(dirty) {
        if (dirty > 0) {
            delay(1200)
            controller.reloadAndRestart()
        }
    }

    val selected = if (settings.splitMode == SplitMode.Only) settings.onlyApps else settings.excludedApps

    fun setSelected(transform: (Set<String>) -> Set<String>) {
        controller.update({ s ->
            if (s.splitMode == SplitMode.Only) s.copy(onlyApps = transform(s.onlyApps))
            else s.copy(excludedApps = transform(s.excludedApps))
        })
        dirty++
    }

    val visible = remember(apps, query, showSystem, settings.splitMode) {
        val current = selected
        apps.orEmpty()
            .filter { showSystem || !it.system || it.pkg in current }
            .filter { query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true) }
            .sortedByDescending { it.pkg in current }
    }
    val active = settings.splitMode != SplitMode.Off

    val pinnedCount = remember(visible) { visible.count { it.pkg in selected } }

    LaunchedEffect(visible) { visible.forEach { platform.loadAppIcon(it.pkg) } }
    val packApps = remember(apps, region) {
        region?.packs.orEmpty().associateWith { pack -> apps.orEmpty().filter { pack.matches(it.pkg) }.map { it.pkg }.toSet() }
    }
    SubScreen(
        stringResource(Res.string.apps_title),
        if (searching) ({
            searching = false
            query = ""
        }) else onBack,
        collapsing = false,
        titleContent = if (searching) ({ SearchInput(query, { query = it }, stringResource(Res.string.apps_search)) }) else null,
        actions = {
            AnimatedVisibility(active && !searching, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
                IconButton(onClick = { searching = true }) { Icon(Icons.Rounded.Search, stringResource(Res.string.apps_search)) }
            }
            AnimatedVisibility(searching && query.isNotEmpty(), enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
                IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, null) }
            }
            AnimatedVisibility(active) {
                MenuButton(
                    buildList {
                        add(MenuEntry(stringResource(Res.string.apps_select_all), Icons.Rounded.DoneAll) { setSelected { it + visible.map(InstalledApp::pkg) } })
                        add(MenuEntry(stringResource(Res.string.apps_clear), Icons.Rounded.RemoveDone) { setSelected { emptySet() } })
                        add(MenuEntry(stringResource(Res.string.apps_system), Icons.Rounded.Android, showSystem) {
                            scope.launch {
                                delay(150)
                                showSystem = !showSystem
                            }
                        })
                        if (list.canScrollBackward) add(MenuEntry(stringResource(Res.string.apps_to_top), Icons.Rounded.VerticalAlignTop) { scope.launch { list.animateScrollToItem(0) } })
                        if (list.canScrollForward) add(MenuEntry(stringResource(Res.string.apps_to_bottom), Icons.Rounded.VerticalAlignBottom) { scope.launch { list.animateScrollToItem(list.layoutInfo.totalItemsCount - 1) } })
                    },
                )
            }
        },
    ) { padding ->
      Box(Modifier.padding(padding).fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().imePadding(),
            state = list,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item(key = "mode") {
                GapColumn(Modifier.padding(bottom = 12.dp)) {
                    ChoiceButtons(
                        listOf(
                            SplitMode.Off to stringResource(Res.string.apps_mode_off),
                            SplitMode.Exclude to stringResource(Res.string.apps_mode_exclude),
                            SplitMode.Only to stringResource(Res.string.apps_mode_only),
                        ),
                        settings.splitMode,
                    ) { mode ->
                        controller.update({ it.copy(splitMode = mode) })
                        dirty++
                    }
                    AnimatedContent(settings.splitMode, label = "hint") { mode ->
                        Text(
                            stringResource(
                                when (mode) {
                                    SplitMode.Off -> Res.string.apps_mode_off_hint
                                    SplitMode.Exclude -> Res.string.apps_mode_exclude_hint
                                    SplitMode.Only -> Res.string.apps_mode_only_hint
                                },
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp).fillMaxWidth(),
                        )
                    }
                    AnimatedVisibility(region != null && settings.splitMode == SplitMode.Exclude, enter = RevealEnter, exit = RevealExit) {
                        val chips = rememberScrollState()
                        Row(
                            Modifier.fillMaxWidth().edgeFade(chips).horizontalScroll(chips),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            region?.packs.orEmpty().forEach { pack ->
                                val packed = packApps[pack].orEmpty()
                                val on = packed.isNotEmpty() && packed.all { it in selected }
                                FilterChip(
                                    selected = on,
                                    enabled = packed.isNotEmpty(),
                                    onClick = {
                                        platform.haptic(Haptic.Tick)
                                        setSelected { if (on) it - packed else it + packed }
                                    },
                                    label = { Text(stringResource(pack.title)) },
                                )
                            }
                        }
                    }
                }
            }
            if (active) {
                when {
                    apps == null -> item(key = "loading") {
                        Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
                    }
                    visible.isEmpty() -> item(key = "empty") {
                        Text(
                            stringResource(if (apps!!.isEmpty()) Res.string.apps_unavailable else Res.string.apps_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                    else -> items(visible.size, key = { visible[it].pkg }, contentType = { "app" }) { index ->
                        val app = visible[index]
                        AppRow(controller, app, app.pkg in selected, positionShape(index, visible.lastIndex)) { on ->
                            platform.haptic(Haptic.Tick)
                            setSelected { if (on) it + app.pkg else it - app.pkg }
                        }
                    }
                }
            }
        }
        if (active && visible.size > 12) {
            FastScroller(
                list,
                visible.size,
                { index ->
                    val first = visible[index].label.firstOrNull()?.uppercaseChar() ?: '#'
                    if (index < pinnedCount) '★' else if (first.isLetter()) first else '#'
                },
                Modifier.align(Alignment.CenterEnd),
            ) { platform.haptic(Haptic.Tick) }
        }
      }
    }
}

@Composable
fun SearchInput(value: String, onChange: (String) -> Unit, hint: String) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Box(contentAlignment = Alignment.CenterStart) {
        if (value.isEmpty()) {
            Text(hint, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        BasicTextField(
            value, onChange,
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            singleLine = true,
            textStyle = MaterialTheme.typography.titleMedium.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        )
    }
}

@Composable
private fun FastScroller(
    list: LazyListState,
    count: Int,
    letterAt: (Int) -> Char,
    modifier: Modifier,
    onLetter: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    var fingerIndex by remember { mutableStateOf(0) }
    var trackHeight by remember { mutableStateOf(1) }
    var keepShown by remember { mutableStateOf(false) }
    val scrolling = list.isScrollInProgress
    LaunchedEffect(scrolling, dragging) {
        if (scrolling || dragging) {
            keepShown = true
        } else {
            delay(1200)
            keepShown = false
        }
    }
    val alpha by animateFloatAsState(if (keepShown) 1f else 0f, tween(250), label = "scrollerAlpha")
    val width by animateDpAsState(if (dragging) 12.dp else 6.dp, spring(0.6f, 500f), label = "scrollerWidth")
    val thumbHeight by animateDpAsState(if (dragging) 72.dp else 48.dp, spring(0.6f, 500f), label = "scrollerHeight")
    val density = LocalDensity.current

    val progress by remember(count) {
        derivedStateOf { ((list.firstVisibleItemIndex - 1).coerceAtLeast(0).toFloat() / (count - 1).coerceAtLeast(1)).coerceIn(0f, 1f) }
    }

    fun pick(y: Float) {
        val index = ((y / trackHeight) * count).toInt().coerceIn(0, count - 1)
        if (letterAt(index) != letterAt(fingerIndex)) onLetter()
        fingerIndex = index
        scope.launch { list.scrollToItem(index + 1) }
    }

    Box(
        modifier.fillMaxHeight().width(if (keepShown) 40.dp else 0.dp).padding(vertical = 16.dp).onSizeChanged { trackHeight = it.height.coerceAtLeast(1) }.then(
            if (keepShown) {
                Modifier.pointerInput(count) {
                    detectDragGestures(
                        onDragStart = {
                            dragging = true
                            pick(it.y)
                        },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                        onDrag = { change, _ ->
                            change.consume()
                            pick(change.position.y)
                        },
                    )
                }
            } else {
                Modifier
            },
        ),
    ) {
        val offset = { IntOffset(0, ((trackHeight - with(density) { thumbHeight.roundToPx() }) * progress).toInt().coerceAtLeast(0)) }
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.align(Alignment.TopEnd).padding(end = 4.dp).offset { offset() }.size(width, thumbHeight).graphicsLayer { this.alpha = alpha },
        ) {}
    }
    Box(modifier.fillMaxHeight().padding(end = 28.dp, top = 16.dp, bottom = 16.dp)) {
        AnimatedVisibility(
            dragging,
            enter = fadeIn() + scaleIn(spring(0.55f, 500f), initialScale = 0.4f),
            exit = fadeOut() + scaleOut(targetScale = 0.4f),
            modifier = Modifier.offset { IntOffset(0, (trackHeight * progress).toInt().coerceAtMost((trackHeight - with(density) { 72.dp.roundToPx() }).coerceAtLeast(0))) },
        ) {
            val shape = remember { PolygonShape(MaterialShapes.Cookie9Sided) }
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(72.dp)) {
                Surface(shape = shape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxSize()) {}
                AnimatedContent(
                    letterAt(fingerIndex.coerceIn(0, (count - 1).coerceAtLeast(0))),
                    label = "letter",
                    transitionSpec = { (slideInVertically { it / 2 } + fadeIn()) togetherWith (slideOutVertically { -it / 2 } + fadeOut()) },
                ) { letter ->
                    Text(letter.toString(), style = MaterialTheme.typography.headlineMediumEmphasized, color = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
    }
}

@Composable
private fun AppRow(controller: AppController, app: InstalledApp, checked: Boolean, shape: RoundedCornerShape, onChange: (Boolean) -> Unit) {
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().clip(shape).clickable { onChange(!checked) },
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val platform = controller.platform
            val icon by produceState(platform.cachedAppIcon(app.pkg), app.pkg) {
                if (value == null) value = platform.loadAppIcon(app.pkg)
            }

            Box(Modifier.size(40.dp)) {
                icon?.let { Image(it, null, Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp))) }
            }
            Column(Modifier.weight(1f)) {
                Text(app.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    app.pkg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Checkbox(checked, onChange)
        }
    }
}

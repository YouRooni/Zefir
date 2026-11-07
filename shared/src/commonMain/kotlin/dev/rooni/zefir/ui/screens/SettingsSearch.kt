package dev.rooni.zefir.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.rooni.zefir.app.AppController
import dev.rooni.zefir.platform.Haptic
import dev.rooni.zefir.res.*
import dev.rooni.zefir.ui.*
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

private class Found(val spot: SettingSpot, val title: String, val description: String, val path: String, val section: String, val detail: String)

private const val HISTORY_SIZE = 8

private fun search(all: List<Found>, query: String): List<Found> {
    val words = query.trim().lowercase().split(' ').filter { it.isNotEmpty() }
    if (words.isEmpty()) return emptyList()
    return all.filter { item ->
        val text = "${item.title} ${item.description} ${item.path} ${item.spot.keywords}".lowercase()
        words.all { it in text }
    }.sortedBy { item ->
        val title = item.title.lowercase()
        when {
            title.startsWith(words.first()) -> 0
            words.all { it in title } -> 1
            else -> 2
        }
    }
}

@Composable
private fun highlight(text: String, query: String): AnnotatedString {
    val color = MaterialTheme.colorScheme.primary
    return remember(text, query, color) {
        val words = query.trim().lowercase().split(' ').filter { it.isNotEmpty() }
        val lower = text.lowercase()
        val marks = BooleanArray(text.length)
        words.forEach { word ->
            var at = lower.indexOf(word)
            while (at >= 0) {
                for (i in at until at + word.length) if (i < marks.size) marks[i] = true
                at = lower.indexOf(word, at + word.length)
            }
        }
        buildAnnotatedString {
            text.forEachIndexed { index, char ->
                if (marks[index]) withStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold)) { append(char) } else append(char)
            }
        }
    }
}

@Composable
private fun FoundRow(item: Found, subtitle: String, query: String, shape: Shape, onClick: () -> Unit) {
    Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().clip(shape).clickable(onClick = onClick)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(item.spot.icon, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(highlight(item.title, query), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotEmpty()) {
                    Text(highlight(subtitle, query), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun AnimatedVisibilityScope.SettingsSearch(controller: AppController, nav: Navigator, onClose: () -> Unit) {
    val settings by controller.settings.collectAsState()
    val platform = controller.platform
    var query by remember { mutableStateOf("") }
    val all by produceState(emptyList<Found>()) {
        val root = getString(Res.string.tab_settings)
        value = SettingSpots.all(platform, controller.region != null).map { spot ->
            val parents = spot.parents.map { getString(it) }
            val description = spot.description?.let { getString(it) }.orEmpty()
            val detail = if (parents.size > 1) parents.drop(1).joinToString(" › ") else description
            Found(spot, getString(spot.title), description, (listOf(root) + parents).joinToString(" › "), parents.firstOrNull() ?: root, detail)
        }
    }
    val found = remember(all, query) { search(all, query) }
    val recent = remember(all, settings.searchHistory) { settings.searchHistory.mapNotNull { id -> all.firstOrNull { it.spot.title.key == id } } }
    val typing = query.isNotBlank()

    fun open(item: Found) {
        platform.haptic(Haptic.Tick)
        val id = item.spot.title.key
        controller.update({ it.copy(searchHistory = (listOf(id) + it.searchHistory.filter { old -> old != id }).take(HISTORY_SIZE)) })
        if (item.spot.advanced) controller.update({ it.copy(showAdvanced = true) })
        onClose()
        nav.openSetting(item.spot)
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shadowElevation = 8.dp, modifier = Modifier.zIndex(1f)) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(Res.string.back)) }
                Box(Modifier.weight(1f).padding(horizontal = 4.dp)) { SearchInput(query, { query = it }, stringResource(Res.string.search_hint)) }
                AnimatedVisibility(query.isNotEmpty(), enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
                    IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, null) }
                }
            }
        }
        val shown = if (typing) found else recent
        Box(Modifier.weight(1f).fillMaxWidth().imePadding().animateEnterExit(enter = slideInVertically(tween(280)) { it / 16 }, exit = slideOutVertically(tween(180)) { it / 24 }), contentAlignment = Alignment.TopCenter) {
            if (typing && shown.isEmpty()) {
                Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(Icons.Rounded.SearchOff, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(Res.string.search_empty), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.widthIn(max = ContentMaxWidth).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (!typing && shown.isNotEmpty()) {
                        item(key = "recent") {
                            Row(Modifier.fillMaxWidth().animateItem().padding(start = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(Res.string.search_recent), style = MaterialTheme.typography.titleSmallEmphasized, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                                IconButton(onClick = {
                                    platform.haptic(Haptic.Tick)
                                    controller.update({ it.copy(searchHistory = emptyList()) })
                                }) { Icon(Icons.Rounded.DeleteSweep, stringResource(Res.string.search_clear), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                    }
                    if (typing) {
                        shown.groupBy { it.section }.forEach { (section, rows) ->
                            item(key = "head-$section") { Box(Modifier.animateItem()) { GroupTitle(section) } }
                            itemsIndexed(rows, key = { _, item -> item.spot.title.key }) { index, item ->
                                Box(Modifier.animateItem()) { FoundRow(item, item.detail, query, positionShape(index, rows.lastIndex)) { open(item) } }
                            }
                        }
                    } else {
                        itemsIndexed(shown, key = { _, item -> item.spot.title.key }) { index, item ->
                            Box(Modifier.animateItem()) { FoundRow(item, item.path, query, positionShape(index, shown.lastIndex)) { open(item) } }
                        }
                    }
                }
            }
        }
    }
}

package dev.rooni.zefir.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.rooni.zefir.app.AppController
import dev.rooni.zefir.app.RuleSource
import dev.rooni.zefir.app.SiteRoute
import dev.rooni.zefir.core.DnsEntry
import dev.rooni.zefir.model.RuleAction
import dev.rooni.zefir.model.TunnelState
import dev.rooni.zefir.platform.Haptic
import dev.rooni.zefir.res.*
import dev.rooni.zefir.ui.*
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun SiteCheckScreen(controller: AppController, onBack: () -> Unit, initial: String? = null) {
    val scope = rememberCoroutineScope()
    val platform = controller.platform
    var field by remember { mutableStateOf(initial.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<SiteRoute?>(null) }
    var bad by remember { mutableStateOf(false) }

    fun check() {
        if (busy) return
        bad = false
        busy = true
        scope.launch {
            val found = controller.checkSite(field)
            busy = false
            if (found == null) {
                bad = true
                platform.haptic(Haptic.Failed)
            } else {
                result = found
                platform.haptic(Haptic.Tick)
            }
        }
    }
    LaunchedEffect(Unit) { if (field.isNotBlank()) check() }

    SubScreen(stringResource(Res.string.site_title), onBack) { padding ->
        ScrollColumn(Modifier.padding(padding)) {
            Text(stringResource(Res.string.site_desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
            AppTextField(
                field,
                { field = it; bad = false },
                stringResource(Res.string.site_hint),
                modifier = Modifier.fillMaxWidth(),
            )
            AnimatedVisibility(bad, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Notice(Icons.Rounded.ErrorOutline, stringResource(Res.string.site_bad), error = true)
            }
            Button(
                onClick = ::check,
                enabled = field.isNotBlank() && !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                shapes = ButtonDefaults.shapes(),
            ) {
                AnimatedContent(busy, label = "siteBusy") { loading ->
                    if (loading) LoadingIndicator(Modifier.size(24.dp)) else ButtonText(stringResource(Res.string.site_check))
                }
            }
            AnimatedContent(
                result,
                label = "siteResult",
                transitionSpec = { (fadeIn(tween(220, 60)) + scaleIn(spring(0.85f, 500f), 0.96f)) togetherWith fadeOut(tween(120)) using SizeTransform(clip = false) },
            ) { route ->
                if (route != null) RouteVerdict(controller, route.host, route.exit, route.rule, route.payload, route.source, route.error)
            }
        }
    }
}

@Composable
private fun Notice(icon: ImageVector, text: String, error: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(20.dp), color = if (error) scheme.errorContainer else scheme.surfaceContainerHigh, contentColor = if (error) scheme.onErrorContainer else scheme.onSurface) {
        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null)
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun sourceText(source: RuleSource): StringResource = when (source) {
    RuleSource.Yours -> Res.string.src_yours
    RuleSource.Local -> Res.string.src_local
    RuleSource.Ads -> Res.string.src_ads
    RuleSource.Region -> Res.string.src_region
    RuleSource.Subscription -> Res.string.src_subscription
    RuleSource.Default -> Res.string.src_default
    RuleSource.Mode -> Res.string.src_mode
    RuleSource.Unknown -> Res.string.src_unknown
}

@Composable
fun RouteVerdict(controller: AppController, host: String, exit: String, rule: String, payload: String, source: RuleSource, error: String?) {
    val scheme = MaterialTheme.colorScheme
    val platform = controller.platform
    val direct = exit == "DIRECT"
    val blocked = exit.startsWith("REJECT")
    var added by remember(host) { mutableStateOf<RuleAction?>(null) }
    val (icon, title, container, content) = when {
        exit.isEmpty() -> Quad(Icons.Rounded.ErrorOutline, stringResource(Res.string.site_failed), scheme.errorContainer, scheme.onErrorContainer)
        blocked -> Quad(Icons.Rounded.Block, stringResource(Res.string.site_blocked), scheme.errorContainer, scheme.onErrorContainer)
        direct -> Quad(Icons.Rounded.Language, stringResource(Res.string.site_direct), scheme.tertiaryContainer, scheme.onTertiaryContainer)
        else -> Quad(Icons.Rounded.VpnLock, stringResource(Res.string.site_vpn, parseServerName(exit).label.ifBlank { exit }), scheme.primaryContainer, scheme.onPrimaryContainer)
    }
    GapColumn(Modifier.fillMaxWidth(), gap = 2.dp) {
        Surface(shape = RoundedCornerShape(28.dp, 28.dp, 6.dp, 6.dp), color = container, contentColor = content) {
            Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).background(content.copy(alpha = 0.12f), RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) { Icon(icon, null) }
                Column(Modifier.weight(1f)) {
                    Text(host, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(title, style = MaterialTheme.typography.titleLargeEmphasized)
                }
            }
        }
        Surface(shape = RoundedCornerShape(6.dp, 6.dp, 28.dp, 28.dp), color = scheme.surfaceContainer) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(if (exit.isEmpty()) Res.string.src_unknown else sourceText(source)), style = MaterialTheme.typography.bodyLarge)
                if (rule.isNotEmpty()) {
                    Text(
                        listOf(rule, payload).filter { it.isNotEmpty() }.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = scheme.onSurfaceVariant,
                    )
                }
                if (exit.isEmpty() && error != null) Text(error, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
        }
        if (exit.isNotEmpty() && !host.first().isDigit() && ':' !in host) {
            Spacer(Modifier.height(10.dp))
            AnimatedContent(added, label = "ruleAdded") { done ->
                if (done != null) {
                    Notice(Icons.Rounded.CheckCircle, stringResource(Res.string.site_rule_added))
                } else {
                    val vpnLabel = stringResource(Res.string.site_force_vpn)
                    val directLabel = stringResource(Res.string.site_force_direct)
                    fun add(action: RuleAction) {
                        platform.haptic(Haptic.Toggle)
                        controller.addSiteRule(host, action)
                        added = action
                    }
                    if (direct || blocked) {
                        DialogButtons(directLabel, vpnLabel, { add(RuleAction.Direct) }, { add(RuleAction.Proxy) })
                    } else {
                        DialogButtons(vpnLabel, directLabel, { add(RuleAction.Proxy) }, { add(RuleAction.Direct) })
                    }
                }
            }
        }
    }
}

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

@Composable
fun ConnectionWhySheet(controller: AppController, host: String, exit: String, rule: String, payload: String, onClose: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose, containerColor = MaterialTheme.colorScheme.surfaceContainerLow, dragHandle = null) {
        SheetHandle()
        Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp).navigationBarsPadding()) {
            RouteVerdict(controller, host, exit, rule, payload, controller.ruleSource(rule, payload), null)
        }
    }
}

@Composable
fun DnsLogScreen(controller: AppController, onBack: () -> Unit) {
    DisposableEffect(Unit) {
        controller.watchDns(true)
        onDispose { controller.watchDns(false) }
    }
    val entries by controller.dns.collectAsState()
    val tunnel by controller.tunnel.collectAsState()
    var query by remember { mutableStateOf("") }
    val shown = remember(entries, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) entries else entries.filter { q in it.domain.lowercase() || it.answers.any { a -> q in a } }
    }
    SubScreen(
        stringResource(Res.string.dns_title),
        onBack,
        collapsing = false,
        actions = {
            IconButton(onClick = controller::clearDns, enabled = entries.isNotEmpty()) {
                Icon(Icons.Rounded.ClearAll, stringResource(Res.string.dns_clear))
            }
        },
        belowBar = {
            AppTextField(query, { query = it }, stringResource(Res.string.dns_search), modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (shown.isEmpty()) {
                item(key = "empty") {
                    Column(Modifier.animateItem().fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Rounded.Dns, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline)
                        Text(
                            stringResource(if (tunnel == TunnelState.On) Res.string.dns_empty else Res.string.dns_off),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            items(shown.size, key = { shown[it].id }) { index ->
                Box(Modifier.animateItem()) { DnsRow(shown[index], positionShape(index, shown.lastIndex)) }
            }
        }
    }
}

@Composable
private fun DnsRow(entry: DnsEntry, shape: Shape) {
    val scheme = MaterialTheme.colorScheme
    Surface(shape = shape, color = scheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(40.dp).background(if (entry.failed) scheme.errorContainer else scheme.surfaceContainerHighest, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(entry.type.ifEmpty { "?" }, style = MaterialTheme.typography.labelMedium, color = if (entry.failed) scheme.onErrorContainer else scheme.onSurfaceVariant, maxLines = 1)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(entry.domain, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (entry.failed) stringResource(Res.string.dns_failed) else entry.answers.ifEmpty { listOf("—") }.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (entry.failed) scheme.error else scheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(
                        if (entry.cached) stringResource(Res.string.dns_cached) else entry.upstream.ifEmpty { null },
                        if (!entry.cached) "${entry.delay} ms" else null,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

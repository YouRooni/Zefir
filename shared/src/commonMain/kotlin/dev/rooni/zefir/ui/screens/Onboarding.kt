package dev.rooni.zefir.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.rooni.zefir.app.AppController
import dev.rooni.zefir.app.ImportError
import dev.rooni.zefir.model.*
import dev.rooni.zefir.platform.Haptic
import dev.rooni.zefir.res.*
import dev.rooni.zefir.ui.*
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private enum class Step { Welcome, How, Add, Region, Done }

@Composable
fun OnboardingScreen(controller: AppController, finish: (Offset) -> Unit) {
    val region = remember { controller.region }
    val steps = remember { Step.entries.filter { it != Step.Region || region != null } }
    var index by rememberSaveable { mutableStateOf(0) }
    val next = { if (index < steps.lastIndex) index++ }
    val progress by animateFloatAsState((index + 1f) / steps.size, spring(1f, 200f), label = "progress")

    LaunchedEffect(Unit) { controller.loadApps() }

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        LinearWavyProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
        )

        AnimatedContent(
            targetState = steps[index],
            modifier = Modifier.weight(1f).fillMaxWidth(),
            transitionSpec = {
                val forward = targetState.ordinal > initialState.ordinal
                val dir = if (forward) 1 else -1
                (slideInHorizontally(spring(1f, 300f)) { it * dir } + fadeIn(tween(200))) togetherWith
                    (slideOutHorizontally(spring(1f, 300f)) { -it * dir } + fadeOut(tween(200))) using SizeTransform(clip = false)
            },
            label = "step",
        ) { step ->
            when (step) {
                Step.Welcome -> HeroPage(ZefirMark, Res.string.ob_welcome_title, Res.string.ob_welcome_text) { _ ->
                    WideButton(stringResource(Res.string.next), next)
                }
                Step.How -> How(next)
                Step.Add -> AddStep(controller, next)
                Step.Region -> RegionStep(controller, region!!, next)
                Step.Done -> HeroPage(Icons.Rounded.Check, Res.string.ob_done_title, Res.string.ob_done_text) { position ->
                    WideButton(stringResource(Res.string.ob_done_action), {
                        controller.platform.haptic(Haptic.Press)
                        finish(position())
                    })
                }
            }
        }
    }
}

@Composable
private fun Page(
    aside: (@Composable () -> Unit)? = null,
    actions: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 24.dp).padding(bottom = 16.dp), contentAlignment = Alignment.Center) {
        val twoPanes = maxWidth > maxHeight && maxWidth >= 560.dp
        if (twoPanes) {
            Row(Modifier.widthIn(max = 1000.dp).fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
                if (aside != null) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { aside() }
                }
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
                    content()
                    Spacer(Modifier.height(8.dp))
                    actions()
                }
            }
        } else {
            Column(Modifier.widthIn(max = 560.dp).fillMaxSize()) {
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (aside != null) {
                        Spacer(Modifier.height(8.dp))
                        aside()
                        Spacer(Modifier.height(12.dp))
                    }
                    content()
                }
                Spacer(Modifier.height(12.dp))
                actions()
            }
        }
    }
}

@Composable
private fun Hero(icon: ImageVector, size: androidx.compose.ui.unit.Dp = 168.dp) {
    val spin = rememberInfiniteTransition(label = "hero")
    val turn by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(24000, easing = LinearEasing)), label = "turn")
    val shape = remember { PolygonShape(MaterialShapes.Cookie9Sided) }
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size)) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = shape,
            modifier = Modifier.fillMaxSize().graphicsLayer { rotationZ = turn },
        ) {}
        Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(size * 0.4f))
    }
}

@Composable
private fun HeroPage(icon: ImageVector, title: StringResource, text: StringResource, action: @Composable (() -> Offset) -> Unit) {
    var center by remember { mutableStateOf(Offset.Zero) }
    Page(
        aside = { Hero(icon) },
        actions = {
            Box(Modifier.onGloballyPositioned { center = it.boundsInRoot().center }) { action { center } }
        },
    ) {
        Text(
            stringResource(title),
            style = MaterialTheme.typography.displaySmallEmphasized,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(text),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun WideButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(60.dp),
        shapes = ButtonDefaults.shapes(),
    ) { ButtonText(text, style = MaterialTheme.typography.titleMedium) }
}

@Composable
private fun How(next: () -> Unit) {
    Page(actions = { WideButton(stringResource(Res.string.next), next) }) {
        Text(
            stringResource(Res.string.ob_how_title),
            style = MaterialTheme.typography.displaySmallEmphasized,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        )
        SectionGroup(
            rows = listOf(
                { HowRow(it, Icons.Rounded.Link, Res.string.ob_how_1_title, Res.string.ob_how_1_text) },
                { HowRow(it, Icons.Rounded.PowerSettingsNew, Res.string.ob_how_2_title, Res.string.ob_how_2_text) },
                { HowRow(it, Icons.Rounded.Tune, Res.string.ob_how_3_title, Res.string.ob_how_3_text) },
            ),
        )
    }
}

@Composable
private fun HowRow(shape: Shape, icon: ImageVector, title: StringResource, text: StringResource) {
    SettingRow(shape, stringResource(title), stringResource(text), icon)
}

@Composable
private fun AddStep(controller: AppController, next: () -> Unit) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<ImportError?>(null) }
    var noLink by remember { mutableStateOf(false) }
    var manual by remember { mutableStateOf(false) }
    var field by remember { mutableStateOf("") }
    val platform = controller.platform

    fun submit(text: String) {
        loading = true
        error = null
        noLink = false
        scope.launch {
            val result = controller.importSubscription(text)

            if (result == null) {
                platform.haptic(Haptic.Toggle)
                next()
            } else {
                platform.haptic(Haptic.Failed)
                error = result
                loading = false
            }
        }
    }

    Page(
        aside = { Hero(Icons.Rounded.Link, 120.dp) },
        actions = {
            AnimatedContent(loading, label = "addActions", transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) using SizeTransform(clip = false) }) { busy ->
                if (busy) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ContainedLoadingIndicator(Modifier.size(64.dp))
                        Text(stringResource(Res.string.add_loading))
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        AnimatedContent(manual, label = "primary") { typed ->
                            if (typed) {
                                WideButton(stringResource(Res.string.add_action), { submit(field) }, field.isNotBlank())
                            } else {
                                WideButton(stringResource(Res.string.add_paste), {
                                    val text = platform.readClipboard()?.trim().orEmpty()
                                    if (text.contains("://") || text.contains("proxies:")) {
                                        submit(text)
                                    } else {
                                        platform.haptic(Haptic.Failed)
                                        noLink = true
                                    }
                                })
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(
                                onClick = {
                                    manual = !manual
                                    noLink = false
                                },
                                modifier = Modifier.weight(1f).height(52.dp),
                                shape = RoundedCornerShape(26.dp, 6.dp, 6.dp, 6.dp),
                            ) { ButtonText(stringResource(if (manual) Res.string.add_paste else Res.string.add_manual)) }
                            OutlinedButton(
                                onClick = { scope.launch { platform.pickFile()?.let(::submit) } },
                                modifier = Modifier.weight(1f).height(52.dp),
                                shape = RoundedCornerShape(6.dp, 26.dp, 6.dp, 6.dp),
                            ) { ButtonText(stringResource(Res.string.add_file)) }
                        }
                        OutlinedButton(
                            onClick = next,
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape = RoundedCornerShape(6.dp, 6.dp, 26.dp, 26.dp),
                        ) { ButtonText(stringResource(Res.string.ob_no_sub)) }
                    }
                }
            }
        },
    ) {
        Text(
            stringResource(Res.string.ob_add_title),
            style = MaterialTheme.typography.displaySmallEmphasized,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(Res.string.ob_add_text),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
        GapColumn(Modifier.fillMaxWidth()) {
            AnimatedVisibility(manual && !loading, enter = RevealEnter, exit = RevealExit) {
                AppTextField(field, { field = it }, stringResource(Res.string.add_field), maxLines = 4, hint = stringResource(Res.string.add_hint))
            }
            MessageBanner(if (noLink) stringResource(Res.string.add_clipboard_empty) else error?.let { stringResource(it.label()) })
        }
    }
}

fun ImportError.label(): StringResource = when (this) {
    ImportError.Empty -> Res.string.add_err_empty
    ImportError.Network -> Res.string.add_err_network
    ImportError.Unsupported -> Res.string.add_err_unsupported
    ImportError.NoServers -> Res.string.add_err_no_servers
    ImportError.Blocked -> Res.string.add_err_blocked
    ImportError.TooLarge -> Res.string.add_err_too_large
}

@Composable
private fun RegionStep(controller: AppController, region: RegionPack, next: () -> Unit) {
    var chosen by remember { mutableStateOf(region.packs.map { it.id }.toSet()) }
    var expanded by remember { mutableStateOf(false) }
    val perApp = controller.platform.hasPerAppRouting

    Page(
        aside = { Hero(Icons.Rounded.AccountBalance, 120.dp) },
        actions = {
            DialogButtons(
                stringResource(Res.string.no_thanks),
                stringResource(Res.string.yes_please),
                onSecondary = next,
                onPrimary = {
                    controller.platform.haptic(Haptic.Toggle)
                    controller.applyRegionPacks(region.packs.filter { it.id in chosen })
                    next()
                },
            )
        },
    ) {
        Text(
            stringResource(Res.string.ob_region_title),
            style = MaterialTheme.typography.headlineLargeEmphasized,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(Res.string.ob_region_text),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
        if (perApp) {
            val turn by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
            SectionGroup(
                modifier = Modifier.padding(top = 8.dp),
                rows = listOf({ shape ->
                    SettingRow(shape, stringResource(Res.string.ob_region_more), null, Icons.Rounded.Checklist, onClick = { expanded = !expanded }) {
                        Icon(Icons.Rounded.ExpandMore, null, Modifier.graphicsLayer { rotationZ = turn })
                    }
                }),
            )
            AnimatedVisibility(expanded, enter = RevealEnter, exit = RevealExit) {
                SectionGroup(
                    rows = region.packs.map { pack ->
                        { shape: Shape ->
                            SwitchRow(
                                shape, stringResource(pack.title), null,
                                pack.id in chosen,
                                onChange = { on -> chosen = if (on) chosen + pack.id else chosen - pack.id },
                            )
                        }
                    },
                )
            }
        }
        Text(
            stringResource(Res.string.ob_region_note),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

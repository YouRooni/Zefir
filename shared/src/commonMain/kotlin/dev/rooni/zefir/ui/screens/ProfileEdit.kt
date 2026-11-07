package dev.rooni.zefir.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.rooni.zefir.app.AppController
import dev.rooni.zefir.app.SubscriptionClient
import dev.rooni.zefir.platform.Haptic
import dev.rooni.zefir.res.*
import dev.rooni.zefir.ui.*
import io.github.alexzhirkevich.qrose.rememberQrCodePainter
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

@Composable
private fun clientName(client: SubscriptionClient) = when (client) {
    SubscriptionClient.Auto -> stringResource(Res.string.sub_client_auto)
    SubscriptionClient.Plain -> stringResource(Res.string.sub_client_off)
    SubscriptionClient.Custom -> stringResource(Res.string.sub_client_custom)
    else -> client.label
}

@Composable
private fun ClientSheet(controller: AppController, id: String, current: String, custom: String?, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var customOpen by remember { mutableStateOf(current == SubscriptionClient.Custom.id) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun close() {
        scope.launch {
            sheet.hide()
            onClose()
        }
    }

    fun apply(client: String, userAgent: String?) {
        checking = client
        failure = null
        scope.launch {
            val error = controller.setProfileClient(id, client, userAgent)
            checking = null
            if (error == null) {
                controller.platform.haptic(Haptic.Toggle)
                close()
            } else {
                controller.platform.haptic(Haptic.Failed)

                failure = getString(error.label())
            }
        }
    }

    val list = rememberScrollState()
    AppSheet(stringResource(Res.string.sub_client), onClose, { list.canScrollBackward }, sheet, stringResource(Res.string.sub_client_desc)) {
        GapColumn(Modifier.fillMaxWidth().verticalScroll(list).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp).navigationBarsPadding()) {
            MessageBanner(failure)
            SectionGroup(
                rows = SubscriptionClient.entries.map { option ->
                    { shape: Shape ->
                        val selected = option.id == current
                        SettingRow(
                            shape,
                            clientName(option),
                            color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                            enabled = checking == null,
                            onClick = {
                                if (option == SubscriptionClient.Custom) {
                                    customOpen = true
                                } else {
                                    customOpen = false
                                    if (selected) close() else apply(option.id, null)
                                }
                            },
                        ) {
                            AnimatedContent(checking == option.id, label = "clientState") { busy ->
                                Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                                    when {
                                        busy -> LoadingIndicator(Modifier.size(32.dp))
                                        selected -> Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        }
                    }
                },
            )
            AnimatedVisibility(customOpen, enter = RevealEnter, exit = RevealExit) {
                SavedField(stringResource(Res.string.sub_client_ua), stringResource(Res.string.sub_client_ua_hint), custom.orEmpty()) { value ->
                    apply(SubscriptionClient.Custom.id, value)
                }
            }
        }
    }
}

@Composable
fun MergeProfilesScreen(controller: AppController, id: String?, onBack: () -> Unit) {
    val profiles by controller.profiles.collectAsState()
    val current = profiles.firstOrNull { it.id == id }
    val scope = rememberCoroutineScope()
    val platform = controller.platform
    var name by remember(id) { mutableStateOf(current?.name.orEmpty()) }
    var chosen by remember(id) { mutableStateOf(current?.members?.toSet().orEmpty()) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    SubScreen(stringResource(Res.string.merge_title), onBack, collapsing = false) { padding ->
        val scroll = rememberScrollState()
        val dividerAlpha by androidx.compose.animation.core.animateFloatAsState(if (scroll.canScrollForward) 1f else 0f, label = "mergeDivider")
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            GapColumn(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(16.dp)) {
                Text(stringResource(Res.string.merge_desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                AppTextField(name, { name = it }, stringResource(Res.string.edit_name))
                SectionGroup(
                    rows = profiles.filter { it.members.isEmpty() }.map { profile ->
                        { shape: Shape ->
                            val checked = profile.id in chosen
                            SettingRow(
                                shape,
                                profile.name,
                                stringResource(Res.string.servers_count, profile.servers),
                                onClick = { chosen = if (checked) chosen - profile.id else chosen + profile.id },
                            ) {
                                Checkbox(checked, onCheckedChange = null)
                            }
                        }
                    },
                )
                MessageBanner(error)
            }
            HorizontalDivider(Modifier.graphicsLayer { alpha = dividerAlpha })
            AnimatedContent(saving, Modifier.navigationBarsPadding().padding(16.dp), label = "mergeSave") { busy ->
                if (busy) {
                    Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
                } else {
                    Button(
                        onClick = {
                            saving = true
                            error = null
                            scope.launch {
                                val failure = controller.saveMerged(id, name, profiles.map { it.id }.filter { it in chosen })
                                saving = false
                                if (failure == null) {
                                    platform.haptic(Haptic.Toggle)
                                    onBack()
                                } else {
                                    platform.haptic(Haptic.Failed)
                                    error = failure
                                }
                            }
                        },
                        enabled = name.isNotBlank() && chosen.size >= 2,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shapes = ButtonDefaults.shapes(),
                    ) { ButtonText(stringResource(if (current == null) Res.string.merge_action else Res.string.save)) }
                }
            }
        }
    }
}

@Composable
fun EditProfileScreen(controller: AppController, id: String, onBack: () -> Unit) {
    val profiles by controller.profiles.collectAsState()
    val settings by controller.settings.collectAsState()
    val profile = profiles.firstOrNull { it.id == id } ?: return
    val scope = rememberCoroutineScope()
    val platform = controller.platform
    var name by remember(id) { mutableStateOf(profile.name) }
    var url by remember(id) { mutableStateOf(profile.url.orEmpty()) }
    var config by remember(id) { mutableStateOf<String?>(null) }
    var showQr by remember { mutableStateOf(false) }
    var showClients by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    SubScreen(
        stringResource(Res.string.edit_title),
        onBack,
        collapsing = false,
        actions = {
            if (profile.url != null) {
                IconButton(onClick = { showQr = true }) { Icon(Icons.Rounded.QrCode2, stringResource(Res.string.qr_show)) }
            }
        },
    ) { padding ->
        GapColumn(Modifier.padding(padding).fillMaxSize().imePadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
            AppTextField(name, { name = it }, stringResource(Res.string.edit_name))
            if (profile.url != null) {
                AppTextField(url, { url = it }, stringResource(Res.string.edit_url), maxLines = 4)
            }
            if (profile.url != null) {
                NavRow(
                    RoundedCornerShape(24.dp),
                    stringResource(Res.string.sub_client),
                    null,
                    Icons.Rounded.TheaterComedy,
                    clientName(SubscriptionClient.of(profile.client)),
                ) { showClients = true }
            }
            profile.routing?.let { routing ->
                SwitchRow(
                    RoundedCornerShape(24.dp),
                    stringResource(Res.string.sub_routing),
                    stringResource(Res.string.sub_routing_desc, routing.size),
                    profile.routingEnabled,
                    Icons.Rounded.AltRoute,
                ) { on -> controller.setProfileRouting(id, on) }
            }
            AnimatedVisibility(settings.showAdvanced, enter = RevealEnter, exit = RevealExit) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AnimatedContent(config == null, label = "configOpen") { closed ->
                        if (closed) {
                            OutlinedButton(
                                onClick = { config = controller.readConfig(id) },
                                modifier = Modifier.fillMaxWidth().height(52.dp),
                                shapes = ButtonDefaults.shapes(),
                            ) { ButtonText(stringResource(Res.string.edit_config_open)) }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(Res.string.edit_config_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                AppTextField(
                                    config.orEmpty(), { config = it },
                                    stringResource(Res.string.edit_config),
                                    maxLines = Int.MAX_VALUE,
                                    modifier = Modifier.heightIn(min = 200.dp, max = 420.dp),
                                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                                )
                            }
                        }
                    }
                }
            }
            MessageBanner(error)
            AnimatedContent(saving, label = "editSave") { busy ->
                if (busy) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { LoadingIndicator() }
                } else {
                    Button(
                        onClick = {
                            saving = true
                            error = null
                            scope.launch {
                                val failure = controller.saveProfile(id, name, url.takeIf { profile.url != null }, config)
                                saving = false
                                if (failure == null) {
                                    platform.haptic(Haptic.Toggle)
                                    onBack()
                                } else {
                                    platform.haptic(Haptic.Failed)
                                    error = failure
                                }
                            }
                        },
                        enabled = name.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shapes = ButtonDefaults.shapes(),
                    ) { ButtonText(stringResource(Res.string.save)) }
                }
            }
        }
    }

    if (showClients) {
        ClientSheet(controller, id, profile.client, profile.customClient) { showClients = false }
    }

    if (showQr) {
        ModalBottomSheet(onDismissRequest = { showQr = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, dragHandle = null) {
            SheetHandle()
            Column(
                Modifier.fillMaxWidth().padding(24.dp).navigationBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(stringResource(Res.string.qr_title), style = MaterialTheme.typography.titleLargeEmphasized)

                Box(Modifier.size(260.dp).clip(RoundedCornerShape(24.dp)).background(Color.White).padding(16.dp)) {
                    Image(rememberQrCodePainter(profile.url.orEmpty()), null, Modifier.fillMaxSize())
                }
                Text(stringResource(Res.string.qr_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

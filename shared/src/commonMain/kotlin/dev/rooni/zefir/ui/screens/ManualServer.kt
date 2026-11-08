package dev.rooni.zefir.ui.screens

import androidx.compose.foundation.lazy.LazyColumn
import dev.rooni.zefir.app.transports
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import dev.rooni.zefir.app.AppController
import dev.rooni.zefir.app.FINGERPRINTS
import dev.rooni.zefir.app.ImportError
import dev.rooni.zefir.app.ManualField
import dev.rooni.zefir.app.ManualSecurity
import dev.rooni.zefir.app.ManualServer
import dev.rooni.zefir.app.ManualTransport
import dev.rooni.zefir.app.ManualType
import dev.rooni.zefir.app.SS_CIPHERS
import dev.rooni.zefir.platform.Haptic
import dev.rooni.zefir.res.*
import dev.rooni.zefir.ui.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private enum class ManualMode { Form, Paste }

private val ProtocolSections = listOf(
    Res.string.manual_group_popular to listOf(ManualType.Vless, ManualType.Trojan, ManualType.Shadowsocks, ManualType.Hysteria2),
    Res.string.manual_group_wg to listOf(ManualType.WireGuard, ManualType.Amnezia),
    Res.string.manual_group_more to listOf(ManualType.Vmess, ManualType.Tuic, ManualType.AnyTls, ManualType.Mieru),
    Res.string.manual_group_proxy to listOf(ManualType.Socks, ManualType.Http, ManualType.Ssh),
)

private val ManualType.icon: androidx.compose.ui.graphics.vector.ImageVector
    get() = when (this) {
        ManualType.Socks -> Icons.Rounded.SettingsEthernet
        ManualType.Http -> Icons.Rounded.Public
        ManualType.Shadowsocks -> Icons.Rounded.VisibilityOff
        ManualType.Vmess -> Icons.Rounded.Hub
        ManualType.Trojan -> Icons.Rounded.Security
        ManualType.Vless -> Icons.Rounded.Bolt
        ManualType.Hysteria2 -> Icons.Rounded.Speed
        ManualType.Tuic -> Icons.Rounded.RocketLaunch
        ManualType.WireGuard -> Icons.Rounded.VpnLock
        ManualType.Amnezia -> Icons.Rounded.Masks
        ManualType.AnyTls -> Icons.Rounded.Lock
        ManualType.Mieru -> Icons.Rounded.Waves
        ManualType.Ssh -> Icons.Rounded.Terminal
    }

@Composable
private fun ProtocolPicker(type: ManualType, onPick: (ManualType) -> Unit) {
    val platform = LocalPlatform.current
    var open by remember { mutableStateOf(false) }
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp)
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().clip(shape).clickable {
            platform.haptic(Haptic.Tick)
            open = true
        },
    ) {
        Row(Modifier.padding(start = 12.dp, end = 16.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            AnimatedContent(
                type,
                label = "protocolIcon",
                transitionSpec = { fadeIn(tween(200, 60)) togetherWith fadeOut(tween(100)) },
            ) { current ->
                Box(
                    Modifier.size(56.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(current.icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            AnimatedContent(
                type,
                Modifier.weight(1f).padding(horizontal = 16.dp),
                label = "protocolText",
                transitionSpec = {
                    fadeIn(tween(200, 60)) togetherWith fadeOut(tween(100)) using SizeTransform(clip = false) { _, _ -> spring(dampingRatio = 1f, stiffness = 500f) }
                },
            ) { current ->
                Column {
                    Text(stringResource(Res.string.manual_protocol), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(current.label, style = MaterialTheme.typography.titleLargeEmphasized)
                }
            }
            Icon(Icons.Rounded.UnfoldMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (open) {
        ModalBottomSheet(onDismissRequest = { open = false }, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, dragHandle = null) {
            SheetHandle()
            LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
                ProtocolSections.forEach { (title, options) ->
                    item(key = "t-" + title.key) { GroupTitle(stringResource(title)) }
                    items(options.size, key = { options[it].name }) { index ->
                        val option = options[index]
                        val picked = option == type
                        Box(Modifier.padding(top = if (index > 0) 2.dp else 0.dp)) {
                            SettingRow(
                                positionShape(index, options.lastIndex),
                                option.label,
                                stringResource(option.short),
                                option.icon,
                                color = if (picked) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                                onClick = {
                                    platform.haptic(Haptic.Tick)
                                    onPick(option)
                                    open = false
                                },
                            ) {
                                if (picked) Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }
}

private val ManualType.short: StringResource
    get() = when (this) {
        ManualType.Vless -> Res.string.manual_short_vless
        ManualType.Trojan -> Res.string.manual_short_trojan
        ManualType.Shadowsocks -> Res.string.manual_short_ss
        ManualType.Hysteria2 -> Res.string.manual_short_hy2
        ManualType.WireGuard -> Res.string.manual_short_wg
        ManualType.Amnezia -> Res.string.manual_short_awg
        ManualType.Vmess -> Res.string.manual_short_vmess
        ManualType.Tuic -> Res.string.manual_short_tuic
        ManualType.AnyTls -> Res.string.manual_short_anytls
        ManualType.Mieru -> Res.string.manual_short_mieru
        ManualType.Socks -> Res.string.manual_short_socks
        ManualType.Http -> Res.string.manual_short_http
        ManualType.Ssh -> Res.string.manual_short_ssh
    }

@Composable
fun ManualServerScreen(controller: AppController, editing: String?, onBack: () -> Unit, onSaved: () -> Unit) {
    val platform = controller.platform
    val scope = rememberCoroutineScope()
    val initial = remember(editing) {
        editing?.let { name ->
            controller.profiles.value.firstOrNull { it.manual }?.let { profile ->
                controller.manualServers(profile.id).firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull == name }?.let(ManualServer::from)
            }
        } ?: ManualServer()
    }
    var mode by remember { mutableStateOf(ManualMode.Form) }
    var server by remember(initial) { mutableStateOf(initial) }
    var pasted by remember { mutableStateOf("") }
    var tried by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<ImportError?>(null) }

    fun finish(error: ImportError?) {
        saving = false
        if (error == null) {
            platform.haptic(Haptic.Toggle)
            onSaved()
        } else {
            platform.haptic(Haptic.Failed)
            failure = error
        }
    }

    fun save() {
        failure = null
        if (mode == ManualMode.Paste) {
            if (pasted.isBlank()) {
                platform.haptic(Haptic.Failed)
                failure = ImportError.Empty
                return
            }
            saving = true
            scope.launch { finish(controller.addManualServers(pasted)) }
            return
        }
        tried = true
        if (server.missing != null) {
            platform.haptic(Haptic.Failed)
            return
        }
        saving = true
        scope.launch { finish(controller.saveManualServer(server, editing)) }
    }

    val scroll = rememberScrollState()
    val divider by animateFloatAsState(if (scroll.canScrollForward) 1f else 0f, tween(160), label = "manualDivider")

    SubScreen(stringResource(if (editing == null) Res.string.manual_title else Res.string.manual_edit_title), onBack) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            ScrollColumn(Modifier.weight(1f), scroll) {
                if (editing == null) {
                    ChoiceButtons(
                        listOf(ManualMode.Form to stringResource(Res.string.manual_mode_form), ManualMode.Paste to stringResource(Res.string.manual_mode_paste)),
                        mode,
                    ) {
                        mode = it
                        failure = null
                    }
                }
                AnimatedContent(
                    mode,
                    label = "manualMode",
                    transitionSpec = {
                        fadeIn(tween(220, 90)) togetherWith fadeOut(tween(120)) using SizeTransform(clip = false) { _, _ -> spring(dampingRatio = 1f, stiffness = 420f) }
                    },
                ) { current ->
                    if (current == ManualMode.Paste) {
                        PasteForm(controller, pasted, failure) {
                            pasted = it
                            failure = null
                        }
                    } else {
                        ServerForm(server, tried, failure) { server = it }
                    }
                }
            }
            HorizontalDivider(Modifier.graphicsLayer { alpha = divider })
            Box(Modifier.navigationBarsPadding().padding(16.dp)) {
                AnimatedContent(saving, label = "manualSave", transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) }) { busy ->
                    if (busy) {
                        Box(Modifier.fillMaxWidth().height(64.dp), contentAlignment = Alignment.Center) { ContainedLoadingIndicator(Modifier.size(56.dp)) }
                    } else {
                        Button(
                            onClick = { save() },
                            modifier = Modifier.fillMaxWidth().height(64.dp),
                            shapes = ButtonDefaults.shapes(),
                        ) {
                            Icon(if (editing == null) Icons.Rounded.Add else Icons.Rounded.Check, null)
                            Spacer(Modifier.width(10.dp))
                            ButtonText(stringResource(if (editing == null) Res.string.manual_add else Res.string.save), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PasteForm(controller: AppController, text: String, failure: ImportError?, onChange: (String) -> Unit) {
    val platform = controller.platform
    GapColumn {
        Hint(stringResource(Res.string.manual_paste_hint))
        AppTextField(
            text, onChange, stringResource(Res.string.manual_paste_field), maxLines = 12,
            trailing = {
                IconButton(onClick = {
                    platform.haptic(Haptic.Tick)
                    if (text.isEmpty()) platform.readClipboard()?.trim()?.let(onChange) else onChange("")
                }) { Icon(if (text.isEmpty()) Icons.Rounded.ContentPaste else Icons.Rounded.Close, null) }
            },
        )
        MessageBanner(failure?.let { stringResource(it.label()) })
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

private val ManualType.hint: StringResource
    get() = when (this) {
        ManualType.Socks -> Res.string.manual_socks_hint
        ManualType.Http -> Res.string.manual_http_hint
        ManualType.Shadowsocks -> Res.string.manual_ss_hint
        ManualType.Vless -> Res.string.manual_vless_hint
        ManualType.Vmess -> Res.string.manual_vmess_hint
        ManualType.Trojan -> Res.string.manual_trojan_hint
        ManualType.Hysteria2 -> Res.string.manual_hy2_hint
        ManualType.Tuic -> Res.string.manual_tuic_hint
        ManualType.WireGuard -> Res.string.manual_wg_hint
        ManualType.Amnezia -> Res.string.manual_awg_hint
        ManualType.AnyTls -> Res.string.manual_anytls_hint
        ManualType.Mieru -> Res.string.manual_mieru_hint
        ManualType.Ssh -> Res.string.manual_ssh_hint
    }

@Composable
private fun FormSection(title: String, rows: List<GroupRow>) {
    AnimatedVisibility(rows.any { it.visible }, enter = RevealEnter, exit = RevealExit) {
        LiveSectionGroup(rows, title = title)
    }
}

@Composable
private fun ServerForm(server: ManualServer, tried: Boolean, failure: ImportError?, onChange: (ManualServer) -> Unit) {
    val type = server.type
    val optional = stringResource(Res.string.manual_optional)
    val missing = server.missing.takeIf { tried }
    GapColumn {
        GapColumn(gap = 8.dp) {
            ProtocolPicker(type) { onChange(server.withType(it)) }
            AnimatedContent(
                type,
                label = "protocolHint",
                transitionSpec = { fadeIn(tween(200, 60)) togetherWith fadeOut(tween(100)) using SizeTransform(clip = false) { _, _ -> spring(dampingRatio = 1f, stiffness = 500f) } },
            ) { Hint(stringResource(it.hint)) }
        }

        FormSection(
            stringResource(Res.string.manual_where),
            listOf(
                GroupRow("host") { shape ->
                    FieldRow(shape, Icons.Rounded.Dns) {
                        AppTextField(server.host, { onChange(server.copy(host = it.trim())) }, stringResource(Res.string.manual_host), hint = "1.2.3.4 / proxy.example.com")
                    }
                },
                GroupRow("port") { shape ->
                    FieldRow(shape, Icons.Rounded.Numbers) {
                        AppTextField(server.port, { value -> onChange(server.copy(port = value.filter(Char::isDigit).take(5))) }, stringResource(Res.string.manual_port), hint = when (type) {
                            ManualType.Http -> "8080"
                            ManualType.Ssh -> "22"
                            else -> "443"
                        })
                    }
                },
            ),
        )

        FormSection(
            stringResource(Res.string.manual_access),
            listOf(
                GroupRow("user", type.usesLogin) { shape ->
                    FieldRow(shape, Icons.Rounded.Person) {
                        AppTextField(server.user, { onChange(server.copy(user = it)) }, stringResource(Res.string.manual_user), hint = if (type.needsUser) null else optional)
                    }
                },
                GroupRow("uuid", type.usesUuid) { shape ->
                    FieldRow(shape, Icons.Rounded.Badge) {
                        AppTextField(server.uuid, { onChange(server.copy(uuid = it.trim())) }, stringResource(Res.string.manual_uuid))
                    }
                },
                GroupRow("cipher", type == ManualType.Shadowsocks) { shape ->
                    OptionRow(shape, stringResource(Res.string.manual_cipher), stringResource(Res.string.manual_cipher_desc), Icons.Rounded.Lock, SS_CIPHERS.map { it to it }, server.cipher) {
                        onChange(server.copy(cipher = it))
                    }
                },
                GroupRow("password", type.hasPassword) { shape ->
                    FieldRow(shape, Icons.Rounded.Key) {
                        AppTextField(server.password, { onChange(server.copy(password = it)) }, stringResource(Res.string.manual_password), hint = if (type.needsPassword) null else optional)
                    }
                },
                GroupRow("wgPrivate", type.isWireGuard) { shape ->
                    FieldRow(shape, Icons.Rounded.Key) {
                        AppTextField(server.privateKey, { onChange(server.copy(privateKey = it.trim())) }, stringResource(Res.string.manual_wg_private))
                    }
                },
                GroupRow("wgPeer", type.isWireGuard) { shape ->
                    FieldRow(shape, Icons.Rounded.VpnKey) {
                        AppTextField(server.peerKey, { onChange(server.copy(peerKey = it.trim())) }, stringResource(Res.string.manual_wg_peer))
                    }
                },
                GroupRow("wgPsk", type.isWireGuard) { shape ->
                    FieldRow(shape, Icons.Rounded.Password) {
                        AppTextField(server.presharedKey, { onChange(server.copy(presharedKey = it.trim())) }, stringResource(Res.string.manual_wg_psk), hint = optional)
                    }
                },
                GroupRow("wgAddress", type.isWireGuard) { shape ->
                    FieldRow(shape, Icons.Rounded.Lan) {
                        AppTextField(server.address, { onChange(server.copy(address = it)) }, stringResource(Res.string.manual_wg_address), hint = "10.0.0.2/32, fd00::2/128")
                    }
                },
            ),
        )

        FormSection(
            "AmneziaWG",
            listOf(
                GroupRow("awg", type == ManualType.Amnezia) { shape ->
                    FieldRow(shape, Icons.Rounded.Masks) {
                        AppTextField(
                            server.amnezia,
                            { onChange(server.copy(amnezia = it)) },
                            stringResource(Res.string.manual_awg_params),
                            maxLines = 12,
                            hint = stringResource(Res.string.manual_awg_params_hint),
                        )
                    }
                },
            ),
        )

        val security = server.effectiveSecurity
        val tlsShown = server.showsTlsFields
        val switchOnly = type.securities == listOf(ManualSecurity.None, ManualSecurity.Tls)
        FormSection(
            stringResource(Res.string.manual_security),
            listOf(
                GroupRow("tlsSwitch", switchOnly) { shape ->
                    SwitchRow(shape, stringResource(Res.string.manual_tls), stringResource(Res.string.manual_tls_desc), security == ManualSecurity.Tls, Icons.Rounded.Https) { on ->
                        onChange(server.copy(security = if (on) ManualSecurity.Tls else ManualSecurity.None))
                    }
                },
                GroupRow("securityPick", type.securities.size > 2 || (type.securities.size == 2 && !switchOnly)) { shape ->
                    OptionRow(
                        shape,
                        stringResource(Res.string.manual_security_row),
                        stringResource(Res.string.manual_cipher_desc),
                        Icons.Rounded.Https,
                        type.securities.map { it to it.title() },
                        security,
                    ) { onChange(server.copy(security = it)) }
                },
                GroupRow("sni", tlsShown) { shape ->
                    FieldRow(shape, Icons.Rounded.Language) {
                        AppTextField(server.sni, { onChange(server.copy(sni = it.trim())) }, stringResource(Res.string.manual_sni), hint = optional)
                    }
                },
                GroupRow("fingerprint", tlsShown && (type.hasTransport || type == ManualType.AnyTls)) { shape ->
                    OptionRow(shape, stringResource(Res.string.manual_fingerprint), stringResource(Res.string.manual_fingerprint_desc), Icons.Rounded.Fingerprint, FINGERPRINTS.map { it to it }, server.fingerprint) {
                        onChange(server.copy(fingerprint = it))
                    }
                },
                GroupRow("realityKey", server.showsReality) { shape ->
                    FieldRow(shape, Icons.Rounded.VpnKey) {
                        AppTextField(server.realityKey, { onChange(server.copy(realityKey = it.trim())) }, stringResource(Res.string.manual_reality_key))
                    }
                },
                GroupRow("shortId", server.showsReality) { shape ->
                    FieldRow(shape, Icons.Rounded.Tag) {
                        AppTextField(server.shortId, { onChange(server.copy(shortId = it.trim())) }, stringResource(Res.string.manual_short_id), hint = optional)
                    }
                },
                GroupRow("vision", type == ManualType.Vless && tlsShown && server.transport == ManualTransport.Tcp) { shape ->
                    SwitchRow(shape, stringResource(Res.string.manual_vision), stringResource(Res.string.manual_vision_desc), server.vision, Icons.Rounded.Visibility) { on ->
                        onChange(server.copy(vision = on))
                    }
                },
                GroupRow("obfs", type == ManualType.Hysteria2) { shape ->
                    FieldRow(shape, Icons.Rounded.Shuffle) {
                        AppTextField(server.obfsPassword, { onChange(server.copy(obfsPassword = it)) }, stringResource(Res.string.manual_obfs), hint = optional)
                    }
                },
                GroupRow("insecure", tlsShown && !server.showsReality) { shape ->
                    SwitchRow(shape, stringResource(Res.string.manual_insecure), stringResource(Res.string.manual_insecure_desc), server.insecure, Icons.Rounded.GppMaybe) { on ->
                        onChange(server.copy(insecure = on))
                    }
                },
            ),
        )

        val transport = server.transport
        FormSection(
            stringResource(Res.string.manual_transport),
            listOf(
                GroupRow("network", type.hasTransport) { shape ->
                    OptionRow(shape, stringResource(Res.string.manual_network), stringResource(Res.string.manual_cipher_desc), Icons.Rounded.SwapHoriz, type.transports().map { it to it.label }, transport) {
                        onChange(server.copy(transport = it))
                    }
                },
                GroupRow("path", type.hasTransport && (transport == ManualTransport.Ws || transport == ManualTransport.H2 || transport == ManualTransport.Xhttp)) { shape ->
                    FieldRow(shape, Icons.Rounded.Route) {
                        AppTextField(server.path, { onChange(server.copy(path = it.trim())) }, stringResource(Res.string.manual_path), hint = "/")
                    }
                },
                GroupRow("hostHeader", type.hasTransport && (transport == ManualTransport.Ws || transport == ManualTransport.H2 || transport == ManualTransport.Xhttp)) { shape ->
                    FieldRow(shape, Icons.Rounded.Language) {
                        AppTextField(server.hostHeader, { onChange(server.copy(hostHeader = it.trim())) }, stringResource(Res.string.manual_host_header), hint = optional)
                    }
                },
                GroupRow("service", type.hasTransport && transport == ManualTransport.Grpc) { shape ->
                    FieldRow(shape, Icons.AutoMirrored.Rounded.Label) {
                        AppTextField(server.service, { onChange(server.copy(service = it.trim())) }, stringResource(Res.string.manual_service))
                    }
                },
            ),
        )

        FormSection(
            stringResource(Res.string.manual_look),
            listOf(
                GroupRow("name") { shape ->
                    FieldRow(shape, Icons.AutoMirrored.Rounded.Label) {
                        AppTextField(server.name, { onChange(server.copy(name = it)) }, stringResource(Res.string.manual_name), hint = server.title)
                    }
                },
            ),
        )

        MessageBanner(
            when (missing) {
                ManualField.Host -> stringResource(Res.string.manual_need_host)
                ManualField.Port -> stringResource(Res.string.manual_need_port)
                ManualField.User -> stringResource(Res.string.manual_need_user)
                ManualField.Password -> stringResource(Res.string.manual_need_password)
                ManualField.Uuid -> stringResource(Res.string.manual_need_uuid)
                ManualField.Keys -> stringResource(Res.string.manual_need_keys)
                ManualField.Address -> stringResource(Res.string.manual_need_address)
                ManualField.RealityKey -> stringResource(Res.string.manual_need_reality)
                ManualField.Amnezia -> stringResource(Res.string.manual_need_awg)
                null -> failure?.let { stringResource(it.label()) }
            },
        )
    }
}

@Composable
private fun ManualSecurity.title(): String = when (this) {
    ManualSecurity.None -> stringResource(Res.string.manual_sec_none)
    ManualSecurity.Tls -> "TLS"
    ManualSecurity.Reality -> "Reality"
}

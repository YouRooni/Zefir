package dev.rooni.zefir.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.rooni.zefir.app.AppController
import dev.rooni.zefir.app.Automation
import dev.rooni.zefir.platform.Haptic
import dev.rooni.zefir.res.*
import dev.rooni.zefir.ui.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

// Экран плагина для Tasker и MacroDroid: выбрать команду и её параметры.
// Ключ плагину не нужен, достаточно включённой автоматизации.
@Composable
fun PluginEditor(
    controller: AppController,
    initialCommand: String?,
    initialArgs: Map<String, String>,
    onCancel: () -> Unit,
    onSave: (command: String, args: Map<String, String>) -> Unit,
) {
    val settings by controller.settings.collectAsState()
    CompositionLocalProvider(LocalPlatform provides controller.platform) {
        AppTheme(settings, controller.platform) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                PluginEditorContent(controller, initialCommand, initialArgs, onCancel, onSave)
            }
        }
    }
}

@Composable
private fun PluginEditorContent(
    controller: AppController,
    initialCommand: String?,
    initialArgs: Map<String, String>,
    onCancel: () -> Unit,
    onSave: (String, Map<String, String>) -> Unit,
) {
    val settings by controller.settings.collectAsState()
    val platform = controller.platform
    var command by remember { mutableStateOf(initialCommand?.takeIf { it in Automation.COMMANDS } ?: "toggle") }
    val values = remember { mutableStateMapOf<String, String>().apply { putAll(initialArgs) } }
    val fields = Automation.ARGS[command].orEmpty()
    // на закрытии блока держим старые поля, иначе он схлопывается без анимации
    var shownFields by remember { mutableStateOf(fields) }
    if (fields.isNotEmpty() && fields != shownFields) shownFields = fields

    SubScreen(stringResource(Res.string.plugin_title), onCancel) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                ScrollColumn {
                  Column {
                    Text(
                        stringResource(Res.string.plugin_how),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    AnimatedVisibility(!settings.automation, enter = RevealEnter, exit = RevealExit) {
                        SectionGroup(
                            rows = listOf { shape ->
                                SwitchRow(shape, stringResource(Res.string.automation), stringResource(Res.string.plugin_off), settings.automation, Icons.Rounded.SmartToy) { on ->
                                    controller.setAutomation(on)
                                }
                            },
                        )
                    }
                    AnimatedVisibility(fields.isNotEmpty(), enter = RevealEnter, exit = RevealExit) {
                        Column {
                            GroupTitle(stringResource(Res.string.plugin_args))
                            AnimatedContent(
                                shownFields,
                                transitionSpec = {
                                    (fadeIn(tween(180, delayMillis = 90)) togetherWith fadeOut(tween(90)))
                                        .using(SizeTransform(clip = false) { _, _ -> spring(1f, 380f) })
                                },
                                label = "pluginFields",
                            ) { list ->
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    list.forEach { name ->
                                        AppTextField(values[name].orEmpty(), { values[name] = it }, stringResource(argLabel(name)))
                                    }
                                }
                            }
                            Text(
                                stringResource(Res.string.plugin_vars_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                            )
                        }
                    }
                    SectionGroup(
                        title = stringResource(Res.string.plugin_command),
                        rows = Automation.COMMANDS.entries.filter { it.key != "help" }.map { (name, description) ->
                            { shape: androidx.compose.ui.graphics.Shape ->
                                val chosen = name == command
                                val color by androidx.compose.animation.animateColorAsState(
                                    if (chosen) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                                    tween(200),
                                    label = "pluginCommand",
                                )
                                SettingRow(
                                    shape,
                                    name,
                                    description,
                                    if (chosen) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                                    color = color,
                                    onClick = { command = name },
                                )
                            }
                        },
                    )
                  }
                }
            }
            Button(
                onClick = {
                    platform.haptic(Haptic.Toggle)
                    onSave(command, fields.associateWith { values[it].orEmpty().trim() }.filterValues { it.isNotEmpty() })
                },
                enabled = settings.automation,
                modifier = Modifier.fillMaxWidth().padding(16.dp).navigationBarsPadding().height(56.dp),
                shapes = ButtonDefaults.shapes(),
            ) { ButtonText(stringResource(Res.string.plugin_save)) }
        }
    }
}

private fun argLabel(name: String): StringResource = when (name) {
    "group" -> Res.string.arg_group
    "url" -> Res.string.arg_url
    "value" -> Res.string.arg_value
    "domain" -> Res.string.arg_domain
    "action" -> Res.string.arg_action
    "count" -> Res.string.arg_count
    "id" -> Res.string.arg_id
    "host" -> Res.string.arg_host
    else -> Res.string.arg_name
}

// короткая подпись настройки плагина в списке действий Tasker и MacroDroid
fun pluginBlurb(command: String, args: Map<String, String>) =
    if (args.isEmpty()) "Zefir: $command" else "Zefir: $command (" + args.entries.joinToString(", ") { "${it.key}=${it.value}" } + ")"

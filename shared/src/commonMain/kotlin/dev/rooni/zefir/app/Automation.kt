package dev.rooni.zefir.app

import dev.rooni.zefir.model.AppSettings
import dev.rooni.zefir.model.Profile
import dev.rooni.zefir.model.RuleAction
import dev.rooni.zefir.model.TunnelState
import dev.rooni.zefir.platform.nowMillis
import dev.rooni.zefir.ui.APP_CHANNEL
import dev.rooni.zefir.ui.APP_VERSION
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*

// Управление из Tasker, MacroDroid, adb и любых скриптов.
// По умолчанию выключено, каждая команда должна принести ключ из настроек.
class Automation(private val controller: AppController) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun authorized(key: String?): Boolean {
        val s = controller.settings.value
        if (!s.automation || s.automationKey.isEmpty() || key.isNullOrEmpty()) return false
        return java.security.MessageDigest.isEqual(key.toByteArray(), s.automationKey.toByteArray())
    }

    suspend fun handle(key: String?, command: String?, args: Map<String, String>): JsonObject {
        val name = command?.trim()?.lowercase().orEmpty()
        if (!controller.settings.value.automation) return failure(name, "automation is off")
        if (!authorized(key)) return failure(name, "wrong key")
        return run(name, args)
    }

    // Плагин Tasker/MacroDroid: без ключа, чтобы импортированные макросы работали сразу.
    // Хватает того, что автоматизацию включили руками в приложении.
    suspend fun handlePlugin(command: String?, args: Map<String, String>): JsonObject {
        val name = command?.trim()?.lowercase().orEmpty()
        if (!controller.settings.value.automation) return failure(name, "automation is off")
        return run(name, args)
    }

    // без проверки ключа: для тестов и для уже проверенных вызовов
    suspend fun run(command: String, args: Map<String, String>): JsonObject = try {
        controller.ready.first { it }
        val result = when (command) {
            "", "help" -> help()
            "status" -> status()
            "connect" -> tunnel(args) { controller.connect() }
            "disconnect" -> tunnel(args) { controller.disconnect() }
            "toggle" -> tunnel(args) { controller.toggle() }
            "reconnect" -> tunnel(args) { controller.reconnect() }
            "profiles" -> JsonArray(controller.profiles.value.map(::profileJson))
            "profile.activate" -> activate(args)
            "profile.add" -> addProfile(args)
            "profile.refresh" -> refresh(args)
            "profile.delete" -> deleteProfile(args)
            "servers" -> servers()
            "server.select" -> select(args)
            "ping" -> ping(args)
            "latency" -> buildJsonObject {
                put("direct", controller.pingDirect())
                put("current", controller.pingCurrent())
            }
            "settings.get" -> settingsGet(args)
            "settings.set" -> settingsSet(args)
            "rule.add" -> addRule(args)
            "logs" -> logs(args)
            "connections" -> JsonArray(controller.connections().map(::connectionJson))
            "connections.close" -> closeConnections(args)
            "exit_ip" -> controller.exitIp()?.let { buildJsonObject { put("ip", it.ip); put("country", it.country); put("city", it.city) } } ?: JsonNull
            "site" -> site(args)
            "services" -> JsonObject(controller.serviceCheck().associate { (name, value) -> name to JsonPrimitive(value) })
            "device" -> buildJsonObject {
                put("version", APP_VERSION)
                put("channel", APP_CHANNEL)
                put("info", controller.platform.deviceInfo())
            }
            else -> throw AutomationException("unknown command, try help")
        }
        success(command, result)
    } catch (e: AutomationException) {
        failure(command, e.message)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        failure(command, e.message ?: e::class.simpleName)
    }

    private fun success(command: String, result: JsonElement) = buildJsonObject {
        put("ok", true)
        put("command", command)
        put("result", result)
    }

    private fun failure(command: String, error: String?) = buildJsonObject {
        put("ok", false)
        put("command", command)
        put("error", error ?: "failed")
    }

    private fun help() = JsonObject(COMMANDS.mapValues { JsonPrimitive(it.value) })

    fun status(): JsonObject {
        val state = controller.tunnel.value
        val profile = controller.activeProfile
        val snapshot = controller.proxies.value
        return buildJsonObject {
            put("state", stateName(state))
            if (state is TunnelState.Failed) put("error", state.message)
            put("profile", profile?.id)
            put("profile_name", profile?.name)
            put("group", snapshot.main?.name)
            put("server", snapshot.resolve(snapshot.main?.name))
            put("mode", controller.settings.value.mode.name.lowercase())
            controller.connectedAt.value?.let { put("connected_ms", nowMillis() - it) }
            put("up_speed", controller.speed.value.up)
            put("down_speed", controller.speed.value.down)
            put("up_total", controller.total.value.up)
            put("down_total", controller.total.value.down)
        }
    }

    private suspend fun tunnel(args: Map<String, String>, action: () -> Unit): JsonObject {
        action()
        if (args.flag("wait", true)) {
            // даём состоянию сдвинуться, потом ждём устойчивое
            kotlinx.coroutines.delay(150)
            withTimeoutOrNull(WAIT) { controller.tunnel.first { it == TunnelState.On || it == TunnelState.Off || it is TunnelState.Failed } }
        }
        return status()
    }

    private fun findProfile(args: Map<String, String>, fallbackActive: Boolean = false): Profile {
        val list = controller.profiles.value
        args["id"]?.let { id -> return list.firstOrNull { it.id == id } ?: throw AutomationException("no profile with id $id") }
        args["name"]?.let { name -> return list.firstOrNull { it.name.equals(name, true) } ?: throw AutomationException("no profile named $name") }
        if (fallbackActive) controller.activeProfile?.let { return it }
        throw AutomationException("pass id or name")
    }

    private suspend fun activate(args: Map<String, String>): JsonObject {
        val profile = findProfile(args)
        controller.activate(profile.id)
        return profileJson(profile)
    }

    private suspend fun addProfile(args: Map<String, String>): JsonElement {
        val input = args["url"] ?: args["text"] ?: throw AutomationException("pass url or text")
        controller.importSubscription(input)?.let { throw AutomationException("import failed: ${it.name}") }
        return controller.activeProfile?.let(::profileJson) ?: JsonNull
    }

    private suspend fun refresh(args: Map<String, String>): JsonElement {
        val targets = if (args["id"] == "all" || args["name"] == "all") {
            controller.profiles.value.filter { it.url != null || it.members.isNotEmpty() }
        } else {
            listOf(findProfile(args, fallbackActive = true))
        }
        targets.forEach { controller.refreshProfile(it.id) }
        if (args.flag("wait", true)) {
            val ids = targets.map { it.id }.toSet()
            withTimeoutOrNull(WAIT) { controller.refreshing.first { running -> running.none { it in ids } } }
        }
        val fresh = controller.profiles.value.filter { profile -> targets.any { it.id == profile.id } }
        return JsonArray(fresh.map(::profileJson))
    }

    private fun deleteProfile(args: Map<String, String>): JsonObject {
        val profile = findProfile(args)
        controller.delete(profile.id)
        return profileJson(profile)
    }

    private fun servers(): JsonObject {
        val snapshot = controller.proxies.value
        val delays = controller.delays.value
        return buildJsonObject {
            put("group", snapshot.main?.name)
            put("current", snapshot.resolve(snapshot.main?.name))
            putJsonArray("groups") {
                snapshot.groups.forEach { group ->
                    addJsonObject {
                        put("name", group.name)
                        put("type", group.type)
                        put("now", group.now)
                        putJsonArray("members") { group.members.forEach { add(it) } }
                    }
                }
            }
            putJsonArray("servers") {
                snapshot.nodes.values.filter { it.name !in AppController.BUILT_IN }.forEach { node ->
                    addJsonObject {
                        put("name", node.name)
                        put("type", node.type)
                        put("delay", delays[node.name])
                    }
                }
            }
        }
    }

    private fun select(args: Map<String, String>): JsonObject {
        val name = args["name"] ?: throw AutomationException("pass name")
        val snapshot = controller.proxies.value
        val group = args["group"]?.let { wanted -> snapshot.groups.firstOrNull { it.name == wanted } ?: throw AutomationException("no group $wanted") }
            ?: snapshot.main ?: throw AutomationException("no servers loaded")
        if (name !in group.members) throw AutomationException("$name is not in ${group.name}")
        controller.selectProxy(group.name, name)
        return buildJsonObject {
            put("group", group.name)
            put("server", name)
        }
    }

    private suspend fun ping(args: Map<String, String>): JsonObject {
        val name = args["name"]
        if (name != null) {
            if (name !in controller.proxies.value.nodes) throw AutomationException("no server $name")
            if (name !in controller.testingServers.value) controller.testServer(name)
            withTimeoutOrNull(WAIT) { controller.testingServers.first { name !in it } }
            return buildJsonObject { put(name, controller.delays.value[name]) }
        }
        // общий прогон: ждём сколько можно, недомеренные просто не попадут в ответ
        withTimeoutOrNull(WAIT) {
            controller.runServerTests()
            controller.testing.first { !it }
        }
        return JsonObject(controller.delays.value.mapValues { JsonPrimitive(it.value) })
    }

    private fun settingsJson(): JsonObject {
        val all = json.encodeToJsonElement(AppSettings.serializer(), controller.settings.value).jsonObject
        return JsonObject(all - HIDDEN_SETTINGS)
    }

    private fun settingsGet(args: Map<String, String>): JsonElement {
        val all = settingsJson()
        val key = args["name"] ?: return all
        return all[key] ?: throw AutomationException("no setting $key")
    }

    private fun settingsSet(args: Map<String, String>): JsonObject {
        val key = args["name"] ?: throw AutomationException("pass name")
        val raw = args["value"] ?: throw AutomationException("pass value")
        if (key in LOCKED_SETTINGS) throw AutomationException("$key can only be changed in the app")
        val current = json.encodeToJsonElement(AppSettings.serializer(), controller.settings.value).jsonObject
        val old = current[key] ?: throw AutomationException("no setting $key")
        val parsed = runCatching { Json.parseToJsonElement(raw) }.getOrNull()
        // строковые настройки можно передавать без кавычек
        val value = if (old is JsonPrimitive && old.isString && (parsed == null || parsed !is JsonPrimitive || !parsed.isString)) JsonPrimitive(raw) else parsed ?: JsonPrimitive(raw)
        val next = runCatching { json.decodeFromJsonElement(AppSettings.serializer(), JsonObject(current + (key to value))) }
            .getOrElse { throw AutomationException("bad value for $key: ${it.message}") }
        controller.update({ next }, reapply = key in RESTART_SETTINGS)
        return buildJsonObject {
            put("key", key)
            put("value", settingsJson()[key] ?: JsonNull)
        }
    }

    private fun addRule(args: Map<String, String>): JsonObject {
        val domain = args["domain"] ?: throw AutomationException("pass domain")
        val action = RuleAction.entries.firstOrNull { it.name.equals(args["action"] ?: "", true) } ?: throw AutomationException("action: direct, proxy or block")
        controller.addSiteRule(domain, action)
        return buildJsonObject {
            put("domain", domain)
            put("action", action.name.lowercase())
        }
    }

    private fun logs(args: Map<String, String>): JsonArray {
        val count = args["count"]?.toIntOrNull()?.coerceIn(1, 500) ?: 100
        val redact = args.flag("redact", false)
        return JsonArray(controller.logs.value.takeLast(count).map { line ->
            buildJsonObject {
                put("time", line.time)
                put("level", line.level)
                put("text", if (redact) redactLog(line.text) else line.text)
            }
        })
    }

    private suspend fun closeConnections(args: Map<String, String>): JsonArray {
        val id = args["id"]
        val left = if (id == null || id == "all") controller.closeAllConnections() else controller.closeConnection(id)
        return JsonArray(left.map(::connectionJson))
    }

    private suspend fun site(args: Map<String, String>): JsonElement {
        val input = args["host"] ?: args["url"] ?: throw AutomationException("pass host")
        val route = controller.checkSite(input) ?: throw AutomationException("bad host")
        return buildJsonObject {
            put("host", route.host)
            put("exit", route.exit)
            put("rule", route.rule)
            put("payload", route.payload)
            put("source", route.source.name.lowercase())
            put("error", route.error)
        }
    }

    private fun profileJson(profile: Profile) = buildJsonObject {
        put("id", profile.id)
        put("name", profile.name)
        put("url", profile.url)
        put("active", profile.id == controller.settings.value.activeProfile)
        put("servers", profile.servers)
        put("updated_at", profile.updatedAt)
        put("used", profile.used)
        put("total", profile.total)
        put("expire", profile.expire)
        put("manual", profile.manual)
        put("merged", profile.members.isNotEmpty())
    }

    private fun connectionJson(c: ConnectionInfo) = buildJsonObject {
        put("id", c.id)
        put("host", c.host)
        put("network", c.network)
        put("chain", c.chain)
        put("rule", c.rule)
        put("payload", c.rulePayload)
        put("process", c.process)
        put("upload", c.upload)
        put("download", c.download)
        put("start", c.startMillis)
    }

    private fun Map<String, String>.flag(name: String, default: Boolean) = this[name]?.lowercase()?.let { it == "true" || it == "1" || it == "yes" } ?: default

    private class AutomationException(message: String) : Exception(message)

    companion object {
        private const val WAIT = 20_000L

        fun stateName(state: TunnelState) = when (state) {
            TunnelState.Off -> "off"
            TunnelState.Starting -> "starting"
            TunnelState.On -> "on"
            TunnelState.Stopping -> "stopping"
            is TunnelState.Failed -> "failed"
        }

        fun newKey(): String {
            val bytes = ByteArray(18).also { java.security.SecureRandom().nextBytes(it) }
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        private val HIDDEN_SETTINGS = setOf("automationKey")
        private val LOCKED_SETTINGS = setOf("automation", "automationKey")

        // то же, что в интерфейсе меняется с перезапуском ядра
        private val RESTART_SETTINGS = setOf(
            "allowBypass", "blockAds", "bypassLocal", "customDns", "dnsHijackAll", "dnsMode", "fakeIp", "logLevel", "mode", "mtu",
            "rawRules", "region", "regionDirect", "rules", "skipCertVerify", "sniffer", "stack", "tcpConcurrent", "tcpFastOpen", "unifiedDelay",
            "ipv6", "testUrl", "splitMode", "excludedApps", "onlyApps",
        )

        // аргументы для экрана плагина Tasker/MacroDroid, порядок как в полях
        val ARGS = mapOf(
            "profile.activate" to listOf("name"),
            "profile.add" to listOf("url"),
            "profile.refresh" to listOf("name"),
            "profile.delete" to listOf("name"),
            "server.select" to listOf("name", "group"),
            "ping" to listOf("name"),
            "settings.get" to listOf("name"),
            "settings.set" to listOf("name", "value"),
            "rule.add" to listOf("domain", "action"),
            "logs" to listOf("count"),
            "connections.close" to listOf("id"),
            "site" to listOf("host"),
        )

        val COMMANDS = linkedMapOf(
            "help" to "this list",
            "status" to "tunnel state, profile, server, speed and traffic",
            "connect" to "turn on; wait=false to return at once",
            "disconnect" to "turn off; wait=false to return at once",
            "toggle" to "turn on or off",
            "reconnect" to "restart the tunnel",
            "profiles" to "all subscriptions",
            "profile.activate" to "id or name",
            "profile.add" to "url or text",
            "profile.refresh" to "id, name or all; the active one by default",
            "profile.delete" to "id or name",
            "servers" to "groups, servers and last delays",
            "server.select" to "name, optional group",
            "ping" to "optional name; all servers by default",
            "latency" to "direct and current server delay",
            "settings.get" to "optional name",
            "settings.set" to "name and value (JSON or plain text)",
            "rule.add" to "domain and action: direct, proxy or block",
            "logs" to "count (1..500), redact=true",
            "connections" to "open connections",
            "connections.close" to "id or all",
            "exit_ip" to "public IP of the current route",
            "site" to "host: which rule and exit a site uses",
            "services" to "check of popular services",
            "device" to "version, build channel and device",
        )
    }
}

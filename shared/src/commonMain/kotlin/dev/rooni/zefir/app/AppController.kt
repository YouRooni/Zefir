@file:OptIn(kotlinx.coroutines.FlowPreview::class)

package dev.rooni.zefir.app

import dev.rooni.zefir.model.*
import dev.rooni.zefir.platform.Fs
import dev.rooni.zefir.platform.IdleEvent
import dev.rooni.zefir.platform.Platform
import dev.rooni.zefir.platform.nowMillis
import dev.rooni.zefir.res.*
import org.jetbrains.compose.resources.getString
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*

class AppController(val platform: Platform, private val scope: CoroutineScope) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val settingsPath = "${platform.dataDir}/settings.json"
    private val profilesPath = "${platform.dataDir}/profiles.json"
    private val core get() = platform.engine.core
    private val importer by lazy { ProfileImporter(platform.dataDir, core, ::deviceHeaders, platform::directRoute) }
    private val applyLock = Mutex()
    private val restartLock = Mutex()
    private val lifecycleLock = Mutex()
    private var connectJob: Job? = null
    @Volatile private var wantedSeq = 0L
    private val logBuffer = StringBuilder()

    private val diskDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val settingsDirty = MutableStateFlow(0)
    private val profilesDirty = MutableStateFlow(0)
    private val logDirty = MutableStateFlow(0)

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AppSettings> = _settings

    private val _profiles = MutableStateFlow(loadProfiles())
    val profiles: StateFlow<List<Profile>> = _profiles

    private val _proxies = MutableStateFlow(ProxySnapshot())
    val proxies: StateFlow<ProxySnapshot> = _proxies

    private val _delays = MutableStateFlow<Map<String, Int>>(emptyMap())
    val delays: StateFlow<Map<String, Int>> = _delays

    private val _testing = MutableStateFlow(false)
    val testing: StateFlow<Boolean> = _testing

    private val _testingServers = MutableStateFlow<Set<String>>(emptySet())
    val testingServers: StateFlow<Set<String>> = _testingServers
    private val singleTests = java.util.concurrent.ConcurrentHashMap<String, Job>()
    @Volatile private var testJob: Job? = null

    private val _speed = MutableStateFlow(Traffic())
    val speed: StateFlow<Traffic> = _speed

    private val _total = MutableStateFlow(Traffic())
    val total: StateFlow<Traffic> = _total

    private val _logs = MutableStateFlow<List<LogLine>>(emptyList())
    val logs: StateFlow<List<LogLine>> = _logs

    private val _message = MutableSharedFlow<UiMessage>(extraBufferCapacity = 8)
    val message: SharedFlow<UiMessage> = _message

    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready

    private val _apps = MutableStateFlow<List<InstalledApp>?>(null)
    val apps: StateFlow<List<InstalledApp>?> = _apps

    private val logPath = "${platform.dataDir}/core.log"

    private val _pendingImport = MutableStateFlow<String?>(null)
    val pendingImport: StateFlow<String?> = _pendingImport

    val tunnel get() = platform.engine.state

    private val _refreshing = MutableStateFlow<Set<String>>(emptySet())
    val refreshing: StateFlow<Set<String>> = _refreshing
    private val _refreshCooldown = MutableStateFlow<Set<String>>(emptySet())
    val refreshCooldown: StateFlow<Set<String>> = _refreshCooldown

    val automation by lazy { Automation(this) }

    fun setAutomation(on: Boolean) = update({ it.copy(automation = on, automationKey = it.automationKey.ifEmpty { Automation.newKey() }) })

    fun newAutomationKey() = update({ it.copy(automationKey = Automation.newKey()) })

    fun deviceHeaders(): Map<String, String> {
        val s = _settings.value
        if (!s.sendDevice) return emptyMap()
        val own = platform.deviceHeaders
        if (dev.rooni.zefir.ui.PLAY_BUILD) return own
        return own + buildMap {
            s.deviceHwid.trim().ifEmpty { null }?.let { put("x-hwid", it) }
            s.deviceModel.trim().ifEmpty { null }?.let { put("x-device-model", it) }
            s.deviceOsVersion.trim().ifEmpty { null }?.let { put("x-ver-os", it) }
        }
    }

    private val _connectedAt = MutableStateFlow<Long?>(null)
    val connectedAt: StateFlow<Long?> = _connectedAt
    val detectedCountry: String by lazy { platform.detectCountry() }
    val activeProfile: Profile? get() = _profiles.value.firstOrNull { it.id == _settings.value.activeProfile }
    val region: RegionPack? get() = _settings.value.effectiveRegion(detectedCountry)

    init {
        _settings.value.let { s ->
            if (s.widgetsVersion < 1) {
                val list = s.widgets.toMutableList()
                val at = list.indexOf(HomeWidget.Traffic)
                if (at >= 0 && HomeWidget.Session !in list) list.add(at, HomeWidget.Session)
                _settings.value = s.copy(widgets = list, widgetsVersion = 1)
                persistSettings()
            }
        }
        // автоправила из бекапа другой сборки там, где их нет (десктоп, Zefir X)
        if (!platform.hasWifiRules && _settings.value.autoRules) {
            _settings.value = _settings.value.copy(autoRules = false)
            persistSettings()
        }
        dev.rooni.zefir.ui.AppMotion.off = _settings.value.noAnimations
        scope.launch { _settings.collect { dev.rooni.zefir.ui.AppMotion.off = it.noAnimations } }
        scope.launch(Dispatchers.Default) {
            runCatching {
                platform.copyGeoData()
                core.init(platform.dataDir, platform.sdkVersion)
                Fs.writeText(logPath, "")
                core.onLog { line ->
                    if (platform.foreground.value || _settings.value.logLevel == "debug" || line.level !in QUIET_LOGS) addLog(line)
                }
                core.startLog()
                reload()
            }.onFailure { _message.tryEmit(UiMessage.Failure(it.message)) }
            _ready.value = true
            launch { loadApps() }
            autoUpdateIfNeeded()
            launch {
                while (true) {
                    delay(AUTO_UPDATE_CHECK)
                    autoUpdateIfNeeded()
                }
            }

            if (_settings.value.connectOnStart && !_settings.value.autoRules && activeProfile != null) connect()
        }
        scope.launch { pollTraffic() }
        scope.launch(diskDispatcher) {
            logDirty.filter { it > 0 }.collect {
                delay(2000)
                flushLog()
            }
        }
        scope.launch(diskDispatcher) {
            settingsDirty.filter { it > 0 }.conflate().collect { Fs.writeText(settingsPath, json.encodeToString(AppSettings.serializer(), _settings.value)) }
        }
        scope.launch(diskDispatcher) {
            profilesDirty.filter { it > 0 }.conflate().collect { Fs.writeText(profilesPath, json.encodeToString(ListSerializer(Profile.serializer()), _profiles.value)) }
        }
        scope.launch {
            _proxies.map { snapshot -> snapshot.resolve(snapshot.main?.name) }.distinctUntilChanged().collect { platform.engine.updateServer(it) }
        }
        scope.launch {
            tunnel.collect { state ->
                when (state) {
                    TunnelState.On -> if (_connectedAt.value == null) _connectedAt.value = nowMillis()
                    TunnelState.Off, is TunnelState.Failed -> _connectedAt.value = null
                    else -> Unit
                }
            }
        }

        scope.launch {
            _ready.first { it }
            platform.network.debounce(AUTO_SETTLE).collect { applyAutoRules() }
        }
        scope.launch { watchTunnelForAutoRules() }
        scope.launch {
            while (isActive) {
                delay(AUTO_SWITCH_EVERY)
                runCatching { switchIfServerDied() }
            }
        }
        scope.launch { settings.map { it.haptics }.distinctUntilChanged().collect(platform::setHaptics) }
        scope.launch { settings.map { it.desktopTun }.distinctUntilChanged().collect(platform::applyDesktopSettings) }
        scope.launch { platform.idleEvents.collect(::onIdleEvent) }
        scope.launch {
            _ready.first { it }
            syncWatching()

            if (platform.isInteractive) resumeAfterIdle()
        }

        scope.launch {
            platform.resumed.collect {
                if (_ready.value && it > 0) resumeIfUnlocked()
            }
        }
        scope.launch {
            settings.map { it.idleStopped }.distinctUntilChanged().collectLatest { stopped ->
                while (stopped) {
                    delay(IDLE_RESUME_POLL)
                    resumeIfUnlocked()
                }
            }
        }
    }

    private fun addLog(line: LogLine) {
        _logs.update { (it + line).takeLast(500) }
        synchronized(logBuffer) { logBuffer.append('[').append(line.level).append("] ").append(line.text).append('\n') }
        if (line.level == "error") flushLog() else logDirty.update { it + 1 }
    }

    private fun flushLog() {
        val text = synchronized(logBuffer) {
            logBuffer.toString().also { logBuffer.setLength(0) }
        }
        if (text.isEmpty()) return
        runCatching { Fs.appendText(logPath, text) }
        logBytes += text.length
        if (logBytes > LOG_LIMIT) runCatching {
            val tail = Fs.readText(logPath).takeLast(LOG_KEEP).substringAfter('\n')
            Fs.writeText(logPath, tail)
            logBytes = tail.length.toLong()
        }
    }

    @Volatile private var logBytes = 0L

    suspend fun loadApps() {
        if (_apps.value == null) _apps.value = runCatching { platform.installedApps() }.getOrDefault(emptyList())
    }

    fun applyRegionPacks(packs: List<AppPack>) {
        scope.launch(Dispatchers.Default) {
            loadApps()
            val matched = _apps.value.orEmpty().filter { app -> packs.any { it.matches(app.pkg) } }.map { it.pkg }.toSet()
            update({
                it.copy(
                    splitMode = if (matched.isNotEmpty()) SplitMode.Exclude else it.splitMode,
                    excludedApps = it.excludedApps + matched,
                    appliedPacks = packs.map { p -> p.id }.toSet(),
                    regionDirect = true,
                )
            }, reapply = true)
        }
    }

    private suspend fun pollTraffic() {
        val live = _settings.map { it.notifLiveSpeed && it.notifEnabled }.distinctUntilChanged()
        combine(tunnel, platform.foreground, live) { state, foreground, notification ->
            state == TunnelState.On && (foreground || notification)
        }.distinctUntilChanged().collectLatest { needed ->
            if (!needed) {
                _speed.value = Traffic()
                return@collectLatest
            }
            while (currentCoroutineContext().isActive) {
                runCatching {
                    val speed = core.speed()
                    _speed.value = speed
                    _total.value = core.totalTraffic()

                    if (_settings.value.notifLiveSpeed && _settings.value.notifEnabled) platform.engine.updateNotification(speed)
                }
                delay(1000)
            }
        }
    }

    fun update(transform: (AppSettings) -> AppSettings, reapply: Boolean = false) {
        val before = _settings.getAndUpdate(transform)
        if (before == _settings.value) return
        persistSettings()
        platform.engine.applyOptions(tunnelOptions())
        if (reapply) scope.launch(Dispatchers.Default) { reloadAndRestart() }
    }

    fun requestImport(text: String) { _pendingImport.value = text }

    fun consumeImport() { _pendingImport.value = null }

    suspend fun importSubscription(input: String): ImportError? = withContext(Dispatchers.Default) {
        try {
            val profile = importer.import(input)
            _profiles.update { it + profile }
            persistProfiles()
            update({ it.copy(activeProfile = profile.id) })
            reloadAndRestart()
            null
        } catch (e: ImportException) {
            addLog(LogLine("error", "import: ${e.message}"))
            e.reason
        } catch (e: Exception) {
            addLog(LogLine("error", "import: ${e.message}"))
            ImportError.Unsupported
        }
    }

    private fun mergeRefreshed(updated: Profile) {
        _profiles.update { list ->

            list.map { if (it.id == updated.id) updated.copy(name = it.name, routingEnabled = it.routingEnabled, selected = it.selected, pinned = it.pinned, hiddenTags = it.hiddenTags, hideDead = it.hideDead, chains = it.chains) else it }
        }
    }

    fun refreshProfile(id: String) {
        if (id in _refreshing.value || id in _refreshCooldown.value) return
        _refreshing.update { it + id }
        scope.launch(Dispatchers.Default) {
            try {
                val current = _profiles.value.firstOrNull { it.id == id } ?: return@launch
                if (current.members.isNotEmpty()) {
                    val failed = current.members.count { member ->
                        val source = _profiles.value.firstOrNull { it.id == member }
                        val link = source?.url ?: return@count false
                        runCatching { importLocked(link, source) }.onSuccess(::mergeRefreshed).isFailure
                    }
                    val error = rebuildMerged(id)
                    persistProfiles()
                    if (_settings.value.activeProfile == id) reloadAndRestart()
                    _message.tryEmit(if (failed == 0 && error == null) UiMessage.Updated else UiMessage.UpdateFailed)
                    return@launch
                }
                val url = current.url ?: return@launch
                runCatching { importLocked(url, current) }
                    .onSuccess { updated ->
                        mergeRefreshed(updated)
                        persistProfiles()
                        if (_settings.value.activeProfile == id) reloadAndRestart()
                        syncMerged(id)
                        _message.tryEmit(UiMessage.Updated)
                    }
                    .onFailure { _message.tryEmit(UiMessage.UpdateFailed) }
            } finally {
                _refreshCooldown.update { it + id }
                _refreshing.update { it - id }

                scope.launch {
                    delay(REFRESH_COOLDOWN)
                    _refreshCooldown.update { it - id }
                }
            }
        }
    }

    suspend fun serverLink(name: String): String? = withContext(Dispatchers.Default) {
        val profile = activeProfile ?: return@withContext null
        profile.serverLinks[name] ?: runCatching { findServerLink(readConfig(profile.id), name) }.getOrNull()
    }

    fun readConfig(id: String): String = runCatching { Fs.readText(importer.pathOf(id)) }.getOrDefault("")

    suspend fun saveProfile(id: String, name: String, url: String?, config: String?): String? = withContext(Dispatchers.Default) {
        val current = _profiles.value.firstOrNull { it.id == id } ?: return@withContext null
        var updated = current.copy(name = name.trim())
        if (config != null && config != readConfig(id)) {
            importer.saveConfig(id, config)?.let { return@withContext errorText(it) }
        } else if (url != null && url.trim() != current.url) {
            updated = try {
                importLocked(url.trim(), current).copy(name = name.trim())
            } catch (e: Exception) {
                return@withContext errorText(e.message)
            }
        }
        _profiles.update { list -> list.map { if (it.id == id) updated else it } }
        persistProfiles()
        if (_settings.value.activeProfile == id) reloadAndRestart()
        syncMerged(id)
        null
    }

    suspend fun saveMerged(id: String?, name: String, members: List<String>): String? = withContext(Dispatchers.Default) {
        val target = id ?: java.util.UUID.randomUUID().toString()
        val previous = _profiles.value.firstOrNull { it.id == target }
        val draft = (previous ?: Profile(id = target, name = name)).copy(name = name.trim(), members = members)
        _profiles.update { list -> if (previous == null) list + draft else list.map { if (it.id == target) draft else it } }
        val error = rebuildMerged(target)
        if (error != null) {
            _profiles.update { list -> if (previous == null) list.filterNot { it.id == target } else list.map { if (it.id == target) previous else it } }
            return@withContext error
        }
        persistProfiles()
        if (_settings.value.activeProfile == target) reloadAndRestart()
        null
    }

    private suspend fun rebuildMerged(id: String): String? {
        val merged = _profiles.value.firstOrNull { it.id == id } ?: return null
        val sources = merged.members.mapNotNull { member -> _profiles.value.firstOrNull { it.id == member } }
        val result = runCatching { mergeProfileConfigs(sources.map { readConfig(it.id) }) }.getOrElse { return errorText(it.message) }
        importer.saveConfig(id, result.config)?.let { return errorText(it) }
        val count = core.profileCount(importer.pathOf(id))
        fun renamed(pick: (Profile) -> Map<String, String>) = sources.zip(result.renames).flatMap { (source, names) ->
            pick(source).mapNotNull { (name, value) -> names[name]?.let { it to value } }
        }.toMap()
        _profiles.update { list ->
            list.map { profile ->
                if (profile.id != id) return@map profile
                profile.copy(
                    updatedAt = nowMillis(),
                    servers = count,
                    used = sources.sumOf { it.used },
                    total = if (sources.all { it.total > 0 }) sources.sumOf { it.total } else 0,
                    expire = sources.map { it.expire }.filter { it > 0 }.minOrNull() ?: 0,
                    serverNotes = renamed { it.serverNotes },
                    serverLinks = renamed { it.serverLinks },
                )
            }
        }
        return null
    }

    private suspend fun syncMerged(member: String) {
        _profiles.value.filter { member in it.members }.forEach { merged ->
            rebuildMerged(merged.id)?.let { addLog(LogLine("error", "merge: $it")) }
            persistProfiles()
            if (_settings.value.activeProfile == merged.id) reloadAndRestart()
        }
    }

    suspend fun shareProfile(profile: Profile) {
        val url = profile.url
        if (url != null) platform.shareText(url) else platform.shareFile("${profile.name}.yaml", readConfig(profile.id))
    }

    suspend fun connections(): List<ConnectionInfo> = withContext(Dispatchers.Default) {
        runCatching { core.connections().mapNotNull(::parseConnection).sortedByDescending { it.start } }.getOrDefault(emptyList())
    }

    suspend fun closeConnection(id: String): List<ConnectionInfo> {
        runCatching { core.closeConnection(id) }
        return connections()
    }

    suspend fun closeAllConnections(): List<ConnectionInfo> {
        runCatching { core.closeConnections() }
        return connections()
    }

    private fun parseConnection(item: JsonElement): ConnectionInfo? {
        val row = item as? JsonObject ?: return null
        val meta = row["metadata"] as? JsonObject
        fun text(source: JsonObject?, key: String) = source?.get(key)?.jsonPrimitive?.contentOrNull.orEmpty()
        val host = text(meta, "host").ifEmpty { text(meta, "destinationIP") }
        val chains = (row["chains"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        val start = text(row, "start")
        return ConnectionInfo(
            id = text(row, "id"),
            host = "$host:${text(meta, "destinationPort")}",
            network = text(meta, "network"),
            chain = chains.reversed().joinToString(" → "),
            rule = text(row, "rule"),
            upload = row["upload"]?.jsonPrimitive?.longOrNull ?: 0,
            download = row["download"]?.jsonPrimitive?.longOrNull ?: 0,
            start = start,
            exit = chains.firstOrNull().orEmpty(),
            process = text(meta, "process"),
            startMillis = runCatching { java.time.OffsetDateTime.parse(start).toInstant().toEpochMilli() }.getOrDefault(0L),
            rulePayload = text(row, "rulePayload"),
        )
    }

    suspend fun exitIp(): ExitIp? = withContext(Dispatchers.Default) {
        val main = _proxies.value.main?.name ?: return@withContext null
        val answers = coroutineScope {
            IP_SOURCES.map { url -> async { runCatching { core.outboundBody(main, url) }.getOrNull()?.let(::parseExitIp) } }.awaitAll()
        }.filterNotNull()
        pickExitIp(answers)
    }

    fun speedTester() = SpeedTester(platform.vpnProxy)

    private val _dns = MutableStateFlow<List<dev.rooni.zefir.core.DnsEntry>>(emptyList())
    val dns: StateFlow<List<dev.rooni.zefir.core.DnsEntry>> = _dns
    private var dnsWatchers = 0

    @Synchronized
    fun watchDns(on: Boolean) {
        dnsWatchers = (dnsWatchers + if (on) 1 else -1).coerceAtLeast(0)
        core.onDns(if (dnsWatchers > 0) { entry -> _dns.update { (listOf(entry) + it).take(DNS_KEEP) } } else null)
    }

    fun clearDns() { _dns.value = emptyList() }

    suspend fun checkSite(input: String): SiteRoute? = withContext(Dispatchers.Default) {
        val host = siteHost(input) ?: return@withContext null
        val probe = runCatching { core.probe("https://$host/") }.getOrNull() ?: return@withContext SiteRoute(host, "", "", "", RuleSource.Unknown, "failed")
        val exit = probe.chains.firstOrNull().orEmpty()
        SiteRoute(host, exit, probe.rule, probe.payload, ruleSource(probe.rule, probe.payload), probe.error.takeIf { exit.isEmpty() })
    }

    fun ruleSource(rule: String, payload: String): RuleSource {
        val s = _settings.value
        if (s.mode != RoutingMode.Smart) return RuleSource.Mode
        if (rule.isEmpty()) return RuleSource.Unknown
        if (rule.equals("Match", true)) return RuleSource.Default
        fun norm(type: String) = type.replace("-", "").lowercase().removeSuffix("6")
        val key = norm(rule) to payload.lowercase()
        fun matches(lines: List<String>) = lines.any { line ->
            val parts = line.split(',')
            parts.size >= 2 && norm(parts[0]) to parts[1].trim().lowercase() == key
        }
        return when {
            s.rules.any { norm("DOMAIN-SUFFIX") to it.value.lowercase() == key } || matches(s.rawRules) -> RuleSource.Yours
            s.bypassLocal && matches(LOCAL_RULES) -> RuleSource.Local
            s.blockAds && key == ("geosite" to "category-ads-all") -> RuleSource.Ads
            s.regionDirect && matches(region?.directRules.orEmpty()) -> RuleSource.Region
            else -> RuleSource.Subscription
        }
    }

    fun addSiteRule(domain: String, action: RuleAction) {
        val value = domain.trim().lowercase().removePrefix("www.")
        if (value.isEmpty() || ',' in value) return
        update({ s -> s.copy(rules = s.rules.filterNot { it.value == value } + UserRule(value, action)) }, reapply = true)
    }

    fun currentServer(): String? {
        if (tunnel.value != TunnelState.On) return null
        val snapshot = _proxies.value
        return snapshot.resolve(snapshot.main?.name)
    }

    suspend fun pingDirect(): Int = runCatching { core.delay("DIRECT", _settings.value.testUrl) }.getOrDefault(-1)

    suspend fun pingCurrent(): Int {
        val snapshot = _proxies.value
        val name = snapshot.resolve(snapshot.main?.name) ?: return -1
        return runCatching { core.delay(name, _settings.value.testUrl) }.getOrDefault(-1)
    }

    suspend fun serviceCheck(): List<Pair<String, String>> = withContext(Dispatchers.Default) {
        val main = _proxies.value.main?.name.orEmpty()
        runCatching { core.serviceCheck(main) }.getOrDefault(emptyList())
    }

    suspend fun shareLogs() {
        val text = buildString {
            append(platform.deviceInfo()).append("\n\n")
            _logs.value.forEach { append('[').append(it.level).append("] ").append(redactLog(it.text)).append('\n') }
        }
        runCatching { platform.shareFile("zefir-log.txt", text) }
    }

    suspend fun exportBackup(): Boolean {
        val backup = buildJsonObject {
            put("version", 1)
            // ключ автоматизации в файл не кладём
            put("settings", json.encodeToJsonElement(AppSettings.serializer(), _settings.value.copy(automation = false, automationKey = "")))
            put("profiles", json.encodeToJsonElement(ListSerializer(Profile.serializer()), _profiles.value))
            putJsonObject("files") {
                _profiles.value.forEach { put(it.id, readConfig(it.id)) }
            }
        }
        return runCatching { platform.saveFile("zefir-backup.json", backup.toString()) }.getOrDefault(false)
    }

    suspend fun importBackup(text: String): Boolean = withContext(Dispatchers.Default) {
        runCatching {
            val root = Json.parseToJsonElement(text).jsonObject
            val settings = json.decodeFromJsonElement(AppSettings.serializer(), root.getValue("settings"))
            val profiles = json.decodeFromJsonElement(ListSerializer(Profile.serializer()), root.getValue("profiles"))
                .filter { PROFILE_ID.matches(it.id) }
                .map { profile -> profile.copy(members = profile.members.filter { PROFILE_ID.matches(it) }) }
            val files = root["files"]?.jsonObject.orEmpty().filterKeys { PROFILE_ID.matches(it) }
            val old = _profiles.value.map { it.id }.toSet()
            files.forEach { (id, content) -> Fs.writeText(importer.pathOf(id), content.jsonPrimitive.content) }
            (old - profiles.map { it.id }.toSet()).forEach { id ->
                Fs.delete(importer.pathOf(id))
                Fs.delete(tunedPath(id))
            }
            _profiles.value = profiles
            persistProfiles()
            val own = _settings.value
            _settings.value = settings.copy(onboarded = true, automation = own.automation, automationKey = own.automationKey)
            persistSettings()
            syncWatching()
            reloadAndRestart()
        }.isSuccess
    }

    @Volatile private var lastPlace: String? = null
    @Volatile private var pendingAuto = false
    @Volatile private var autoActing = false
    @Volatile private var restarting = false
    @Volatile private var handUntil = 0L
    private var serverMisses = 0
    @Volatile private var addressCache: Pair<String, Map<String, ServerAddress>>? = null
    private val pinger by lazy { TcpPinger(platform) }

    fun applyAutoRules(force: Boolean = false) {
        val s = _settings.value
        syncWatching()
        if (!s.autoRules || s.idleStopped) return
        val place = autoPlace(platform.network.value) ?: return
        val changed = place != lastPlace
        if (changed) {
            lastPlace = place
            if (s.autoPausedOn != null && s.autoPausedOn != place) update({ it.copy(autoPausedOn = null) })
        }
        val state = tunnel.value

        if (state == TunnelState.Starting || state == TunnelState.Stopping || restarting) {
            if (changed || force) pendingAuto = true
            return
        }
        val current = _settings.value
        when (decideAuto(place, current.trustedWifi, state, current.autoConnectElsewhere, current.autoStopped, current.autoPausedOn, changed, force)) {
            AutoAction.Stop -> {
                addLog(LogLine("info", "auto rules: trusted network, turning off"))
                autoActing = true
                update({ it.copy(autoStopped = true) })
                disconnect()
            }
            AutoAction.Start -> {
                addLog(LogLine("info", "auto rules: other network, turning on"))
                autoActing = true
                update({ it.copy(autoStopped = false) })
                connect(auto = true)
            }
            AutoAction.None -> Unit
        }
    }

    private fun syncWatching() {
        val s = _settings.value
        platform.setBootWatching(s.autoRules || s.idleOff)
        platform.setSsidWatching(s.autoRules)
        platform.setWatching((s.autoRules && tunnel.value != TunnelState.On) || s.idleStopped)
    }

    fun setIdleOff(on: Boolean) {
        update({ it.copy(idleOff = on, idleStopped = false) })
        if (!on) platform.cancelIdleAlarm()
        syncWatching()
    }

    private fun onIdleEvent(event: IdleEvent) {
        val s = _settings.value
        when (event) {
            IdleEvent.ScreenOff -> if (s.idleOff && tunnel.value == TunnelState.On) platform.armIdleAlarm(s.idleOffMinutes * 60_000L)
            IdleEvent.Unlocked -> {
                platform.cancelIdleAlarm()
                resumeAfterIdle()
            }
            IdleEvent.Alarm -> scope.launch(Dispatchers.Default) { stopIfIdle() }
        }
    }

    private suspend fun stopIfIdle() {
        if (!_settings.value.idleOff || tunnel.value != TunnelState.On || platform.isInteractive) return
        if (_settings.value.idleNightOnly) {
            val wait = msUntilNight(java.time.LocalTime.now())
            if (wait > 0) {
                platform.armIdleAlarm(wait)
                return
            }
        }
        val now = runCatching { core.speed() }.getOrDefault(Traffic())
        if (now.up + now.down > IDLE_TRAFFIC) {
            platform.armIdleAlarm(IDLE_RECHECK)
            return
        }
        addLog(LogLine("info", "idle: turning off"))
        autoActing = true

        update({ it.copy(idleStopped = true) })
        syncWatching()
        disconnect()
    }

    private fun resumeIfUnlocked() {
        if (platform.isInteractive && !platform.isLocked) resumeAfterIdle()
    }

    private fun resumeAfterIdle() {
        val s = _settings.value
        if (!s.idleStopped) return
        update({ it.copy(idleStopped = false) })
        syncWatching()
        val state = tunnel.value
        if (state != TunnelState.Off && state !is TunnelState.Failed) return

        val place = autoPlace(platform.network.value)
        if (s.autoRules && place != null && isTrustedPlace(place, s.trustedWifi)) return
        addLog(LogLine("info", "idle: turning on"))
        autoActing = true
        connect(auto = true)
    }

    private suspend fun watchTunnelForAutoRules() {
        var previous = tunnel.value
        tunnel.collect { state ->
            val s = _settings.value
            syncWatching()
            if (state == TunnelState.On && previous != TunnelState.On && s.idleOff && !platform.isInteractive) {
                platform.armIdleAlarm(s.idleOffMinutes * 60_000L)
            }
            if (s.autoRules && !autoActing && !restarting) {
                if (state == TunnelState.Off && (previous == TunnelState.On || previous == TunnelState.Stopping)) {
                    update({ it.copy(autoPausedOn = lastPlace, autoStopped = false) })
                } else if (state == TunnelState.On && previous != TunnelState.On) {
                    update({ it.copy(autoPausedOn = null, autoStopped = false) })
                }
            }
            if (state == TunnelState.On || state == TunnelState.Off || state is TunnelState.Failed) {
                if (previous != state) autoActing = false
                if (pendingAuto && !restarting) {
                    pendingAuto = false
                    applyAutoRules(force = true)
                }
            }
            previous = state
        }
    }

    fun openShortcut(id: String) {
        scope.launch(Dispatchers.Default) {
            _ready.first { it }
            if (_profiles.value.none { it.id == id }) return@launch
            if (_settings.value.activeProfile != id) activate(id)
            if (tunnel.value == TunnelState.Off || tunnel.value is TunnelState.Failed) connect()
        }
    }

    // если тыкать подписки подряд, перезапуск один, на последнюю выбранную
    private val switchRequests = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    private val switchWorker by lazy {
        scope.launch(Dispatchers.Default) {
            for (request in switchRequests) {
                if (tunnel.value != TunnelState.Off && tunnel.value !is TunnelState.Failed) delay(SWITCH_SETTLE)
                switchRequests.tryReceive()
                reloadAndRestart()
            }
        }
    }

    fun reorderProfiles(ids: List<String>) {
        _profiles.update { list ->
            val byId = list.associateBy { it.id }
            ids.mapNotNull { byId[it] } + list.filter { it.id !in ids }
        }
        persistProfiles()
    }

    fun activate(id: String) {
        update({ it.copy(activeProfile = id) })
        switchWorker
        switchRequests.trySend(Unit)
    }

    suspend fun setProfileClient(id: String, client: String, custom: String?): ImportError? = withContext(Dispatchers.Default) {
        val current = _profiles.value.firstOrNull { it.id == id } ?: return@withContext null
        val url = current.url ?: return@withContext null
        try {
            val updated = importLocked(url, current, client, custom)
            mergeRefreshed(updated)
            persistProfiles()
            if (_settings.value.activeProfile == id) reloadAndRestart()
            syncMerged(id)
            null
        } catch (e: ImportException) {
            addLog(LogLine("error", "subscription client: ${e.message}"))
            e.reason
        } catch (e: Exception) {
            addLog(LogLine("error", "subscription client: ${e.message}"))
            ImportError.Unsupported
        }
    }

    fun setProfileRouting(id: String, enabled: Boolean) {
        _profiles.update { list -> list.map { if (it.id == id) it.copy(routingEnabled = enabled) else it } }
        persistProfiles()
        if (_settings.value.activeProfile == id) scope.launch(Dispatchers.Default) { reloadAndRestart() }
    }

    fun delete(id: String) {
        _profiles.update { list -> list.filterNot { it.id == id } }
        persistProfiles()
        Fs.delete(importer.pathOf(id))
        Fs.delete(tunedPath(id))
        if (_settings.value.activeProfile == id) {
            update({ it.copy(activeProfile = _profiles.value.firstOrNull()?.id) })
            scope.launch(Dispatchers.Default) {
                if (activeProfile == null) disconnect()
                reloadAndRestart()
            }
        }
        _profiles.value.filter { id in it.members }.forEach { merged ->
            val rest = merged.members - id
            if (rest.isEmpty()) {
                delete(merged.id)
            } else {
                _profiles.update { list -> list.map { if (it.id == merged.id) it.copy(members = rest) else it } }
                scope.launch(Dispatchers.Default) {
                    rebuildMerged(merged.id)
                    persistProfiles()
                    if (_settings.value.activeProfile == merged.id) reloadAndRestart()
                }
            }
        }
    }

    private fun tunedPath(id: String) = "${platform.dataDir}/profiles/$id.tuned.yaml"

    private fun runtimeProfile(id: String, path: String): String {
        val tuned = tunedPath(id)
        val chains = _profiles.value.firstOrNull { it.id == id }?.chains.orEmpty()
        val fastOpen = _settings.value.tcpFastOpen
        val text = if (platform.hasIdleOff || chains.isNotEmpty() || fastOpen) {
            runCatching { tuneProfile(Fs.readText(path), MIN_HEALTH_INTERVAL.takeIf { platform.hasIdleOff }, chains, fastOpen) }.getOrNull()
        } else {
            null
        }
        if (text == null) {
            Fs.delete(tuned)
            return path
        }
        Fs.writeText(tuned, text)
        return tuned
    }

    fun connect(auto: Boolean = false) {
        val mine = ++wantedSeq
        connectJob?.cancel()
        connectJob = scope.launch(Dispatchers.Default) {
            _ready.first { it }
            if (activeProfile == null) {
                autoActing = false
                if (!auto) _message.tryEmit(UiMessage.NoProfile)
                return@launch
            }

            if (auto && !platform.engine.hasPermission()) {
                autoActing = false
                addLog(LogLine("warning", "auto rules: no VPN permission"))
                _message.tryEmit(UiMessage.PermissionDenied)
                return@launch
            }
            if (!platform.engine.ensurePermission()) {
                autoActing = false
                _message.tryEmit(UiMessage.PermissionDenied)
                return@launch
            }
            if (platform.takeTunDeclined()) _message.tryEmit(UiMessage.ProxyOnly)
            if (!auto) platform.requestNotifications()
            runCatching {
                val loaded = reload()
                if (!loaded || _proxies.value.main == null) {
                    autoActing = false
                    val reported = !loaded && Fs.exists(importer.pathOf(activeProfile?.id.orEmpty()))
                    if (!reported) _message.tryEmit(UiMessage.Failure(getString(Res.string.servers_load_failed)))
                    return@launch
                }
                startIfWanted(mine)
            }.onFailure {
                if (it is CancellationException) throw it
                _message.tryEmit(UiMessage.Failure(it.message))
            }
        }
    }

    private suspend fun startIfWanted(mine: Long): Boolean = lifecycleLock.withLock {
        currentCoroutineContext().ensureActive()
        if (mine != wantedSeq) return@withLock false
        platform.engine.start(tunnelOptions())
        true
    }

    fun reconnect() {
        scope.launch(Dispatchers.Default) {
            if (tunnel.value != TunnelState.On) return@launch
            runCatching {
                platform.engine.stop()
                platform.engine.awaitStopped()
            }
            connect()
        }
    }

    fun disconnect() {
        wantedSeq++
        connectJob?.cancel()
        scope.launch(Dispatchers.Default) { lifecycleLock.withLock { runCatching { platform.engine.stop() } } }
    }

    fun toggle() {
        when (tunnel.value) {
            TunnelState.On, TunnelState.Starting -> disconnect()
            else -> connect()
        }
    }

    fun pressToggle() {
        handUntil = nowMillis() + HAND_WINDOW
        toggle()
    }

    fun takeHandAction(): Boolean {
        val hand = nowMillis() < handUntil
        handUntil = 0
        return hand
    }

    fun tunnelOptions(): TunnelOptions {
        val s = _settings.value
        return TunnelOptions(s.splitMode, s.excludedApps, s.onlyApps, s.ipv6, s.stack, s.mtu, s.allowBypass, s.dnsHijackAll, s.notifEnabled, s.notifStopButton, s.notifLiveSpeed, s.notifPauseButton, s.notifShowServer, s.bypassLocal)
    }

    fun selectProxy(group: String, name: String) = selectPath(listOf(group), name)

    fun selectPath(groups: List<String>, name: String) {
        val chain = groups.zip(groups.drop(1) + name)
        val picks = chain.toMap()
        _proxies.update { snapshot ->
            snapshot.copy(groups = snapshot.groups.map { group ->
                picks[group.name]?.let { pick ->
                    group.copy(now = pick.ifEmpty { group.now }, fixed = if (group.autoPicks) pick else group.fixed)
                } ?: group
            })
        }
        scope.launch(Dispatchers.Default) {
            applyLock.withLock {
                chain.forEach { (group, pick) -> runCatching { core.select(group, pick) } }
                _settings.value.activeProfile?.let { id ->
                    _profiles.update { list ->
                        list.map { if (it.id == id) it.copy(selected = it.selected + picks) else it }
                    }
                }
            }

            if (tunnel.value == TunnelState.On) runCatching { core.closeConnections() }
            persistProfiles()
            refreshProxies()
        }
    }

    fun testAll() {
        if (_testing.value) {
            testJob?.cancel()
            testJob = null
            _testing.value = false
            _testingServers.update { names -> names.filterTo(mutableSetOf()) { singleTests.containsKey(it) } }
        } else {
            scope.launch(Dispatchers.Default) { runServerTests() }
        }
    }

    fun testServer(name: String) {
        if (name in _testingServers.value) {
            _testingServers.update { it - name }
            singleTests.remove(name)?.cancel()
            return
        }
        _testingServers.update { it + name }
        val url = _settings.value.testUrl
        val job = scope.launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
            val self = coroutineContext[Job]
            try {
                val value = measure(name, url)
                ensureActive()
                if (name in _testingServers.value) _delays.update { it + (name to value) }
            } finally {
                if (singleTests[name] === self) {
                    singleTests.remove(name)
                    _testingServers.update { it - name }
                }
            }
        }
        singleTests[name] = job
        job.start()
    }

    fun updateActiveProfile(transform: (Profile) -> Profile) {
        val id = _settings.value.activeProfile ?: return
        _profiles.update { list -> list.map { if (it.id == id) transform(it) else it } }
        persistProfiles()
    }

    fun manualServers(id: String): List<JsonObject> = runCatching {
        (Json.parseToJsonElement(readConfig(id)).jsonObject["proxies"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    }.getOrDefault(emptyList())

    suspend fun saveManualServer(server: ManualServer, replace: String? = null): ImportError? = saveManual(listOf(server.toProxy()), replace)

    suspend fun addManualServers(text: String): ImportError? {
        if (text.isBlank()) return ImportError.Empty
        val parsed = withContext(Dispatchers.Default) { runCatching { importer.parseServers(text) }.getOrNull() }
        if (parsed.isNullOrEmpty()) return ImportError.Unsupported
        return saveManual(parsed, null)
    }

    fun manualEditable(name: String): Boolean {
        val profile = _profiles.value.firstOrNull { it.manual } ?: return false
        return manualServers(profile.id).firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull == name }?.let(ManualServer::from) != null
    }

    private suspend fun saveManual(added: List<JsonObject>, replace: String?): ImportError? = withContext(Dispatchers.Default) {
        try {
            val existing = _profiles.value.firstOrNull { it.manual }
            val current = existing?.let { manualServers(it.id) }.orEmpty()
            val kept = if (replace == null) current else current.filterNot { it["name"]?.jsonPrimitive?.contentOrNull == replace }
            val profile = importer.importManual(existing, getString(Res.string.manual_profile), kept + added)
            if (existing == null) _profiles.update { it + profile } else _profiles.update { list -> list.map { if (it.id == profile.id) profile else it } }
            persistProfiles()
            if (_settings.value.activeProfile != profile.id) update({ it.copy(activeProfile = profile.id) })
            reloadAndRestart()
            null
        } catch (e: ImportException) {
            addLog(LogLine("error", "manual server: ${e.message}"))
            e.reason
        } catch (e: Exception) {
            addLog(LogLine("error", "manual server: ${e.message}"))
            ImportError.Unsupported
        }
    }

    suspend fun deleteManualServer(name: String) = withContext(Dispatchers.Default) {
        val profile = _profiles.value.firstOrNull { it.manual } ?: return@withContext
        val rest = manualServers(profile.id).filterNot { it["name"]?.jsonPrimitive?.contentOrNull == name }
        if (rest.isEmpty()) {
            delete(profile.id)
            return@withContext
        }
        runCatching { importer.importManual(profile, profile.name, rest) }.onSuccess { updated ->
            _profiles.update { list -> list.map { if (it.id == updated.id) updated.copy(chains = it.chains - name, pinned = it.pinned - name) else it } }
            persistProfiles()
            if (_settings.value.activeProfile == profile.id) reloadAndRestart()
        }
    }

    fun setChain(name: String, via: String?) {
        val id = _settings.value.activeProfile ?: return
        val chains = activeProfile?.chains.orEmpty()
        if (via != null && chainCreatesLoop(name, via, chains - name)) return
        _profiles.update { list ->
            list.map { if (it.id == id) it.copy(chains = if (via == null) it.chains - name else it.chains + (name to via)) else it }
        }
        persistProfiles()
        scope.launch(Dispatchers.Default) { reloadAndRestart() }
    }

    fun togglePin(name: String) {
        val id = _settings.value.activeProfile ?: return
        _profiles.update { list ->
            list.map { if (it.id == id) it.copy(pinned = if (name in it.pinned) it.pinned - name else it.pinned + name) else it }
        }
        persistProfiles()
    }

    private suspend fun switchIfServerDied() {
        val s = _settings.value
        val snapshot = _proxies.value
        val main = snapshot.main
        val groups = snapshot.groups.mapTo(mutableSetOf()) { it.name }
        if (!s.autoSwitch || tunnel.value != TunnelState.On || !platform.isInteractive || !platform.network.value.online || main == null || main.now in groups || main.now in BUILT_IN) {
            serverMisses = 0
            return
        }
        if (core.delay(main.now, s.testUrl) > 0) {
            serverMisses = 0
            return
        }

        if (++serverMisses < 2) return
        serverMisses = 0
        val candidates = main.members.filter { it != main.now && it !in groups && it !in BUILT_IN }
        val measured = coroutineScope {
            candidates.chunked(16).flatMap { chunk ->
                chunk.map { name -> async { name to runCatching { core.delay(name, s.testUrl) }.getOrDefault(-1) } }.awaitAll()
            }
        }

        val best = measured.filter { it.second > 0 }.minByOrNull { it.second }?.first ?: return
        addLog(LogLine("info", "auto switch: ${main.now} -> $best"))
        selectProxy(main.name, best)
        _message.tryEmit(UiMessage.Switched(best))
    }

    suspend fun errorText(raw: String?): String = if (_settings.value.detailedErrors && !raw.isNullOrBlank()) raw else getString(friendlyError(raw))

    fun setPingMethod(method: PingMethod) {
        update({ it.copy(pingMethod = method) })
        _delays.value = emptyMap()
    }

    private suspend fun measure(name: String, url: String): Int {
        if (_settings.value.pingMethod == PingMethod.Tcp) {
            val address = serverAddresses()[name]
            if (address != null) return pinger.ping(address, tunnel.value == TunnelState.On)
        }
        return runCatching { core.delay(name, url) }.getOrDefault(-1)
    }

    private fun serverAddresses(): Map<String, ServerAddress> {
        val id = _settings.value.activeProfile ?: return emptyMap()
        addressCache?.takeIf { it.first == id }?.let { return it.second }
        return findServerAddresses(readConfig(id)).also { addressCache = id to it }
    }

    suspend fun runServerTests() = withContext(Dispatchers.Default) {
        if (!_testing.compareAndSet(false, true)) return@withContext
        val self = coroutineContext[Job]
        testJob = self
        val url = _settings.value.testUrl
        val names = _proxies.value.nodes.keys.filter { it !in BUILT_IN }
        _testingServers.update { it + names }
        val fresh = java.util.concurrent.ConcurrentHashMap<String, Int>()
        fun publish() {
            val batch = fresh.toMap()
            if (batch.isEmpty()) return
            fresh.keys.removeAll(batch.keys)
            _delays.update { it + batch }
            _testingServers.update { it - batch.keys }
        }
        try {
            val publisher = launch {
                while (isActive) {
                    delay(300)
                    publish()
                }
            }
            coroutineScope {
                names.chunked(16).forEach { chunk ->
                    chunk.map { name ->
                        async {
                            if (name in _testingServers.value) {
                                val value = measure(name, url)
                                ensureActive()
                                if (name in _testingServers.value) fresh[name] = value
                            }
                        }
                    }.awaitAll()
                }
            }
            publisher.cancelAndJoin()
            refreshProxies()
        } finally {
            publish()

            if (testJob === self) {
                testJob = null
                _testing.value = false
                _testingServers.update { current -> current.filterTo(mutableSetOf()) { singleTests.containsKey(it) } }
            }
        }
    }

    suspend fun refreshProxies() {
        runCatching { core.proxies() }.onSuccess { _proxies.value = it }
    }

    suspend fun reloadAndRestart() = withContext(Dispatchers.Default) {
        restartLock.withLock {
            val wasOn = tunnel.value == TunnelState.On
            val mine = wantedSeq
            restarting = wasOn
            try {
                if (wasOn) {
                    runCatching {
                        platform.engine.stop()
                        platform.engine.awaitStopped()
                    }
                }
                val loaded = reload()
                if (wasOn && !loaded) {
                    if (!Fs.exists(importer.pathOf(activeProfile?.id.orEmpty()))) _message.tryEmit(UiMessage.Failure(getString(Res.string.servers_load_failed)))
                } else if (wasOn) {
                    val started = runCatching { startIfWanted(mine) }.getOrDefault(false)

                    if (started) withTimeoutOrNull(RESTART_TIMEOUT) { tunnel.first { it == TunnelState.On || it is TunnelState.Failed } }
                }
            } finally {
                restarting = false
            }
            if (pendingAuto && tunnel.value != TunnelState.Starting && tunnel.value != TunnelState.Stopping) {
                pendingAuto = false
                applyAutoRules(force = true)
            }
        }
    }

    private var delaysProfile: String? = null

    private suspend fun reload(): Boolean = applyLock.withLock {
        val profile = activeProfile
        if (profile == null) {
            _proxies.value = ProxySnapshot()
            _loadError.value = null
            return@withLock false
        }
        val path = importer.pathOf(profile.id)
        if (!Fs.exists(path)) {
            _proxies.value = ProxySnapshot()
            _loadError.value = getString(Res.string.servers_load_failed)
            return@withLock false
        }
        val base = core.profileBase(path)
        val mainGroup = runCatching { core.firstGroup(path) }.getOrNull()
        val settings = _settings.value
        val routing = profile.routing?.takeIf { profile.routingEnabled }?.toRules(mainGroup).orEmpty()
        val runtime = runtimeProfile(profile.id, path)
        var ownGeo = false
        suspend fun apply(rules: List<String>): String? {
            var result = core.setup(runtime, buildOverlay(base, settings, region, mainGroup, rules, ownGeo, platform.tunInConfig, platform.hasIdleOff).toString(), profile.selected, settings.testUrl, settings.skipCertVerify)

            if (result != null && !ownGeo && isGeoFileError(result)) {
                addLog(LogLine("warning", "subscription geodata skipped: $result"))
                ownGeo = true
                result = core.setup(runtime, buildOverlay(base, settings, region, mainGroup, rules, true, platform.tunInConfig, platform.hasIdleOff).toString(), profile.selected, settings.testUrl, settings.skipCertVerify)
            }
            return result
        }
        var rules = routing
        var error = apply(rules)
        while (error != null) {
            val broken = rules.firstOrNull { "[$it]" in error } ?: break
            addLog(LogLine("warning", "subscription rule skipped: $broken"))
            rules = rules - broken
            error = apply(rules)
        }

        if (error != null && rules.isNotEmpty()) {
            addLog(LogLine("warning", "subscription routing skipped: $error"))
            error = apply(emptyList())
        }
        if (error != null) {
            addLog(LogLine("error", error))
            _message.tryEmit(UiMessage.Failure(error))
            _loadError.value = if (settings.detailedErrors) error else getString(friendlyError(error))
            _proxies.value = ProxySnapshot()
            return@withLock false
        }
        _loadError.value = null
        addressCache = null
        pinger.forget()
        refreshProxies()
        val names = _proxies.value.nodes.keys
        _delays.update { old -> if (delaysProfile == profile.id) old.filterKeys { it in names } else emptyMap() }
        delaysProfile = profile.id

        if (settings.mode == RoutingMode.Global) {
            _proxies.value.main?.let { main -> runCatching { core.select("GLOBAL", main.name) } }
        }
        true
    }

    private fun autoUpdateIfNeeded() {
        val hours = _settings.value.autoUpdateHours
        if (hours <= 0) return

        _profiles.value.filter { profile ->
            profile.url != null && profile.updatedAt < nowMillis() - (profile.updateHours ?: hours) * 3_600_000L
        }.forEach { refreshSilently(it) }
    }

    private val importLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    private suspend fun importLocked(url: String, previous: Profile, client: String? = null, custom: String? = null): Profile =
        importLocks.getOrPut(previous.id) { Mutex() }.withLock { importer.import(url, previous, client, custom) }

    private val silentRefreshing = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private fun refreshSilently(profile: Profile) {
        val url = profile.url ?: return
        if (!silentRefreshing.add(profile.id)) return
        scope.launch(Dispatchers.Default) {
            try {
                runCatching { importLocked(url, profile) }.onSuccess { updated ->
                    mergeRefreshed(updated)
                    persistProfiles()
                    if (_settings.value.activeProfile == profile.id) reloadAndRestart()
                    syncMerged(profile.id)
                }
            } finally {
                silentRefreshing.remove(profile.id)
            }
        }
    }

    fun resetSettings() {
        val keep = _settings.value
        _settings.value = AppSettings(onboarded = true, activeProfile = keep.activeProfile)
        persistSettings()
        platform.cancelIdleAlarm()
        syncWatching()
        scope.launch(Dispatchers.Default) { reloadAndRestart() }
    }

    private fun <T> loadGuarded(path: String, fallback: T, parse: (String) -> T): T {
        if (!Fs.exists(path)) return fallback
        val text = runCatching { Fs.readText(path) }.getOrNull() ?: return fallback
        runCatching { parse(text) }.onSuccess { value ->
            Fs.writeText("$path.bak", text)
            return value
        }
        runCatching { Fs.writeText("$path.broken", text) }
        return runCatching { parse(Fs.readText("$path.bak")) }.getOrDefault(fallback)
    }

    private fun loadSettings(): AppSettings = loadGuarded(settingsPath, AppSettings()) { json.decodeFromString<AppSettings>(it) }

    private fun loadProfiles(): List<Profile> = loadGuarded(profilesPath, emptyList()) { text ->
        json.decodeFromString(ListSerializer(Profile.serializer()), text)
            .map { it.copy(total = normalizeTotal(it.total), expire = normalizeExpire(it.expire)) }
    }

    private fun persistSettings() = settingsDirty.update { it + 1 }

    private fun persistProfiles() = profilesDirty.update { it + 1 }

    companion object {
        val BUILT_IN = setOf("DIRECT", "REJECT", "REJECT-DROP", "PASS", "PASS-RULE", "COMPATIBLE")
        val BUILT_IN_TYPES = setOf("Direct", "Reject", "RejectDrop", "Pass", "PassRule", "Compatible", "Dns")
        private const val REFRESH_COOLDOWN = 3000L
        private const val AUTO_SETTLE = 2000L
        private const val RESTART_TIMEOUT = 15_000L
        private const val SWITCH_SETTLE = 600L
        private const val HAND_WINDOW = 20_000L
        private const val AUTO_SWITCH_EVERY = 60_000L
        private const val IDLE_TRAFFIC = 32 * 1024L
        private const val IDLE_RECHECK = 10 * 60_000L
        private const val MIN_HEALTH_INTERVAL = 600
        private const val LOG_LIMIT = 4L * 1024 * 1024
        private const val LOG_KEEP = 1024 * 1024
        private val QUIET_LOGS = setOf("info", "debug")
        private const val IDLE_RESUME_POLL = 15_000L
        private const val AUTO_UPDATE_CHECK = 60 * 60_000L
    }
}

data class ConnectionInfo(
    val id: String,
    val host: String,
    val network: String,
    val chain: String,
    val rule: String,
    val upload: Long,
    val download: Long,
    val start: String,
    val exit: String = "",
    val process: String = "",
    val startMillis: Long = 0,
    val rulePayload: String = "",
) {
    val direct get() = exit == "DIRECT"
    val blocked get() = exit.startsWith("REJECT")
}

data class ExitIp(val ip: String, val country: String?, val city: String?)

enum class RuleSource { Yours, Local, Ads, Region, Subscription, Default, Mode, Unknown }

data class SiteRoute(val host: String, val exit: String, val rule: String, val payload: String, val source: RuleSource, val error: String?) {
    val direct get() = exit == "DIRECT"
    val blocked get() = exit.startsWith("REJECT")
}

fun siteHost(input: String): String? {
    val text = input.trim().substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
    val host = text.substringAfterLast('@').let { if (it.startsWith('[')) it.substringBefore(']') + "]" else it.substringBefore(':') }
    return host.lowercase().trimEnd('.').takeIf { it.isNotEmpty() && it.none { c -> c.isWhitespace() || c == ',' } && ('.' in it || ':' in it) }
}

private const val DNS_KEEP = 400

private val IP_SOURCES = listOf("https://ipinfo.io/json", "https://api.ip.sb/geoip", "https://ipwho.is")

fun parseExitIp(body: String): ExitIp? {
    val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
    if (root["success"]?.jsonPrimitive?.booleanOrNull == false) return null
    val ip = root["ip"]?.jsonPrimitive?.contentOrNull ?: return null
    val country = (root["country_code"] ?: root["country"])?.jsonPrimitive?.contentOrNull?.uppercase()?.takeIf { it.length == 2 }
    return ExitIp(ip, country, root["city"]?.jsonPrimitive?.contentOrNull)
}

fun pickExitIp(answers: List<ExitIp>): ExitIp? {
    val first = answers.firstOrNull() ?: return null
    val sameIp = answers.filter { it.ip == first.ip }
    val country = sameIp.mapNotNull { it.country }.groupingBy { it }.eachCount().let { votes ->
        val best = votes.maxOfOrNull { it.value } ?: return@let null
        sameIp.mapNotNull { it.country }.first { votes[it] == best }
    }
    return first.copy(country = country, city = sameIp.firstOrNull { it.country == country }?.city ?: first.city)
}

suspend fun UiMessage.text(detailed: Boolean): String = when (this) {
    UiMessage.Updated -> getString(Res.string.servers_updated)
    UiMessage.UpdateFailed -> getString(Res.string.servers_update_failed)
    UiMessage.NoProfile -> getString(Res.string.msg_no_profile)
    UiMessage.PermissionDenied -> getString(Res.string.msg_denied)
    is UiMessage.Failure -> if (detailed && !detail.isNullOrBlank()) getString(Res.string.msg_failure, detail) else getString(friendlyError(detail))
    is UiMessage.Switched -> getString(Res.string.auto_switched, name)
    UiMessage.ProxyOnly -> getString(Res.string.tun_declined)
}

sealed interface UiMessage {
    data object Updated : UiMessage
    data object UpdateFailed : UiMessage
    data object NoProfile : UiMessage
    data object PermissionDenied : UiMessage
    data class Failure(val detail: String?) : UiMessage
    data class Switched(val name: String) : UiMessage
    data object ProxyOnly : UiMessage
}

val LOCAL_RULES = listOf(
    "DOMAIN,localhost,DIRECT",
    "DOMAIN-SUFFIX,localhost,DIRECT",
    "IP-CIDR,127.0.0.0/8,DIRECT,no-resolve",
    "IP-CIDR6,::1/128,DIRECT,no-resolve",
    "IP-CIDR,10.0.0.0/8,DIRECT,no-resolve",
    "IP-CIDR,172.16.0.0/12,DIRECT,no-resolve",
    "IP-CIDR,192.168.0.0/16,DIRECT,no-resolve",
    "IP-CIDR,169.254.0.0/16,DIRECT,no-resolve",
    "IP-CIDR6,fe80::/10,DIRECT,no-resolve",
)

fun isGeoFileError(error: String) = error.contains("geodata", true) || error.contains("GeoIP.dat", true)

private val LOG_URL = Regex("""(https?://[^/\s"']+)[^\s"']*""", RegexOption.IGNORE_CASE)
private val LOG_UUID = Regex("""\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b""")
private val LOG_TOKEN = Regex("""\b[A-Za-z0-9_\-]{24,}={0,2}""")

fun redactLog(text: String): String = text
    .replace(LOG_URL) { it.groupValues[1] + if (it.value.length > it.groupValues[1].length) "/…" else "" }
    .replace(LOG_UUID, "<uuid>")
    .replace(LOG_TOKEN, "<key>")

private val PROFILE_ID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

private val PROFILE_KEYS = setOf(
    "mode", "ipv6", "log-level", "unified-delay", "tcp-concurrent",
    "geodata-mode", "geodata-loader", "geosite-matcher", "geox-url", "geo-auto-update", "geo-update-interval",
    "global-client-fingerprint", "global-ua", "keep-alive-interval", "keep-alive-idle", "disable-keep-alive",
    "dns", "hosts", "use-hosts", "use-system-hosts", "sniffer", "proxy-providers", "rule-providers",
    "profile", "experimental", "etag-support",
)

fun buildOverlay(base: JsonObject, settings: AppSettings, region: RegionPack?, mainGroup: String?, routing: List<String> = emptyList(), ownGeo: Boolean = false, tun: Boolean = false, mobile: Boolean = false): JsonObject {
    val patched = base.filter { (key, value) -> value !is JsonNull && key in PROFILE_KEYS }.toMutableMap()
    (patched["dns"] as? JsonObject)?.let { dns -> patched["dns"] = JsonObject(dns - "listen") }
    if (ownGeo) patched["geodata-mode"] = JsonPrimitive(false)
    patched.remove("tun")
    if (tun) {
        patched["tun"] = buildJsonObject {
            put("enable", true)
            put("device", "Zefir")
            put("stack", "gvisor")
            put("mtu", settings.mtu)
            put("auto-route", true)
            put("auto-detect-interface", true)
            putJsonArray("dns-hijack") { add("any:53") }
        }
    }
    patched.remove("rule")
    patched["mode"] = JsonPrimitive(settings.mode.core)
    patched["log-level"] = JsonPrimitive(settings.logLevel)
    patched["ipv6"] = JsonPrimitive(settings.ipv6)
    patched["allow-lan"] = JsonPrimitive(false)
    patched["unified-delay"] = JsonPrimitive(settings.unifiedDelay)
    patched["tcp-concurrent"] = JsonPrimitive(settings.tcpConcurrent)
    patched["geo-auto-update"] = JsonPrimitive(false)
    if (mobile) patched["disable-keep-alive"] = JsonPrimitive(true)

    patched["external-controller"] = JsonPrimitive("")
    patched["port"] = JsonPrimitive(0)
    patched["socks-port"] = JsonPrimitive(0)
    patched["mixed-port"] = JsonPrimitive(settings.localProxyPort)

    val needsProcess = settings.rawRules.any { it.startsWith("PROCESS", ignoreCase = true) }
    patched["find-process-mode"] = JsonPrimitive(if (needsProcess) "always" else "off")

    val hasOwnDns = (base["dns"] as? JsonObject)?.get("enable")?.jsonPrimitive?.booleanOrNull == true
    if (settings.dnsMode != DnsMode.FromProfile || !hasOwnDns) patched["dns"] = dnsSection(settings)

    if (settings.sniffer) {
        patched["sniffer"] = buildJsonObject {
            put("enable", true)
            putJsonObject("sniff") {
                putJsonObject("TLS") { putJsonArray("ports") { add(443) } }
                putJsonObject("HTTP") { putJsonArray("ports") { add(80) } }
                putJsonObject("QUIC") { putJsonArray("ports") { add(443) } }
            }
        }
    }

    val rules = buildList {
        if (settings.mode == RoutingMode.Smart) {
            if (settings.bypassLocal) addAll(LOCAL_RULES)
            addAll(settings.rawRules)
            settings.rules.forEach { rule ->
                val target = when (rule.action) {
                    RuleAction.Direct -> "DIRECT"
                    RuleAction.Block -> "REJECT"
                    RuleAction.Proxy -> mainGroup ?: return@forEach
                }
                if (rule.value.any { it == ',' || it == '\n' || it == '\r' }) return@forEach
                add("DOMAIN-SUFFIX,${rule.value},$target")
            }
            if (settings.blockAds) add("GEOSITE,category-ads-all,REJECT")
            if (settings.regionDirect && region != null) addAll(region.directRules)

            addAll(routing)
        }
    }
    if (rules.isNotEmpty()) patched["rules"] = JsonArray(rules.map(::JsonPrimitive)) else patched.remove("rules")
    return JsonObject(patched)
}

private fun dnsSection(settings: AppSettings): JsonObject {
    val servers = settings.customDns.split(',', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        .takeIf { settings.dnsMode == DnsMode.Custom && it.isNotEmpty() }
        ?: listOf("https://1.1.1.1/dns-query", "https://8.8.8.8/dns-query")
    return buildJsonObject {
        put("enable", true)
        put("ipv6", settings.ipv6)
        put("enhanced-mode", if (settings.fakeIp) "fake-ip" else "redir-host")
        put("fake-ip-range", "198.18.0.1/16")
        putJsonArray("fake-ip-filter") {
            if (settings.bypassLocal) {
                add("localhost")
                add("+.localhost")
            }
            add("*.lan")
            add("*.local")
            add("+.msftconnecttest.com")
            add("+.msftncsi.com")
        }
        putJsonArray("default-nameserver") {
            add("system")
            add("8.8.8.8")
        }
        put("nameserver", JsonArray(servers.map(::JsonPrimitive)))
        putJsonArray("proxy-server-nameserver") {
            add("system")
            add("https://1.1.1.1/dns-query")
        }
    }
}

package dev.rooni.zefir.app

import dev.rooni.zefir.core.CoreApi
import dev.rooni.zefir.model.Profile
import dev.rooni.zefir.platform.DirectRoute
import dev.rooni.zefir.platform.Fs
import dev.rooni.zefir.platform.nowMillis
import dev.rooni.zefir.res.Res
import dev.rooni.zefir.res.servers_default_name
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.*
import org.jetbrains.compose.resources.getString
import java.net.URLDecoder
import java.util.UUID

enum class ImportError { Empty, Network, Unsupported, NoServers, Blocked }

class ImportException(val reason: ImportError, detail: String? = null) : Exception(detail ?: reason.name)

const val AUTO_GROUP = "Auto"
const val SERVERS_GROUP = "Servers"

class ProfileImporter(
    private val dataDir: String,
    private val core: CoreApi,
    private val deviceHeaders: () -> Map<String, String> = { emptyMap() },
    private val directRoute: () -> DirectRoute? = { null },
) {
    private val http = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 15_000
        }
    }

    private var direct: Pair<Any, HttpClient>? = null

    @Synchronized
    private fun directClient(): HttpClient? {
        val route = directRoute() ?: return null
        direct?.takeIf { it.first == route.key }?.let { return it.second }
        val client = HttpClient(OkHttp) {
            install(HttpTimeout) {
                requestTimeoutMillis = 20_000
                connectTimeoutMillis = 8_000
            }
            engine {
                preconfigured = okhttp3.OkHttpClient.Builder()
                    .socketFactory(route.sockets)
                    .dns(object : okhttp3.Dns {
                        override fun lookup(hostname: String) = route.lookup(hostname)
                    })
                    .build()
            }
        }
        direct?.second?.close()
        direct = route.key to client
        return client
    }

    fun pathOf(id: String) = "$dataDir/profiles/$id.yaml"

    suspend fun import(rawInput: String, previous: Profile? = null, client: String? = null, customClient: String? = null): Profile {
        val input = normalize(rawInput)
        if (input.isEmpty()) throw ImportException(ImportError.Empty)
        val id = previous?.id ?: UUID.randomUUID().toString()
        val isUrl = input.startsWith("http://", true) || input.startsWith("https://", true)

        var name = previous?.name
        val choice = client ?: previous?.client ?: SubscriptionClient.Auto.id
        val custom = customClient ?: previous?.customClient
        val notes = mutableMapOf<String, String>()
        val serverLinks = mutableMapOf<String, String>()
        val fetched = if (isUrl) {
            fetchWorking(input, choice, custom, previous, notes, serverLinks)
        } else {
            val text = plainBody(input)
            Fetched(null, text, toConfig(text, notes, serverLinks), null)
        }
        val response = fetched.response
        val text = fetched.text
        val config = fetched.config
        val headers = response?.headers
        val meta = parseSubscriptionMeta({ headers?.get(it) }, text)
        if (name == null) name = meta.title ?: response?.let { titleFrom(it) } ?: if (isUrl) hostOf(input) else meta.title
        val target = pathOf(id)
        val temp = "$target.new"
        Fs.writeText(temp, config)
        val error = runCatching { core.validate(temp) }.getOrElse { it.message }
        if (error != null) {
            Fs.delete(temp)
            throw ImportException(ImportError.Unsupported, error)
        }
        val count = core.profileCount(temp)
        val hasProviders = config.contains("proxy-providers")
        if (count == 0 && !hasProviders) {
            Fs.delete(temp)
            throw ImportException(ImportError.NoServers)
        }
        Fs.writeText(target, config)
        Fs.delete(temp)

        return Profile(
            id = id,
            name = name ?: if (isUrl) hostOf(input) else firstLinkName(input),

            url = if (isUrl) meta.newUrl ?: meta.newDomain?.let { replaceDomain(input, it) } ?: input else null,
            updatedAt = nowMillis(),
            used = meta.used,
            total = meta.total,
            expire = meta.expire,
            servers = count,
            selected = previous?.selected.orEmpty(),
            pinned = previous?.pinned.orEmpty(),
            hiddenTags = previous?.hiddenTags.orEmpty(),
            hideDead = previous?.hideDead ?: false,
            chains = previous?.chains.orEmpty(),
            updateHours = meta.updateHours,
            supportUrl = meta.supportUrl,
            webUrl = meta.webUrl,
            announce = meta.announce,
            info = meta.info,
            fallbackUrl = meta.fallbackUrl,
            routing = meta.routing,
            routingEnabled = previous?.routingEnabled ?: true,
            client = choice,
            customClient = custom,
            workingClient = fetched.workingClient,
            serverNotes = notes,
            serverLinks = serverLinks,
        )
    }

    suspend fun importManual(previous: Profile?, name: String, proxies: List<JsonObject>): Profile {
        if (proxies.isEmpty()) throw ImportException(ImportError.NoServers)
        val id = previous?.id ?: UUID.randomUUID().toString()
        val config = generatedConfig(proxies.map { GeneratedEntry(it) })
        val target = pathOf(id)
        val temp = "$target.new"
        Fs.writeText(temp, config)
        val error = runCatching { core.validate(temp) }.getOrElse { it.message }
        if (error != null) {
            Fs.delete(temp)
            throw ImportException(ImportError.Unsupported, error)
        }
        val count = core.profileCount(temp)
        Fs.writeText(target, config)
        Fs.delete(temp)
        return (previous ?: Profile(id = id, name = name)).copy(manual = true, servers = count, updatedAt = nowMillis())
    }

    suspend fun parseServers(raw: String): List<JsonObject> {
        val text = plainBody(raw)
        if (isWireguardConf(text)) return listOfNotNull(parseWireguardConf(text))
        if (isXrayJson(text)) {
            return parseXraySubscription(text).flatMap { if (it.balancer == null) listOf(it.proxy) else it.members }.filterNot(::isStubServer)
        }
        if (!text.contains("://")) return clashProxies(text).filterNot(::isStubServer)
        val links = decodeLines(text).map(::cleanShareLink)
        val (wireguard, others) = links.partition { isWireguardLink(it.link) }
        val decoded = if (others.isEmpty()) emptyList() else core.decodeLinks(others.map { it.link })
        return (decoded + wireguard.mapNotNull { parseWireguardLink(it.link) }).filterNot(::isStubServer)
    }

    suspend fun saveConfig(id: String, text: String): String? {
        val target = pathOf(id)
        val temp = "$target.new"
        Fs.writeText(temp, text)
        val error = runCatching { core.validate(temp) }.getOrElse { it.message }
        if (error == null) Fs.writeText(target, text)
        Fs.delete(temp)
        return error
    }

    private fun normalize(raw: String) = unwrapImportLink(raw)

    private fun replaceDomain(url: String, domain: String) =
        url.substringBefore("://") + "://" + domain + url.substringAfter("://").let { rest -> if ('/' in rest) "/" + rest.substringAfter('/') else "" }

    private class Fetched(val response: HttpResponse?, val text: String, val config: String, val workingClient: String?)

    private suspend fun fetchWorking(url: String, choice: String, custom: String?, previous: Profile?, notes: MutableMap<String, String>, serverLinks: MutableMap<String, String>): Fetched {
        val selected = SubscriptionClient.of(choice)
        val tries = when (selected) {
            SubscriptionClient.Auto -> {
                val last = previous?.workingClient?.let(SubscriptionClient::of)?.takeIf { it in SubscriptionClient.autoOrder }
                listOfNotNull(last) + SubscriptionClient.autoOrder.filter { it != last }
            }
            else -> listOf(selected)
        }
        var failure: ImportException? = null
        for (attempt in tries) {
            val userAgent = if (attempt == SubscriptionClient.Custom) custom?.trim()?.ifEmpty { null } ?: "clash.meta" else attempt.userAgent!!
            try {
                val response = try {
                    fetch(url, userAgent)
                } catch (e: ImportException) {
                    if (failure == null) failure = e

                    if (e.message?.startsWith("HTTP") != true) {
                        fetch(previous?.fallbackUrl ?: throw e, userAgent)
                    } else {
                        continue
                    }
                }
                val text = plainBody(response.bodyAsText())
                notes.clear()
                serverLinks.clear()
                return Fetched(response, text, toConfig(text, notes, serverLinks), attempt.id)
            } catch (e: ImportException) {
                if (e.reason == ImportError.Network) throw e
                failure = e
            }
        }
        throw failure ?: ImportException(ImportError.Blocked)
    }

    private suspend fun fetch(url: String, userAgent: String): HttpResponse {
        directClient()?.let { client ->
            runCatching { return request(client, url, userAgent) }
        }
        return request(http, url, userAgent)
    }

    private suspend fun request(client: HttpClient, url: String, userAgent: String): HttpResponse {
        val response = try {
            client.get(url) {
                header("User-Agent", userAgent)
                deviceHeaders().forEach { (name, value) -> header(name, value) }
            }
        } catch (e: Exception) {
            throw ImportException(ImportError.Network, e.message)
        }
        if (!response.status.isSuccess()) throw ImportException(ImportError.Network, "HTTP ${response.status.value}")
        return response
    }

    private fun plainBody(body: String): String {
        val text = body.trim().removePrefix("\uFEFF")
        if (text.contains("://") || looksLikeClash(text) || isXrayJson(text) || isWireguardConf(text)) return text
        return runCatching { decodeBase64(text) }.getOrDefault(text)
    }

    private suspend fun toConfig(text: String, notes: MutableMap<String, String>, serverLinks: MutableMap<String, String>): String {
        if (text.startsWith("<")) throw ImportException(ImportError.Blocked, "html page")
        if (looksLikeClash(text)) return text
        if (isWireguardConf(text)) return generatedConfig(listOf(GeneratedEntry(parseWireguardConf(text) ?: throw ImportException(ImportError.Unsupported))))
        if (isXrayJson(text)) {
            val parsed = parseXraySubscription(text)
            if (parsed.isEmpty()) throw ImportException(ImportError.Unsupported)
            val servers = parsed.mapNotNull { server ->
                if (server.balancer == null) server.takeUnless { isStubServer(it.proxy) }
                else server.copy(members = server.members.filterNot(::isStubServer)).takeIf { it.members.isNotEmpty() }
            }
            if (servers.isEmpty()) throw ImportException(ImportError.Blocked, "stub servers")
            servers.forEach { server ->
                if (server.description != null) notes[server.name] = server.description
            }
            return generatedConfig(servers.map { GeneratedEntry(it.proxy, it.members, it.balancer) })
        }
        val links = decodeLines(text).map(::cleanShareLink)
        links.forEach { link -> if (link.name != null && link.description != null) notes[link.name] = link.description }

        val (wireguard, others) = links.partition { isWireguardLink(it.link) }
        val decoded = if (others.isEmpty()) emptyList() else core.decodeLinks(others.map { it.link })
        val ownDecoded = wireguard.mapNotNull { link -> parseWireguardLink(link.link)?.also { proxy -> proxy["name"]?.jsonPrimitive?.contentOrNull?.let { serverLinks.putIfAbsent(it, link.link) } } }
        if (decoded.isEmpty() && ownDecoded.isEmpty()) throw ImportException(ImportError.Unsupported)

        if (decoded.size == others.size) {
            decoded.zip(others).forEach { (proxy, link) ->
                proxy["name"]?.jsonPrimitive?.contentOrNull?.let { serverLinks.putIfAbsent(it, link.link) }
            }
        }
        val proxies = (decoded + ownDecoded).filterNot(::isStubServer)
        if (proxies.isEmpty()) throw ImportException(ImportError.Blocked, "stub servers")
        return generatedConfig(proxies.map { GeneratedEntry(it) })
    }

    private fun looksLikeClash(text: String) =
        Regex("(?m)^(proxies|proxy-providers|proxy-groups)\\s*:").containsMatchIn(text)

    private fun decodeLines(text: String): List<String> =
        text.lines().map { it.trim() }.filter { it.contains("://") && !it.startsWith("#") && !it.startsWith("happ://", true) }

    private fun decodeBase64(text: String) = decodeBase64Text(text)

    private class GeneratedEntry(val proxy: JsonObject, val members: List<JsonObject> = emptyList(), val balancer: XrayBalancer? = null)

    private fun generatedConfig(entries: List<GeneratedEntry>): String {
        val seen = mutableSetOf(AUTO_GROUP, SERVERS_GROUP)
        fun unique(raw: String?): String {
            var name = raw?.ifBlank { null } ?: "Server"
            val base = name
            var n = 2
            while (!seen.add(name)) name = "$base ${n++}"
            return name
        }
        val proxies = mutableListOf<JsonObject>()
        val listed = mutableListOf<String>()
        val groups = mutableListOf<JsonObject>()
        entries.forEach { entry ->
            val rule = entry.balancer
            if (rule == null) {
                val name = unique(entry.proxy["name"]?.jsonPrimitive?.contentOrNull)
                proxies += JsonObject(entry.proxy + ("name" to JsonPrimitive(name)))
                listed += name
                return@forEach
            }
            val group = unique(entry.proxy["name"]?.jsonPrimitive?.contentOrNull)
            val inner = entry.members.map { member ->
                unique(member["name"]?.jsonPrimitive?.contentOrNull).also { proxies += JsonObject(member + ("name" to JsonPrimitive(it))) }
            }
            listed += group
            groups += buildJsonObject {
                put("name", group)
                put("type", rule.type)
                put("url", rule.url ?: "https://www.gstatic.com/generate_204")
                put("interval", 300)
                if (rule.type == "url-test") put("tolerance", 50)
                rule.strategy?.let { put("strategy", it) }
                put("proxies", JsonArray(inner.map(::JsonPrimitive)))
            }
        }
        val names = JsonArray(proxies.map { it["name"]!! })
        return buildJsonObject {
            put("proxies", JsonArray(proxies))
            putJsonArray("proxy-groups") {
                addJsonObject {
                    put("name", SERVERS_GROUP)
                    put("type", "select")
                    put("proxies", JsonArray((listOf(AUTO_GROUP) + listed).map(::JsonPrimitive)))
                }
                groups.forEach { add(it) }
                addJsonObject {
                    put("name", AUTO_GROUP)
                    put("type", "url-test")
                    put("url", "https://www.gstatic.com/generate_204")
                    put("interval", 300)
                    put("tolerance", 50)
                    put("proxies", names)
                }
            }
            putJsonArray("rules") { add("MATCH,$SERVERS_GROUP") }
        }.toString()
    }

    private fun titleFrom(response: HttpResponse): String? {
        response.headers["profile-title"]?.let { raw ->
            val value = raw.trim()
            if (value.startsWith("base64:")) {
                return runCatching { decodeBase64(value.removePrefix("base64:")) }.getOrNull()
            }
            if (value.isNotEmpty()) return value
        }
        val disposition = response.headers["content-disposition"] ?: return null
        val match = Regex("filename\\*?=(?:UTF-8'')?\"?([^\";]+)").find(disposition) ?: return null
        return runCatching { URLDecoder.decode(match.groupValues[1], "UTF-8") }.getOrNull()
            ?.removeSuffix(".yaml")?.removeSuffix(".yml")?.ifBlank { null }
    }

    private fun hostOf(url: String) = url.substringAfter("://").substringBefore('/').substringBefore(':')

    private suspend fun firstLinkName(text: String): String {
        val fallback = getString(Res.string.servers_default_name)
        if (isWireguardConf(text)) wireguardConfName(text)?.let { return it }
        val line = text.lineSequence().firstOrNull { it.contains('#') } ?: return fallback
        return runCatching { URLDecoder.decode(line.substringAfterLast('#'), "UTF-8") }.getOrDefault(fallback)
    }
}

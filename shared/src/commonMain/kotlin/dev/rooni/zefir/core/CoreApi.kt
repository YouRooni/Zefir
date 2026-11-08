package dev.rooni.zefir.core

import dev.rooni.zefir.model.*
import dev.rooni.zefir.platform.Fs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class CoreException(message: String) : Exception(message)

abstract class CoreTransport {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonElement>>()
    private val seq = AtomicLong()
    var onLog: ((LogLine) -> Unit)? = null
    @Volatile var onDns: ((DnsEntry) -> Unit)? = null

    protected abstract fun send(frame: String)

    suspend fun call(method: String, arguments: JsonElement = JsonNull): JsonElement {
        val id = seq.incrementAndGet().toString()
        val deferred = CompletableDeferred<JsonElement>()
        pending[id] = deferred
        try {
            send(buildJsonObject {
                put("id", id)
                put("method", method)
                put("arguments", arguments)
            }.toString())
            return withTimeoutOrNull(CALL_TIMEOUT) { deferred.await() } ?: throw CoreException("core timeout: $method")
        } finally {
            pending.remove(id)
        }
    }

    private companion object {
        const val CALL_TIMEOUT = 90_000L
    }

    protected fun failPending(reason: String) {
        pending.keys.toList().forEach { id -> pending.remove(id)?.completeExceptionally(CoreException(reason)) }
    }

    protected fun receive(data: ByteArray) {
        val root = runCatching { Json.parseToJsonElement(data.decodeToString()).jsonObject }.getOrNull() ?: return
        if (root["method"]?.jsonPrimitive?.contentOrNull == "message") {
            handleEvents(root["arguments"])
            return
        }
        val id = root["id"]?.jsonPrimitive?.contentOrNull ?: return
        val deferred = pending.remove(id) ?: return
        val error = root["error"]
        val file = root["file"]?.jsonPrimitive?.contentOrNull
        when {
            error != null && error !is JsonNull ->
                deferred.completeExceptionally(CoreException(error.jsonObject["message"]?.jsonPrimitive?.content ?: "core error"))
            file != null -> runCatching {
                val spilled = Json.parseToJsonElement(Fs.readText(file)).jsonObject
                Fs.delete(file)
                deferred.complete(spilled["result"] ?: JsonNull)
            }.onFailure { deferred.completeExceptionally(it) }
            else -> deferred.complete(root["result"] ?: JsonNull)
        }
    }

    protected fun handleEvents(events: JsonElement?) {
        val list = (events as? JsonArray) ?: return
        list.forEach { item ->
            val event = item as? JsonObject ?: return@forEach
            if (event["type"]?.jsonPrimitive?.contentOrNull == "log") {
                val data = event["data"] as? JsonObject ?: return@forEach
                onLog?.invoke(
                    LogLine(
                        (data["LogLevel"] ?: data["logLevel"])?.jsonPrimitive?.contentOrNull ?: "info",
                        (data["Payload"] ?: data["payload"])?.jsonPrimitive?.contentOrNull ?: "",
                    ),
                )
            } else if (event["type"]?.jsonPrimitive?.contentOrNull == "dns") {
                val listener = onDns ?: return@forEach
                val data = event["data"] as? JsonObject ?: return@forEach
                listener(parseDns(data) ?: return@forEach)
            }
        }
    }
}

fun parseDns(data: JsonObject): DnsEntry? {
    fun text(key: String) = data[key]?.jsonPrimitive?.contentOrNull.orEmpty()
    val domain = text("domain").ifEmpty { return null }
    return DnsEntry(
        domain = domain,
        type = text("type"),
        answers = (data["answers"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
        upstream = text("upstream"),
        cached = data["cached"]?.jsonPrimitive?.booleanOrNull == true,
        failed = text("error").isNotEmpty() || text("rcode").let { it.isNotEmpty() && it != "NOERROR" },
        delay = data["delay"]?.jsonPrimitive?.longOrNull ?: 0,
    )
}

data class DnsEntry(
    val domain: String,
    val type: String,
    val answers: List<String>,
    val upstream: String,
    val cached: Boolean,
    val failed: Boolean,
    val delay: Long,
    val time: Long = System.currentTimeMillis(),
    val id: Long = DnsIds.incrementAndGet(),
)

private val DnsIds = AtomicLong()

data class RouteProbe(val chains: List<String>, val rule: String, val payload: String, val error: String?)

private const val DELAY_TIMEOUT = 5000

class CoreApi(private val transport: CoreTransport) {
    fun onLog(listener: (LogLine) -> Unit) { transport.onLog = listener }

    fun onDns(listener: ((DnsEntry) -> Unit)?) { transport.onDns = listener }

    suspend fun probe(url: String): RouteProbe {
        val result = transport.call("probe", buildJsonObject {
            put("url", url)
            put("proxy-name", "")
            put("timeout", 8000)
            put("max-body", 1)
        }).jsonObject
        return RouteProbe(
            chains = (result["chains"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
            rule = result["rule"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            payload = result["rule-payload"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            error = result["error"]?.jsonPrimitive?.contentOrNull?.ifEmpty { null },
        )
    }

    suspend fun init(home: String, sdk: Int) {
        transport.call("initClash", buildJsonObject {
            put("home-dir", home)
            put("version", sdk)
        })
    }

    suspend fun startLog() { transport.call("startLog") }

    suspend fun validate(path: String): String? =
        transport.call("validateConfig", JsonPrimitive(path)).jsonPrimitive.contentOrNull?.ifEmpty { null }

    suspend fun profileBase(path: String): JsonObject {
        val result = transport.call("getProfileBase", JsonPrimitive(path)).jsonObject
        return result["config"] as? JsonObject ?: JsonObject(emptyMap())
    }

    suspend fun profileCount(path: String): Int {
        val result = transport.call("getProfileBase", JsonPrimitive(path)).jsonObject
        return result["proxies"]?.jsonPrimitive?.intOrNull ?: 0
    }

    suspend fun setup(path: String, overlay: String, selected: Map<String, String>, testUrl: String, skipCert: Boolean): String? {
        val result = transport.call("setupConfig", buildJsonObject {
            put("selected-map", JsonObject(selected.mapValues { JsonPrimitive(it.value) }))
            put("test-url", testUrl)
            put("skip-cert-verify", skipCert)
            putJsonObject("base") {
                put("path", path)
                put("overlay", overlay)
            }
        })
        return result.jsonPrimitive.contentOrNull?.ifEmpty { null }
    }

    suspend fun firstGroup(path: String): String? {
        val groups = transport.call("getProfileOutbounds", JsonPrimitive(path)).jsonObject["proxy-groups"] as? JsonArray
        return groups?.firstOrNull()?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
    }

    suspend fun decodeLinks(lines: List<String>): List<JsonObject> {
        val result = transport.call("decodeShareLinks", JsonArray(lines.map(::JsonPrimitive)))
        return result.jsonArray.flatMap { it.jsonArray }.map { it.jsonObject }
    }

    suspend fun proxies(): ProxySnapshot {
        val root = transport.call("getProxies").jsonObject
        val views = root["proxies"]?.jsonObject ?: return ProxySnapshot()
        val order = root["all"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        val nodes = mutableMapOf<String, ProxyNode>()
        val groups = mutableMapOf<String, ProxyGroup>()
        views.forEach { (name, value) ->
            val view = value.jsonObject
            val type = view["type"]?.jsonPrimitive?.contentOrNull ?: ""
            val members = view["all"] as? JsonArray
            if (members != null) {
                if (view["hidden"]?.jsonPrimitive?.booleanOrNull != true) {
                    groups[name] = ProxyGroup(
                        name = name,
                        type = type,
                        now = view["now"]?.jsonPrimitive?.contentOrNull ?: "",
                        members = members.mapNotNull { it.jsonPrimitive.contentOrNull },
                        fixed = view["fixed"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    )
                }
            } else {
                nodes[name] = ProxyNode(name, type)
            }
        }
        val sorted = order.mapNotNull { groups[it] } + groups.values.filter { it.name !in order }
        return ProxySnapshot(sorted, nodes)
    }

    suspend fun select(group: String, proxy: String) {
        transport.call("changeProxy", buildJsonObject {
            put("group-name", group)
            put("proxy-name", proxy)
        })
    }

    suspend fun delay(name: String, url: String): Int {
        val result = transport.call("asyncTestDelay", buildJsonObject {
            put("proxy-name", name)
            put("test-url", url)
            put("timeout", DELAY_TIMEOUT)
        }) as? JsonObject ?: return -1
        val value = result["value"]?.jsonPrimitive?.intOrNull ?: return -1
        return if (value > DELAY_TIMEOUT) -1 else value
    }

    suspend fun connections(): JsonArray {
        val root = transport.call("getConnections") as? JsonObject ?: return JsonArray(emptyList())
        return root["connections"] as? JsonArray ?: JsonArray(emptyList())
    }

    suspend fun closeConnection(id: String) { transport.call("closeConnection", JsonPrimitive(id)) }

    suspend fun closeConnections() { transport.call("closeConnections") }

    suspend fun outboundBody(proxy: String, url: String): String? {
        val result = transport.call("outboundIp", buildJsonObject {
            put("proxy-name", proxy)
            putJsonArray("urls") { add(url) }
            put("timeout", 8000)
        }) as? JsonObject ?: return null
        return result["body"]?.jsonPrimitive?.contentOrNull?.ifEmpty { null }
    }

    suspend fun serviceCheck(proxy: String): List<Pair<String, String>> {
        val result = transport.call("serviceCheck", buildJsonObject {
            put("proxy-name", proxy)
            putJsonArray("names") {}
            put("timeout", 8000)
        }) as? JsonArray ?: return emptyList()
        return result.mapNotNull { item ->
            val row = item as? JsonObject ?: return@mapNotNull null
            val name = row["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            name to (row["status"]?.jsonPrimitive?.contentOrNull ?: "failed")
        }
    }

    suspend fun speed(): Traffic = traffic("getTraffic")

    suspend fun totalTraffic(): Traffic = traffic("getTotalTraffic")

    private suspend fun traffic(method: String): Traffic {
        val result = transport.call(method, JsonPrimitive(false)).jsonObject
        return Traffic(
            result["up"]?.jsonPrimitive?.longOrNull ?: 0,
            result["down"]?.jsonPrimitive?.longOrNull ?: 0,
        )
    }
}

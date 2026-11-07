package dev.rooni.zefir.app

import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

enum class SpeedPhase { Ping, Download, Upload, Done }

data class SpeedState(
    val phase: SpeedPhase = SpeedPhase.Ping,
    val progress: Float = 0f,
    val live: Double = 0.0,
    val samples: List<Float> = emptyList(),
    val ping: Int? = null,
    val jitter: Int? = null,
    val loadedPing: Int? = null,
    val download: Double? = null,
    val upload: Double? = null,
    val used: Long = 0,
    val place: SpeedPlace? = null,
)

data class SpeedPlace(val city: String?, val colo: String?, val country: String?, val ip: String?, val isp: String?)

class SpeedTester(proxy: java.net.Proxy?) {
    private val client = OkHttpClient.Builder()
        .apply { if (proxy != null) proxy(proxy) }
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val used = AtomicLong()

    suspend fun run(update: (SpeedState) -> Unit): SpeedState = withContext(Dispatchers.IO) {
        var state = SpeedState()
        fun emit(next: SpeedState) {
            if (!isActive) return
            state = next.copy(used = used.get())
            update(state)
        }
        emit(state)

        val place = async { runCatching { meta() }.getOrNull() }
        val pings = mutableListOf<Int>()
        var failed = 0
        repeat(PING_COUNT + 1) { round ->
            ensureActive()
            val ms = pingOnce()
            if (ms == null) {
                if (++failed >= 3 && pings.isEmpty()) throw java.io.IOException("no ping")
                return@repeat
            }
            if (round > 0) pings += ms
            emit(state.copy(progress = (round + 1f) / (PING_COUNT + 1), ping = pings.median(), samples = pings.map { it.toFloat() }))
        }
        if (pings.isEmpty()) throw java.io.IOException("no ping")
        val jitter = pings.zipWithNext { a, b -> abs(a - b) }.average().takeIf { !it.isNaN() }?.toInt() ?: 0
        emit(state.copy(ping = pings.median(), jitter = jitter, place = place.await()))

        val loaded = mutableListOf<Int>()
        val download = measure(SpeedPhase.Download, { state }, ::emit) { counter, deadline ->
            launch {
                while (System.nanoTime() < deadline) {
                    pingOnce()?.let { synchronized(loaded) { loaded += it } }
                    delay(400)
                }
            }
            repeat(STREAMS) { launch { downloadLoop(counter, deadline) } }
        }
        emit(state.copy(download = download, loadedPing = synchronized(loaded) { loaded.median() }))

        val upload = measure(SpeedPhase.Upload, { state }, ::emit) { counter, deadline ->
            repeat(STREAMS) { launch { uploadLoop(counter, deadline) } }
        }
        emit(state.copy(upload = upload, phase = SpeedPhase.Done, progress = 1f, live = 0.0))
        state
    }

    private suspend fun measure(
        phase: SpeedPhase,
        current: () -> SpeedState,
        emit: (SpeedState) -> Unit,
        work: CoroutineScope.(AtomicLong, Long) -> Unit,
    ): Double = coroutineScope {
        val counter = AtomicLong()
        val start = System.nanoTime()
        val deadline = start + PHASE_NANOS
        val samples = mutableListOf<Float>()
        emit(current().copy(phase = phase, progress = 0f, live = 0.0, samples = emptyList()))
        val job = launch { work(counter, deadline) }
        var lastBytes = 0L
        var lastTime = start
        var steadyBytes = 0L
        var steadyStart = 0L
        while (System.nanoTime() < deadline && (counter.get() < MAX_BYTES)) {
            delay(TICK_MILLIS)
            val now = System.nanoTime()
            val bytes = counter.get()
            val mbps = (bytes - lastBytes) * 8.0 / ((now - lastTime) / 1e9) / 1e6
            lastBytes = bytes
            lastTime = now
            if (now - start > WARMUP_NANOS && steadyStart == 0L) {
                steadyStart = now
                steadyBytes = bytes
            }
            samples += mbps.toFloat()
            val smooth = samples.takeLast(4).average()
            emit(current().copy(phase = phase, progress = ((now - start).toFloat() / PHASE_NANOS).coerceAtMost(1f), live = smooth, samples = samples.toList()))
            if (job.isCompleted) break
        }
        job.cancelAndJoin()
        val end = System.nanoTime()
        val total = counter.get()
        if (steadyStart == 0L || end - steadyStart < 1_000_000_000L) {
            total * 8.0 / ((end - start) / 1e9) / 1e6
        } else {
            (total - steadyBytes) * 8.0 / ((end - steadyStart) / 1e9) / 1e6
        }
    }

    private suspend fun aliveUntil(deadline: Long): () -> Boolean {
        val job = currentCoroutineContext()[Job]
        return { System.nanoTime() < deadline && job?.isActive != false }
    }

    private suspend fun downloadLoop(counter: AtomicLong, deadline: Long) {
        val buffer = ByteArray(64 * 1024)
        val alive = aliveUntil(deadline)
        while (alive()) {
            val call = client.newCall(Request.Builder().url("$BASE/__down?bytes=$CHUNK").build())
            try {
                call.execute().use { response ->
                    val body = response.body.byteStream()
                    while (alive()) {
                        val read = body.read(buffer)
                        if (read < 0) break
                        counter.addAndGet(read.toLong())
                        used.addAndGet(read.toLong())
                    }
                }
            } catch (e: java.io.IOException) {
                if (!alive()) return
                delay(200)
            } finally {
                call.cancel()
            }
        }
    }

    private suspend fun uploadLoop(counter: AtomicLong, deadline: Long) {
        val chunk = ByteArray(64 * 1024) { (it * 31).toByte() }
        val alive = aliveUntil(deadline)
        while (alive()) {
            val body = object : RequestBody() {
                override fun contentType() = "application/octet-stream".toMediaType()
                override fun contentLength() = CHUNK.toLong()
                override fun writeTo(sink: BufferedSink) {
                    var left = CHUNK.toLong()
                    while (left > 0 && alive()) {
                        val size = minOf(left, chunk.size.toLong()).toInt()
                        sink.write(chunk, 0, size)
                        sink.flush()
                        counter.addAndGet(size.toLong())
                        used.addAndGet(size.toLong())
                        left -= size
                    }
                    if (left > 0) throw java.io.IOException("time is up")
                }
            }
            val call = client.newCall(Request.Builder().url("$BASE/__up").post(body).build())
            try {
                call.execute().close()
            } catch (e: java.io.IOException) {
                if (!alive()) return
                delay(200)
            } finally {
                call.cancel()
            }
        }
    }

    private fun pingOnce(): Int? = runCatching {
        val start = System.nanoTime()
        client.newCall(Request.Builder().url("$BASE/__down?bytes=0").build()).execute().use { response ->
            val total = (System.nanoTime() - start) / 1e6
            val server = response.header("Server-Timing")?.let { Regex("dur=([0-9.]+)").find(it)?.groupValues?.get(1)?.toDoubleOrNull() } ?: 0.0
            (total - server).toInt().coerceAtLeast(1)
        }
    }.getOrNull()

    private fun meta(): SpeedPlace {
        val text = client.newCall(Request.Builder().url("$BASE/meta").build()).execute().use { it.body.string() }
        val root = Json.parseToJsonElement(text).jsonObject
        fun field(name: String) = root[name]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        return SpeedPlace(field("city"), field("colo"), field("country"), field("clientIp"), field("asOrganization"))
    }

    private fun List<Int>.median(): Int? = if (isEmpty()) null else sorted()[size / 2]

    private companion object {
        const val BASE = "https://speed.cloudflare.com"
        const val PING_COUNT = 10
        const val STREAMS = 4
        const val CHUNK = 25_000_000
        const val MAX_BYTES = 400_000_000L
        const val TICK_MILLIS = 200L
        const val PHASE_NANOS = 9_000_000_000L
        const val WARMUP_NANOS = 2_000_000_000L
    }
}

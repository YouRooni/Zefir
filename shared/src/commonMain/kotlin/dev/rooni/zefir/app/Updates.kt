package dev.rooni.zefir.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

sealed interface UpdateCheck {
    data object Idle : UpdateCheck
    data object Checking : UpdateCheck
    data object Latest : UpdateCheck
    data class Available(val version: String, val url: String) : UpdateCheck
    data object Failed : UpdateCheck
}

object Updates {
    const val PLAY_URL = "https://play.google.com/store/apps/details?id=dev.rooni.zefir"
    private const val RELEASES = "https://api.github.com/repos/YouRooni/Zefir/releases?per_page=10"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun check(current: String): UpdateCheck = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(RELEASES).header("Accept", "application/vnd.github+json").build()
            val text = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching UpdateCheck.Failed
                response.body.string()
            }
            val newest = Json.parseToJsonElement(text).jsonArray
                .map { it.jsonObject }
                .filter { it["draft"]?.jsonPrimitive?.booleanOrNull != true }
                .mapNotNull { release ->
                    val tag = release["tag_name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val url = release["html_url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    tag.removePrefix("v") to url
                }
                .maxWithOrNull { a, b -> compareVersions(a.first, b.first) }
                ?: return@runCatching UpdateCheck.Latest
            if (compareVersions(newest.first, current) > 0) UpdateCheck.Available(newest.first, newest.second) else UpdateCheck.Latest
        }.getOrDefault(UpdateCheck.Failed)
    }

    fun compareVersions(a: String, b: String): Int {
        fun split(version: String): Pair<List<Int>, String?> {
            val main = version.substringBefore('-')
            val pre = version.substringAfter('-', "").ifEmpty { null }
            return main.split('.').map { it.toIntOrNull() ?: 0 } to pre
        }
        val (left, leftPre) = split(a)
        val (right, rightPre) = split(b)
        for (index in 0 until maxOf(left.size, right.size)) {
            val diff = left.getOrElse(index) { 0 } - right.getOrElse(index) { 0 }
            if (diff != 0) return diff
        }
        return when {
            leftPre == rightPre -> 0
            leftPre == null -> 1
            rightPre == null -> -1
            else -> comparePre(leftPre, rightPre)
        }
    }

    private fun comparePre(a: String, b: String): Int {
        val left = a.split('.')
        val right = b.split('.')
        for (index in 0 until minOf(left.size, right.size)) {
            val l = left[index].toIntOrNull()
            val r = right[index].toIntOrNull()
            val diff = when {
                l != null && r != null -> l.compareTo(r)
                l != null -> -1
                r != null -> 1
                else -> left[index].compareTo(right[index])
            }
            if (diff != 0) return diff
        }
        return left.size.compareTo(right.size)
    }
}

package dev.rooni.zefir.platform

import dev.rooni.zefir.core.CoreApi
import dev.rooni.zefir.core.CoreTransport
import dev.rooni.vpncore.mobile.Mobile
import dev.rooni.vpncore.mobile.ResultHandler
import dev.rooni.vpncore.mobile.TunHandler
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

object AndroidCore : CoreTransport() {
    val api = CoreApi(this)

    init {
        Mobile.setEventListener(ResultHandler { data ->
            val text = data?.decodeToString() ?: return@ResultHandler
            val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return@ResultHandler
            handleEvents(root["arguments"]?.let { runCatching { it.jsonArray }.getOrNull() })
        })
    }

    override fun send(frame: String) {
        Mobile.invokeMethod(frame, ResultHandler { result -> result?.let(::receive) })
    }

    fun startTun(fd: Int, handler: TunHandler, stack: String, address: String, dns: String, options: String) =
        Mobile.startTun(fd, handler, stack, address, dns, options)

    fun stopTun() = Mobile.stopTun()

    fun updateDns(servers: String) = Mobile.updateDns(servers)

    fun resetNetwork() = Mobile.resetNetwork()
}

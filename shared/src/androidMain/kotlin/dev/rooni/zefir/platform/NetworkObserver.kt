package dev.rooni.zefir.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.os.Build
import dev.rooni.zefir.model.NetworkState
import android.os.Handler
import android.os.HandlerThread
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

object NetworkObserver {
    private val networks = ConcurrentHashMap<Network, List<InetAddress>>()
    private lateinit var connectivity: ConnectivityManager
    private val handler by lazy { Handler(HandlerThread("network-observer").apply { start() }.looper) }
    private var current = ""
    private var currentNetwork: Network? = null
    private var started = false
    private var listener: ((NetworkState) -> Unit)? = null
    private lateinit var appContext: Context

    private val update = Runnable(::apply)

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            networks.putIfAbsent(network, emptyList())
            schedule()
        }

        override fun onLost(network: Network) {
            networks.remove(network)
            schedule()
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            networks[network] = linkProperties.dnsServers
            schedule()
        }
    }

    private fun schedule() {
        handler.removeCallbacks(update)
        handler.postDelayed(update, 300)
    }

    @Synchronized
    fun start(context: Context, onChange: (NetworkState) -> Unit) {
        if (started) return
        appContext = context.applicationContext
        listener = onChange
        connectivity = context.getSystemService(ConnectivityManager::class.java)
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { connectivity.registerNetworkCallback(request, callback) }.onSuccess { started = true }
    }

    fun setSsidWatching(on: Boolean) {
        handler.post { applySsidWatching(on) }
    }

    private fun applySsidWatching(on: Boolean) {
        if (!started || ssidWanted == on) return
        ssidWanted = on
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            schedule()
            return
        }
        if (on) {
            runCatching { connectivity.registerNetworkCallback(wifiRequest, wifiPresence, handler) }
        } else {
            runCatching { connectivity.unregisterNetworkCallback(wifiPresence) }
            disarm()
            lastWifi = null
            lastSsid = null
        }
        schedule()
    }

    fun refresh() {
        if (!started) return
        handler.post {
            if (ssidWanted && lastWifi != null && lastSsid == null) arm()
            schedule()
        }
    }

    @Volatile
    private var ssidWanted = false
    private var armed = false
    private val wifiRequest by lazy { NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build() }
    private val disarmLater = Runnable(::disarm)

    private fun arm() {
        if (armed || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        armed = runCatching { connectivity.registerNetworkCallback(wifiRequest, wifiNames, handler) }.isSuccess
        handler.postDelayed(disarmLater, 5000)
    }

    private fun disarm() {
        handler.removeCallbacks(disarmLater)
        if (!armed) return
        armed = false
        runCatching { connectivity.unregisterNetworkCallback(wifiNames) }
    }

    private val wifiPresence = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (network == lastWifi) return
            lastWifi = network
            lastSsid = null
            arm()
            schedule()
        }

        override fun onLost(network: Network) {
            if (network != lastWifi) return
            lastWifi = null
            lastSsid = null
            schedule()
        }
    }

    private val wifiNames by lazy {
        object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val info = caps.transportInfo as? WifiInfo ?: return
                if (network == lastWifi) lastSsid = cleanSsid(info.ssid)
                handler.post(::disarm)
                schedule()
            }
        }
    }

    @Volatile
    private var lastWifi: Network? = null

    private fun cleanSsid(raw: String?) = raw?.trim('"')?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" }

    @Suppress("DEPRECATION")
    private fun legacySsid() = cleanSsid(appContext.getSystemService(android.net.wifi.WifiManager::class.java)?.connectionInfo?.ssid)

    @Volatile
    private var lastSsid: String? = null

    private fun rank(network: Network): Int {
        val caps = connectivity.getNetworkCapabilities(network) ?: return 100
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> 90
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 0
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 1
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 4
            else -> 20
        }
    }

    @Synchronized
    private fun apply() {
        val best = networks.keys.minByOrNull(::rank)
        val servers = best?.let { networks[it] }.orEmpty().joinToString(",") { address ->
            val host = address.hostAddress?.substringBefore('%').orEmpty()
            if (address is Inet6Address) "[$host]:53" else "$host:53"
        }
        if (servers != current) {
            current = servers
            AndroidCore.updateDns(servers)
        }
        val onWifi = best?.let { connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } == true
        val ssid = if (!onWifi || !ssidWanted) null else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) lastSsid else legacySsid()
        listener?.invoke(NetworkState(onWifi, ssid, best != null, known = true))
        if (best != currentNetwork) {
            if (currentNetwork != null) AndroidCore.resetNetwork()
            currentNetwork = best
        }
    }
}

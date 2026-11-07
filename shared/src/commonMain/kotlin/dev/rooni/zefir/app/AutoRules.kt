package dev.rooni.zefir.app

import dev.rooni.zefir.model.NetworkState
import dev.rooni.zefir.model.TunnelState

enum class AutoAction { None, Stop, Start }

fun autoPlace(network: NetworkState): String? = when {
    !network.known || !network.online -> null
    !network.wifi -> "other"
    network.ssid == null -> null
    else -> "wifi:${network.ssid}"
}

fun isTrustedPlace(place: String, trusted: Set<String>) = place.startsWith("wifi:") && place.removePrefix("wifi:") in trusted

fun decideAuto(
    place: String,
    trusted: Set<String>,
    tunnel: TunnelState,
    connectElsewhere: Boolean,
    autoStopped: Boolean,
    pausedOn: String?,
    changed: Boolean,
    force: Boolean,
): AutoAction {
    if (!changed && !force) return AutoAction.None
    if (isTrustedPlace(place, trusted)) return if (tunnel == TunnelState.On) AutoAction.Stop else AutoAction.None
    val stopped = tunnel == TunnelState.Off || tunnel is TunnelState.Failed
    return if (stopped && (autoStopped || connectElsewhere) && pausedOn != place) AutoAction.Start else AutoAction.None
}

package dev.rooni.zefir.app

import dev.rooni.zefir.model.NetworkState
import dev.rooni.zefir.model.TunnelState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AutoRulesTest {
    private val home = setOf("Home")

    private fun decide(
        place: String,
        tunnel: TunnelState,
        elsewhere: Boolean = true,
        autoStopped: Boolean = false,
        pausedOn: String? = null,
        changed: Boolean = true,
        force: Boolean = false,
    ) = decideAuto(place, home, tunnel, elsewhere, autoStopped, pausedOn, changed, force)

    @Test
    fun unknownNetworkIsNeverActedOn() {
        assertNull(autoPlace(NetworkState()))
        assertNull(autoPlace(NetworkState(wifi = true, ssid = null, online = true, known = true)))
        assertNull(autoPlace(NetworkState(wifi = true, ssid = "Home", online = false, known = true)))
    }

    @Test
    fun placesAreNamed() {
        assertEquals("wifi:Home", autoPlace(NetworkState(wifi = true, ssid = "Home", online = true, known = true)))
        assertEquals("other", autoPlace(NetworkState(wifi = false, ssid = null, online = true, known = true)))
    }

    @Test
    fun trustedNetworkStopsVpn() {
        assertEquals(AutoAction.Stop, decide("wifi:Home", TunnelState.On))
        assertEquals(AutoAction.None, decide("wifi:Home", TunnelState.Off))
    }

    @Test
    fun otherNetworkStartsVpn() {
        assertEquals(AutoAction.Start, decide("other", TunnelState.Off))
        assertEquals(AutoAction.Start, decide("wifi:Cafe", TunnelState.Off))
        assertEquals(AutoAction.None, decide("other", TunnelState.On))
    }

    @Test
    fun failedTunnelIsRetriedOnNextNetwork() {
        assertEquals(AutoAction.Start, decide("other", TunnelState.Failed("x")))
    }

    @Test
    fun connectElsewhereOffOnlyRestoresWhatItStopped() {
        assertEquals(AutoAction.None, decide("other", TunnelState.Off, elsewhere = false))
        assertEquals(AutoAction.Start, decide("other", TunnelState.Off, elsewhere = false, autoStopped = true))
    }

    @Test
    fun manualChoiceLivesUntilNetworkChanges() {
        assertEquals(AutoAction.None, decide("wifi:Cafe", TunnelState.Off, pausedOn = "wifi:Cafe"))
        assertEquals(AutoAction.Start, decide("other", TunnelState.Off, pausedOn = "wifi:Cafe"))

        assertEquals(AutoAction.None, decide("wifi:Home", TunnelState.On, changed = false))
    }

    @Test
    fun forceReevaluatesWithoutNetworkChange() {
        assertEquals(AutoAction.Stop, decide("wifi:Home", TunnelState.On, changed = false, force = true))
        assertEquals(AutoAction.None, decide("wifi:Cafe", TunnelState.Off, pausedOn = "wifi:Cafe", changed = false, force = true))
    }

    @Test
    fun busyTunnelGetsNoAction() {
        assertEquals(AutoAction.None, decide("wifi:Home", TunnelState.Starting))
        assertEquals(AutoAction.None, decide("other", TunnelState.Stopping))
    }
}

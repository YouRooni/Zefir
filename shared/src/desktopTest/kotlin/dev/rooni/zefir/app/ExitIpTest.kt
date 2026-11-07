package dev.rooni.zefir.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExitIpTest {
    @Test
    fun readsEverySource() {
        assertEquals("DE", parseExitIp("""{"ip":"1.2.3.4","country":"DE","city":"Berlin"}""")?.country)
        assertEquals("NL", parseExitIp("""{"ip":"1.2.3.4","country":"Netherlands","country_code":"NL"}""")?.country)
        assertNull(parseExitIp("""{"ip":"1.2.3.4","success":false}"""))
    }

    @Test
    fun majorityWinsCountry() {
        val picked = pickExitIp(
            listOf(
                ExitIp("1.2.3.4", "US", null),
                ExitIp("1.2.3.4", "DE", "Frankfurt"),
                ExitIp("1.2.3.4", "DE", "Frankfurt"),
            ),
        )
        assertEquals("DE", picked?.country)
        assertEquals("Frankfurt", picked?.city)
    }

    @Test
    fun firstSourceBreaksTie() {
        assertEquals("FI", pickExitIp(listOf(ExitIp("1.1.1.1", "FI", null), ExitIp("1.1.1.1", "SE", null)))?.country)
    }
}

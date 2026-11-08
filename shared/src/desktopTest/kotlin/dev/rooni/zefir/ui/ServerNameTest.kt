package dev.rooni.zefir.ui

import dev.rooni.zefir.ui.screens.ServerTag
import dev.rooni.zefir.ui.screens.parseServerName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerNameTest {
    @Test
    fun wirelessWithoutFlag() {
        val name = parseServerName("\uD83D\uDEDC Обход")
        assertNull(name.flag)
        assertTrue(ServerTag.Wireless in name.tags)
        assertEquals("Обход", name.label)
    }

    @Test
    fun wirelessWithFlag() {
        val name = parseServerName("\uD83C\uDDF7\uD83C\uDDFA \uD83D\uDEDC Москва")
        assertEquals("\uD83C\uDDF7\uD83C\uDDFA", name.flag)
        assertTrue(ServerTag.Wireless in name.tags)
        assertEquals("Москва", name.label)
    }

    @Test
    fun manyTagsBecomeIcons() {
        val name = parseServerName("🇩🇪 ⚡️ Frankfurt 🎮")
        assertEquals("🇩🇪", name.flag)
        assertEquals(listOf(ServerTag.Fast, ServerTag.Gaming), name.tags)
        assertEquals("Frankfurt", name.label)
    }

    @Test
    fun plainNameUntouched() {
        val name = parseServerName("Server 1")
        assertNull(name.flag)
        assertEquals("Server 1", name.label)
    }
}

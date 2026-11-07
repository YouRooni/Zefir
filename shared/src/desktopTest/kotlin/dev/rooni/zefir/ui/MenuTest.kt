package dev.rooni.zefir.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import dev.rooni.zefir.platform.Platform
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class MenuTest {
    private val platform = Proxy.newProxyInstance(Platform::class.java.classLoader, arrayOf(Platform::class.java)) { _, _, _ -> null } as Platform

    @Test
    fun tapOpensInsideClickableCardAndCardStaysQuiet() = runComposeUiTest {
        var card = 0
        var picked = 0
        setContent {
            CompositionLocalProvider(LocalPlatform provides platform) {
                MaterialTheme {
                    Box(Modifier.size(300.dp).clickable { card++ }) {
                        MenuButton(listOf(MenuEntry("Пункт") { picked++ }), description = "more")
                    }
                }
            }
        }

        val button = onNodeWithContentDescription("more", useUnmergedTree = true)
        button.performTouchInput { down(center) }
        mainClock.advanceTimeBy(20)
        button.performTouchInput { up() }
        waitForIdle()
        onNodeWithText("Пункт").assertIsDisplayed()
        assertEquals(0, card)
        onNodeWithText("Пункт").performClick()
        waitForIdle()
        assertEquals(1, picked)
        onNodeWithText("Пункт").assertDoesNotExist()
    }

    @Test
    fun holdOpensAndReleaseKeepsMenu() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalPlatform provides platform) {
                MaterialTheme { MenuButton(listOf(MenuEntry("Пункт") {}), description = "more") }
            }
        }
        onNodeWithContentDescription("more").performTouchInput { down(center) }
        mainClock.advanceTimeBy(HOLD_MILLIS + 100)
        waitForIdle()
        onNodeWithText("Пункт").assertIsDisplayed()
        onNodeWithContentDescription("more").performTouchInput { up() }
        waitForIdle()
        onNodeWithText("Пункт").assertIsDisplayed()
    }

    @Test
    fun holdOnButtonInsideHoldableRowOpensOnlyButtonMenu() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalPlatform provides platform) {
                MaterialTheme {
                    val menu = rememberMenuState()
                    Box(Modifier.size(300.dp).menuGesture(menu, platform, 300).clickable { }) {
                        MenuForState(menu, listOf(MenuEntry("Строка") {}))
                        MenuButton(listOf(MenuEntry("Кнопка") {}), description = "more")
                    }
                }
            }
        }
        val button = onNodeWithContentDescription("more", useUnmergedTree = true)
        button.performTouchInput { down(center) }
        mainClock.advanceTimeBy(500)
        waitForIdle()
        onNodeWithText("Кнопка").assertIsDisplayed()
        onNodeWithText("Строка").assertDoesNotExist()
    }

    @Test
    fun rowHoldOpensAndDoesNotClick() = runComposeUiTest {
        var clicks = 0
        setContent {
            CompositionLocalProvider(LocalPlatform provides platform) {
                MaterialTheme {
                    val menu = rememberMenuState()
                    Box(Modifier.size(200.dp).menuGesture(menu, platform, 300).clickable { clicks++ }) {
                        Text("row")
                        MenuForState(menu, listOf(MenuEntry("Пункт") {}))
                    }
                }
            }
        }
        onNodeWithText("row").performTouchInput { down(center) }
        mainClock.advanceTimeBy(400)
        waitForIdle()
        onNodeWithText("Пункт").assertIsDisplayed()
        onNodeWithText("row").performTouchInput { up() }
        waitForIdle()
        assertEquals(0, clicks)
    }

    @Test
    fun rowTapClicksAndKeepsMenuClosed() = runComposeUiTest {
        var clicks = 0
        setContent {
            CompositionLocalProvider(LocalPlatform provides platform) {
                MaterialTheme {
                    val menu = rememberMenuState()
                    Box(Modifier.size(200.dp).menuGesture(menu, platform, 300).clickable { clicks++ }) {
                        Text("row")
                        MenuForState(menu, listOf(MenuEntry("Пункт") {}))
                    }
                }
            }
        }
        onNodeWithText("row").performTouchInput { click(Offset(10f, 10f)) }
        waitForIdle()
        assertEquals(1, clicks)
        onNodeWithText("Пункт").assertDoesNotExist()
    }
}

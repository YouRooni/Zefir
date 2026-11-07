package dev.rooni.zefir.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import dev.rooni.zefir.model.AppSettings
import dev.rooni.zefir.model.ThemeMode
import dev.rooni.zefir.platform.Platform

val AccentChoices = listOf(
    0xFFC62828, 0xFFE64A19, 0xFFB5602A, 0xFF8D6E00, 0xFF5F6F2A, 0xFF2F5D50,
    0xFF00796B, 0xFF0277BD, 0xFF3F5AA9, 0xFF5E35B1, 0xFF8E24AA, 0xFFAD1457,
)

val MultiPalettes = listOf(
    listOf(0xFFE53935, 0xFFFB8C00, 0xFF43A047, 0xFF1E88E5, 0xFF8E24AA),
    listOf(0xFFC62828, 0xFFEF6C00, 0xFFAD1457, 0xFFF9A825, 0xFF6D4C41),
    listOf(0xFF0277BD, 0xFF00897B, 0xFF5E35B1, 0xFF2E7D32, 0xFF00ACC1),
    listOf(0xFF2E7D32, 0xFF8D6E00, 0xFF00796B, 0xFFB5602A, 0xFF5F6F2A),
    listOf(0xFF5E35B1, 0xFFD81B60, 0xFF1E88E5, 0xFF00ACC1, 0xFFFB8C00),
    listOf(0xFFAD1457, 0xFF7E57C2, 0xFFEC407A, 0xFF5C6BC0, 0xFFAB47BC),
)

data class IconTint(val icon: Color, val container: Color, val onContainer: Color)

val LocalIconTints = staticCompositionLocalOf<List<IconTint>> { error("no tints") }

val CalmMotion: MotionScheme = object : MotionScheme by MotionScheme.standard() {
    override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = spring(1f, 700f)
    override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = spring(1f, 1400f)
    override fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> = spring(1f, 300f)
}

const val MONO_PALETTE = -2

internal fun singleHue(seed: Long, dark: Boolean, style: PaletteStyle = PaletteStyle.Fidelity): ColorScheme {
    val scheme = dynamicColorScheme(seedColor = Color(seed), isDark = dark, isAmoled = false, style = style)
    return scheme.copy(
        tertiary = scheme.secondary,
        onTertiary = scheme.onSecondary,
        tertiaryContainer = scheme.secondaryContainer,
        onTertiaryContainer = scheme.onSecondaryContainer,
    )
}

private fun ColorScheme.tint() = IconTint(primary, primaryContainer, onPrimaryContainer)

private fun multiHue(seeds: List<Long>, dark: Boolean): Pair<ColorScheme, List<IconTint>> {
    val parts = seeds.map { singleHue(it, dark) }
    val second = parts[1]
    val third = parts[2]
    val scheme = parts[0].copy(
        secondary = second.primary,
        onSecondary = second.onPrimary,
        secondaryContainer = second.primaryContainer,
        onSecondaryContainer = second.onPrimaryContainer,
        tertiary = third.primary,
        onTertiary = third.onPrimary,
        tertiaryContainer = third.primaryContainer,
        onTertiaryContainer = third.onPrimaryContainer,
    )
    return scheme to parts.map { it.tint() }
}

@Composable
fun AppTheme(settings: AppSettings, platform: Platform, content: @Composable () -> Unit) {
    val dark = when (settings.theme) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    SideEffect { platform.setSystemBarsDark(dark) }
    val system = if (settings.dynamicColor) platform.systemColorScheme(dark) else null
    val palette = MultiPalettes.getOrNull(settings.palette)
    val generated = remember(settings.accent, settings.palette, dark, system == null) {
        when {
            system != null -> null
            palette != null -> multiHue(palette, dark)
            settings.palette == MONO_PALETTE -> singleHue(0xFF808080, dark, PaletteStyle.Monochrome).let { it to listOf(it.tint()) }
            else -> singleHue(settings.accent, dark).let { it to listOf(it.tint()) }
        }
    }
    var scheme = system ?: generated!!.first
    val tints = if (system != null) listOf(system.tint()) else generated!!.second

    if (dark && settings.amoled) {
        scheme = scheme.copy(
            background = Color.Black,
            surface = Color.Black,
            surfaceDim = Color.Black,
            surfaceBright = Color(0xFF2A2A2A),
            surfaceVariant = Color(0xFF242424),
            surfaceContainerLowest = Color.Black,
            surfaceContainerLow = Color(0xFF0E0E0E),
            surfaceContainer = Color(0xFF141414),
            surfaceContainerHigh = Color(0xFF1C1C1C),
            surfaceContainerHighest = Color(0xFF262626),
        )
    }

    val animatedTints = List(5) { index ->
        val tint = tints[index % tints.size]
        IconTint(tint.icon.animated(), tint.container.animated(), tint.onContainer.animated())
    }
    CompositionLocalProvider(LocalIconTints provides animatedTints) {
        MaterialExpressiveTheme(
            colorScheme = scheme.animated(),
            motionScheme = MotionScheme.expressive(),
            shapes = Shapes(
                extraSmall = RoundedCornerShape(6.dp),
                small = RoundedCornerShape(12.dp),
                medium = RoundedCornerShape(16.dp),
                large = RoundedCornerShape(24.dp),
                extraLarge = RoundedCornerShape(32.dp),
            ),
        ) {
            val colors = MaterialTheme.colorScheme
            val iconColor = scheme.onSurface
            SideEffect { platform.setWindowColors(colors.surface, colors.onSurface, iconColor) }

            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface, content = content)
        }
    }
}

private val ColorSpec: AnimationSpec<Color> = tween(450)

@Composable
private fun Color.animated(): Color {
    val value by animateColorAsState(this, ColorSpec, label = "scheme")
    return value
}

@Composable
private fun ColorScheme.animated(): ColorScheme = copy(
    primary = primary.animated(),
    onPrimary = onPrimary.animated(),
    primaryContainer = primaryContainer.animated(),
    onPrimaryContainer = onPrimaryContainer.animated(),
    secondary = secondary.animated(),
    onSecondary = onSecondary.animated(),
    secondaryContainer = secondaryContainer.animated(),
    onSecondaryContainer = onSecondaryContainer.animated(),
    tertiary = tertiary.animated(),
    onTertiary = onTertiary.animated(),
    tertiaryContainer = tertiaryContainer.animated(),
    onTertiaryContainer = onTertiaryContainer.animated(),
    background = background.animated(),
    onBackground = onBackground.animated(),
    surface = surface.animated(),
    onSurface = onSurface.animated(),
    surfaceVariant = surfaceVariant.animated(),
    onSurfaceVariant = onSurfaceVariant.animated(),
    surfaceContainerLowest = surfaceContainerLowest.animated(),
    surfaceContainerLow = surfaceContainerLow.animated(),
    surfaceContainer = surfaceContainer.animated(),
    surfaceContainerHigh = surfaceContainerHigh.animated(),
    surfaceContainerHighest = surfaceContainerHighest.animated(),
    outline = outline.animated(),
    outlineVariant = outlineVariant.animated(),
    error = error.animated(),
    onError = onError.animated(),
    errorContainer = errorContainer.animated(),
    onErrorContainer = onErrorContainer.animated(),
)

package dev.rooni.zefir.ui

import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.RoundedPolygon
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal val HeroTriangle: RoundedPolygon by lazy {
    val vertices = listOf(-90.0, 30.0, 150.0).flatMap { degrees ->
        val angle = degrees * PI / 180.0
        listOf((0.5 + 0.62 * cos(angle)).toFloat(), (0.5 + 0.62 * sin(angle)).toFloat())
    }.toFloatArray()
    RoundedPolygon(vertices, rounding = CornerRounding(0.17f, 0.6f), centerX = 0.5f, centerY = 0.5f)
}

private val CatCurves = listOf(
    listOf(128f, 76f, 142f, 76f, 148f, 75f, 156f, 64f),
    listOf(156f, 64f, 166f, 50f, 184f, 48f, 190f, 62f),
    listOf(190f, 62f, 195f, 74f, 196f, 114f, 196f, 140f),
    listOf(196f, 140f, 196f, 178f, 166f, 208f, 128f, 208f),
    listOf(128f, 208f, 90f, 208f, 60f, 178f, 60f, 140f),
    listOf(60f, 140f, 60f, 114f, 61f, 74f, 66f, 62f),
    listOf(66f, 62f, 72f, 48f, 90f, 50f, 100f, 64f),
    listOf(100f, 64f, 108f, 75f, 114f, 76f, 128f, 76f),
)
private const val CAT_POINTS_PER_CURVE = 14
private const val CAT_TOP = 50f
private const val CAT_SIZE = 160f

internal val CatPolygon: RoundedPolygon by lazy {
    val vertices = CatCurves.flatMap { c ->
        (0 until CAT_POINTS_PER_CURVE).flatMap { step ->
            val t = step / CAT_POINTS_PER_CURVE.toFloat()
            val u = 1f - t
            val x = u * u * u * c[0] + 3 * u * u * t * c[2] + 3 * u * t * t * c[4] + t * t * t * c[6]
            val y = u * u * u * c[1] + 3 * u * u * t * c[3] + 3 * u * t * t * c[5] + t * t * t * c[7]
            listOf((x - 128f) / CAT_SIZE + 0.5f, (y - CAT_TOP) / CAT_SIZE)
        }
    }.toFloatArray()
    RoundedPolygon(vertices, centerX = 0.5f, centerY = 0.55f)
}

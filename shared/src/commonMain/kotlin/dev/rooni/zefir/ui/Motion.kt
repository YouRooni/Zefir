package dev.rooni.zefir.ui

import androidx.compose.ui.MotionDurationScale

object AppMotion : MotionDurationScale {
    @Volatile var off = false
    @Volatile var system = 1f
    override val scaleFactor: Float get() = if (off) 0f else system
}

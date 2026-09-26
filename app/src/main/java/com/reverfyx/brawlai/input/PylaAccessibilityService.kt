package com.reverfyx.brawlai.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import kotlin.math.hypot

class PylaAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: PylaAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    fun performControlSlice(
        screenWidth: Int,
        screenHeight: Int,
        moveX: Float,
        moveY: Float,
        attack: Boolean,
        useSuper: Boolean = false,
        useGadget: Boolean = false,
        durationMs: Long = 180L
    ): Boolean {
        if (screenWidth <= 0 || screenHeight <= 0) return false

        val sx = screenWidth / 1920f
        val sy = screenHeight / 1080f
        val scale = minOf(sx, sy)
        val baseX = 180f * sx
        val baseY = 900f * sy
        val mag = hypot(moveX.toDouble(), moveY.toDouble()).toFloat()
        val nx = if (mag > 1f) moveX / mag else moveX
        val ny = if (mag > 1f) moveY / mag else moveY
        val radius = 78f * scale
        val builder = GestureDescription.Builder()
        var strokes = 0

        if (mag > 0.08f) {
            val movement = Path().apply { moveTo(baseX + nx * radius, baseY + ny * radius) }
            builder.addStroke(GestureDescription.StrokeDescription(movement, 0L, durationMs))
            strokes++
        }

        fun addTap(x1920: Float, y1080: Float, startMs: Long) {
            val p = Path().apply { moveTo(x1920 * sx, y1080 * sy) }
            builder.addStroke(GestureDescription.StrokeDescription(p, startMs, 45L))
            strokes++
        }

        if (attack) addTap(1723f, 793f, 15L)
        if (useSuper) addTap(1512f, 881f, 65L)
        if (useGadget) addTap(1645f, 989f, 115L)
        if (strokes == 0) return false
        return dispatchGesture(builder.build(), null, null)
    }

    fun tap1920(screenWidth: Int, screenHeight: Int, x: Float, y: Float): Boolean {
        val sx = screenWidth / 1920f
        val sy = screenHeight / 1080f
        val p = Path().apply { moveTo(x * sx, y * sy) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 50))
            .build()
        return dispatchGesture(g, null, null)
    }

    fun cancelBotGesture() = Unit
}

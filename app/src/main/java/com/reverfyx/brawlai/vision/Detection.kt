package com.reverfyx.brawlai.vision

import android.graphics.RectF

data class Detection(
    val classId: Int,
    val className: String,
    val confidence: Float,
    val box: RectF
) {
    val centerX: Float get() = (box.left + box.right) * 0.5f
    val centerY: Float get() = (box.top + box.bottom) * 0.5f
}

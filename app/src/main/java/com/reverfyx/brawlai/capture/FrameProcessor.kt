package com.reverfyx.brawlai.capture

import android.content.Context
import android.graphics.Bitmap
import com.reverfyx.brawlai.bot.PylaBotEngine
import com.reverfyx.brawlai.input.PylaAccessibilityService
import com.reverfyx.brawlai.vision.Detection
import com.reverfyx.brawlai.vision.YoloDetector

class FrameProcessor(
    context: Context,
    useNnapi: Boolean,
    private val crop16x9: Boolean,
    private val stats: (String) -> Unit
) : AutoCloseable {

    private val entityDetector = YoloDetector(context,"models/mainInGameModel.onnx",listOf("enemy","teammate","player"),useNnapi,confidenceThreshold=0.55f)
    private val tileDetector = YoloDetector(context,"models/closeTileDetector.onnx",listOf("wall","bush","close_bush"),useNnapi,confidenceThreshold=0.60f)
    private val engine = PylaBotEngine()
    private var frameIndex = 0
    private var cachedTiles: List<Detection> = emptyList()

    fun process(fullFrame: Bitmap, screenWidth: Int, screenHeight: Int) {
        val frame = if (crop16x9) centerCrop16x9(fullFrame) else fullFrame
        try {
            val entities = entityDetector.detect(frame)
            frameIndex++
            if (frameIndex % 6 == 0) cachedTiles = tileDetector.detect(frame)
            val action = engine.decide(entities,cachedTiles,frame.width,frame.height)
            PylaAccessibilityService.instance?.performControlSlice(
                screenWidth,screenHeight,action.moveX,action.moveY,action.attack,action.useSuper,action.useGadget
            )
            val e = entities.count { it.className == "enemy" }
            val p = entities.count { it.className == "player" }
            stats("player=$p enemy=$e | ${action.debug}")
        } finally {
            if (frame !== fullFrame) frame.recycle()
        }
    }

    private fun centerCrop16x9(src: Bitmap): Bitmap {
        val target = 16f/9f
        val current = src.width/src.height.toFloat()
        if (kotlin.math.abs(current-target) < 0.02f) return src
        return if (current > target) {
            val newW=(src.height*target).toInt().coerceAtMost(src.width)
            Bitmap.createBitmap(src,(src.width-newW)/2,0,newW,src.height)
        } else {
            val newH=(src.width/target).toInt().coerceAtMost(src.height)
            Bitmap.createBitmap(src,0,(src.height-newH)/2,src.width,newH)
        }
    }

    override fun close() { entityDetector.close(); tileDetector.close() }
}

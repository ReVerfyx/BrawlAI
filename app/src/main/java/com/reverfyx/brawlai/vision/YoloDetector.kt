package com.reverfyx.brawlai.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

class YoloDetector(
    context: Context,
    modelAssetPath: String,
    private val classes: List<String>,
    useNnapi: Boolean,
    private val inputSize: Int = 640,
    private val confidenceThreshold: Float = 0.55f,
    private val iouThreshold: Float = 0.60f
) : AutoCloseable {

    private val env = OrtEnvironment.getEnvironment()
    private var options: OrtSession.SessionOptions
    private val session: OrtSession
    private val inputName: String
    private val inputBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(3 * inputSize * inputSize * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    init {
        val bytes = context.assets.open(modelAssetPath).use { it.readBytes() }
        var opts = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        var created: OrtSession? = null

        if (useNnapi) {
            try {
                opts.addNnapi()
                created = env.createSession(bytes, opts)
            } catch (_: Throwable) {
                try { opts.close() } catch (_: Throwable) {}
                opts = OrtSession.SessionOptions().apply {
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                }
            }
        }
        if (created == null) created = env.createSession(bytes, opts)
        options = opts
        session = created ?: error("Failed to create ONNX Runtime session")
        inputName = session.inputNames.first()
    }

    fun detect(bitmap: Bitmap): List<Detection> {
        val srcW = bitmap.width
        val srcH = bitmap.height
        if (srcW <= 0 || srcH <= 0) return emptyList()
        val scale = min(inputSize / srcW.toFloat(), inputSize / srcH.toFloat())
        val resizedW = max(1, (srcW * scale).toInt())
        val resizedH = max(1, (srcH * scale).toInt())

        val padded = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(padded)
        canvas.drawColor(Color.rgb(128,128,128))
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(bitmap, null, Rect(0,0,resizedW,resizedH), paint)
        val pixels = IntArray(inputSize * inputSize)
        padded.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        padded.recycle()

        inputBuffer.clear()
        val plane = inputSize * inputSize
        for (channel in 0 until 3) {
            for (i in 0 until plane) {
                val c = pixels[i]
                val v = when(channel) { 0 -> Color.blue(c); 1 -> Color.green(c); else -> Color.red(c) }
                inputBuffer.put(v / 255f)
            }
        }
        inputBuffer.flip()

        OnnxTensor.createTensor(env,inputBuffer,longArrayOf(1,3,inputSize.toLong(),inputSize.toLong())).use { inputTensor ->
            session.run(mapOf(inputName to inputTensor)).use { result ->
                val out = result[0] as? OnnxTensor ?: return emptyList()
                val info = out.info as? TensorInfo ?: return emptyList()
                val buffer = out.floatBuffer ?: return emptyList()
                val raw = FloatArray(buffer.remaining())
                buffer.get(raw)
                return decode(raw,info.shape,srcW,srcH,resizedW,resizedH)
            }
        }
    }

    private fun decode(raw:FloatArray, shape:LongArray, srcW:Int, srcH:Int, resizedW:Int, resizedH:Int):List<Detection> {
        val dims = shape.filter { it > 0 }.map { it.toInt() }
        if (dims.size < 2) return emptyList()
        val a:Int; val b:Int
        if (dims.size >= 3) { a=dims[dims.size-2]; b=dims[dims.size-1] } else { a=dims[0]; b=dims[1] }
        val channelFirst = a <= 256 && a < b
        val channels = if (channelFirst) a else b
        val boxes = if (channelFirst) b else a
        if (channels < 5 || boxes <= 0) return emptyList()
        val numClasses = min(classes.size, channels-4)
        if (numClasses <= 0) return emptyList()

        fun value(box:Int, channel:Int):Float {
            val idx = if (channelFirst) channel*boxes+box else box*channels+channel
            return if (idx in raw.indices) raw[idx] else 0f
        }

        val candidates = Array(numClasses) { mutableListOf<Detection>() }
        val xScale = srcW / resizedW.toFloat()
        val yScale = srcH / resizedH.toFloat()
        for (i in 0 until boxes) {
            var bestClass=-1; var bestScore=Float.NEGATIVE_INFINITY
            for (cls in 0 until numClasses) {
                val score=value(i,4+cls)
                if (score>bestScore) { bestScore=score; bestClass=cls }
            }
            if (bestClass<0 || bestScore<confidenceThreshold) continue
            val cx=value(i,0); val cy=value(i,1); val w=value(i,2); val h=value(i,3)
            val left=((cx-w/2f)*xScale).coerceIn(0f,srcW.toFloat())
            val top=((cy-h/2f)*yScale).coerceIn(0f,srcH.toFloat())
            val right=((cx+w/2f)*xScale).coerceIn(0f,srcW.toFloat())
            val bottom=((cy+h/2f)*yScale).coerceIn(0f,srcH.toFloat())
            if (right<=left || bottom<=top) continue
            candidates[bestClass] += Detection(bestClass,classes[bestClass],bestScore,android.graphics.RectF(left,top,right,bottom))
        }
        return buildList { for (group in candidates) addAll(nms(group)) }
    }

    private fun nms(items:List<Detection>):List<Detection> {
        if (items.isEmpty()) return emptyList()
        val sorted=items.sortedByDescending { it.confidence }.toMutableList()
        val keep=mutableListOf<Detection>()
        while (sorted.isNotEmpty()) {
            val best=sorted.removeAt(0); keep += best
            val iter=sorted.iterator()
            while(iter.hasNext()) if (iou(best.box,iter.next().box)>iouThreshold) iter.remove()
        }
        return keep
    }

    private fun iou(a:android.graphics.RectF,b:android.graphics.RectF):Float {
        val left=max(a.left,b.left); val top=max(a.top,b.top); val right=min(a.right,b.right); val bottom=min(a.bottom,b.bottom)
        val iw=max(0f,right-left); val ih=max(0f,bottom-top); val intersection=iw*ih
        val union=a.width()*a.height()+b.width()*b.height()-intersection
        return if (union<=0f) 0f else intersection/union
    }

    override fun close() {
        try { session.close() } catch (_: Throwable) {}
        try { options.close() } catch (_: Throwable) {}
    }
}

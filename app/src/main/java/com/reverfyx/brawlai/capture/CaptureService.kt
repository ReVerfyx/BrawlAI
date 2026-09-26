package com.reverfyx.brawlai.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import com.reverfyx.brawlai.MainActivity
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class CaptureService : Service() {

    companion object {
        const val ACTION_START = "com.reverfyx.brawlai.START"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_USE_NNAPI = "useNnapi"
        const val EXTRA_CROP_16_9 = "crop169"

        private const val CHANNEL_ID = "pyla_capture"
        private const val NOTIFICATION_ID = 71

        @Volatile
        var running: Boolean = false
            private set
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var processor: FrameProcessor? = null

    private val worker = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private lateinit var imageThread: HandlerThread
    private lateinit var imageHandler: Handler

    private var screenWidth = 0
    private var screenHeight = 0
    private var densityDpi = 0
    private var lastAcceptedFrameNs = 0L
    private var lastNotificationMs = 0L

    override fun onCreate() {
        super.onCreate()
        createChannel()
        imageThread = HandlerThread("BrawlAIImageReader").also { it.start() }
        imageHandler = Handler(imageThread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_START) return START_NOT_STICKY
        if (running) return START_NOT_STICKY

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
        @Suppress("DEPRECATION")
        val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
            ?: return START_NOT_STICKY
        val useNnapi = intent.getBooleanExtra(EXTRA_USE_NNAPI, true)
        val crop169 = intent.getBooleanExtra(EXTRA_CROP_16_9, true)

        val notification = buildNotification("Инициализация моделей…")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val metrics = resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        densityDpi = metrics.densityDpi

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = manager.getMediaProjection(resultCode, resultData)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, Handler(Looper.getMainLooper()))

        setupCapture()
        running = true

        worker.execute {
            try {
                processor = FrameProcessor(
                    context = applicationContext,
                    useNnapi = useNnapi,
                    crop16x9 = crop169,
                    stats = ::updateStats
                )
                updateStats("модели загружены; жду Brawl Stars")
            } catch (t: Throwable) {
                updateStats("ошибка модели: ${t.javaClass.simpleName}: ${t.message ?: "unknown"}")
            }
        }

        return START_NOT_STICKY
    }

    private fun setupCapture() {
        val reader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        imageReader = reader

        virtualDisplay = projection?.createVirtualDisplay(
            "BrawlAICapture",
            screenWidth,
            screenHeight,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            null
        )

        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            val now = System.nanoTime()
            if (now - lastAcceptedFrameNs < 120_000_000L || processor == null || busy.get()) {
                image.close()
                return@setOnImageAvailableListener
            }
            lastAcceptedFrameNs = now

            val bitmap = try { imageToBitmap(image) } catch (_: Throwable) { null } finally { image.close() }
                ?: return@setOnImageAvailableListener

            if (!busy.compareAndSet(false, true)) {
                bitmap.recycle()
                return@setOnImageAvailableListener
            }

            worker.execute {
                try { processor?.process(bitmap, screenWidth, screenHeight) }
                catch (t: Throwable) { updateStats("frame error: ${t.javaClass.simpleName}") }
                finally { bitmap.recycle(); busy.set(false) }
            }
        }, imageHandler)
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        buffer.rewind()
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * screenWidth
        val paddedWidth = screenWidth + rowPadding / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, screenHeight, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(buffer)
        if (paddedWidth == screenWidth) return padded
        val cropped = Bitmap.createBitmap(padded, 0, 0, screenWidth, screenHeight)
        padded.recycle()
        return cropped
    }

    private fun updateStats(text: String) {
        val now = System.currentTimeMillis()
        if (now - lastNotificationMs < 1200L && !text.startsWith("ошибка")) return
        lastNotificationMs = now
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text.take(120)))
    }

    private fun buildNotification(text: String): Notification {
        val pending = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("BrawlAI работает")
            .setContentText(text)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "BrawlAI screen capture", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onDestroy() {
        running = false
        try { imageReader?.setOnImageAvailableListener(null, null) } catch (_: Throwable) {}
        try { imageReader?.close() } catch (_: Throwable) {}
        try { virtualDisplay?.release() } catch (_: Throwable) {}
        try { projection?.stop() } catch (_: Throwable) {}
        try { processor?.close() } catch (_: Throwable) {}
        worker.shutdownNow()
        if (::imageThread.isInitialized) imageThread.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

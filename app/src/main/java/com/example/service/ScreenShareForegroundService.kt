package com.example.service

import android.app.Activity
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
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.core.media.MediaProcessingHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

object ScreenShareController {
    private val _isSharing = MutableStateFlow(false)
    val isSharing: StateFlow<Boolean> = _isSharing.asStateFlow()

    private val _latestSampledFrame = MutableStateFlow<Bitmap?>(null)
    val latestSampledFrame: StateFlow<Bitmap?> = _latestSampledFrame.asStateFlow()

    private var lastFrameHash: Long = 0L

    internal fun setSharing(active: Boolean) {
        _isSharing.value = active
        if (!active) {
            _latestSampledFrame.value = null
            lastFrameHash = 0L
        }
    }

    internal fun offerFrameIfChanged(bitmap: Bitmap) {
        val hash = computeQuickFrameHash(bitmap)
        if (abs(hash - lastFrameHash) > 12L || _latestSampledFrame.value == null) {
            lastFrameHash = hash
            _latestSampledFrame.value = MediaProcessingHelper.scaleBitmapMaxDim(bitmap, 960)
        }
    }

    private fun computeQuickFrameHash(bitmap: Bitmap): Long {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return 0L
        var sum = 0L
        val stepX = (w / 8).coerceAtLeast(1)
        val stepY = (h / 8).coerceAtLeast(1)
        var y = stepY / 2
        while (y < h) {
            var x = stepX / 2
            while (x < w) {
                val pixel = bitmap.getPixel(x, y)
                val luma = (((pixel shr 16) and 0xFF) + ((pixel shr 8) and 0xFF) + (pixel and 0xFF)) / 3
                sum += luma.toLong()
                x += stepX
            }
            y += stepY
        }
        return sum
    }

    fun stopSharing(context: Context) {
        val intent = Intent(context, ScreenShareForegroundService::class.java).apply {
            action = ScreenShareForegroundService.ACTION_STOP
        }
        context.startService(intent)
    }
}

class ScreenShareForegroundService : Service() {

    companion object {
        private const val TAG = "ScreenShareService"
        private const val CHANNEL_ID = "guriva_screen_share"
        private const val NOTIFICATION_ID = 4102
        const val ACTION_START = "com.example.action.START_SCREEN_SHARE"
        const val ACTION_STOP = "com.example.action.STOP_SCREEN_SHARE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var lastCaptureTimeMs: Long = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopProjection()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                createChannel()
                val notification = buildNotification()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }

                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                @Suppress("DEPRECATION")
                val resultData: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)
                if (resultCode == Activity.RESULT_OK && resultData != null) {
                    startProjection(resultCode, resultData)
                } else {
                    stopProjection()
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startProjection(resultCode: Int, data: Intent) {
        try {
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = mgr.getMediaProjection(resultCode, data) ?: run {
                stopSelf()
                return
            }
            mediaProjection = projection
            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopProjection()
                    stopSelf()
                }
            }, null)

            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            val width = (metrics.widthPixels / 2).coerceIn(360, 960)
            val height = (metrics.heightPixels / 2).coerceIn(640, 1440)
            val density = metrics.densityDpi

            val thread = HandlerThread("GurivaScreenSampler").apply { start() }
            handlerThread = thread
            val handler = Handler(thread.looper)

            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            imageReader = reader
            reader.setOnImageAvailableListener({ r ->
                val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val now = System.currentTimeMillis()
                    if (now - lastCaptureTimeMs < 2000L) return@setOnImageAvailableListener
                    lastCaptureTimeMs = now

                    val plane = img.planes.firstOrNull() ?: return@setOnImageAvailableListener
                    val buffer = plane.buffer
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    val rowPadding = rowStride - pixelStride * width
                    val bmp = Bitmap.createBitmap(
                        width + rowPadding / pixelStride,
                        height,
                        Bitmap.Config.ARGB_8888
                    )
                    bmp.copyPixelsFromBuffer(buffer)
                    val cropped = Bitmap.createBitmap(bmp, 0, 0, width, height)
                    bmp.recycle()
                    ScreenShareController.offerFrameIfChanged(cropped)
                } catch (e: Exception) {
                    Log.e(TAG, "Frame sampling error", e)
                } finally {
                    img.close()
                }
            }, handler)

            virtualDisplay = projection.createVirtualDisplay(
                "GurivaScreenShare",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                handler
            )
            ScreenShareController.setSharing(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MediaProjection", e)
            stopProjection()
            stopSelf()
        }
    }

    private fun stopProjection() {
        ScreenShareController.setSharing(false)
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null
        try {
            imageReader?.close()
        } catch (_: Exception) {
        }
        imageReader = null
        try {
            mediaProjection?.stop()
        } catch (_: Exception) {
        }
        mediaProjection = null
        try {
            handlerThread?.quitSafely()
        } catch (_: Exception) {
        }
        handlerThread = null
    }

    override fun onDestroy() {
        stopProjection()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "GURIVA Screen Sharing",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active while sharing your screen with your GURIVA teacher."
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ScreenShareForegroundService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("GURIVA is viewing your shared screen")
            .setContentText("Tap Stop anytime to end screen sharing.")
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop sharing", stopIntent)
            .build()
    }
}

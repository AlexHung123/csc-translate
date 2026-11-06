package com.csc.translate.audio

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.graphics.PixelFormat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Holds the screen-share session used to hear what the tablet plays.
 * The share stays up for the translation session and is released when it ends.
 */
object SystemAudio {
    @Volatile
    var onRevoked: (() -> Unit)? = null

    private val lock = Any()
    private var gate = CompletableDeferred<MediaProjection?>()
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var stopping = false

    private val callback = object : MediaProjection.Callback() {
        override fun onStop() {
            val listener = synchronized(lock) {
                if (stopping) return
                stopping = true
                projection = null
                val ownedDisplay = display
                val ownedReader = reader
                display = null
                reader = null
                runCatching { ownedDisplay?.release() }
                runCatching { ownedReader?.close() }
                if (!gate.isCompleted) gate.complete(null)
                onRevoked
            }
            stopping = false
            listener?.invoke()
        }
    }

    /** Drops any previous share and waits for the next one. */
    fun prepare() {
        release()
        synchronized(lock) {
            gate = CompletableDeferred()
            stopping = false
        }
    }

    suspend fun await(): MediaProjection? = withTimeoutOrNull(8_000) { gate.await() }

    fun fail() {
        synchronized(lock) {
            if (!gate.isCompleted) gate.complete(null)
        }
    }

    /**
     * Call only after a media-projection foreground service is running.
     * Some tablets stay silent unless the share also owns a display, so a tiny one is kept and its
     * frames are thrown away. Only the audio is read.
     */
    fun attach(context: Context, resultCode: Int, data: Intent): Boolean {
        var projection: MediaProjection? = null
        var display: VirtualDisplay? = null
        var reader: ImageReader? = null
        try {
            val manager = context.getSystemService(MediaProjectionManager::class.java)
                ?: error("no projection service")
            projection = manager.getMediaProjection(resultCode, data)
            val handler = Handler(Looper.getMainLooper())
            projection.registerCallback(callback, handler)
            reader = ImageReader.newInstance(64, 64, PixelFormat.RGBA_8888, 2)
            reader.setOnImageAvailableListener({ imageReader ->
                runCatching { imageReader.acquireLatestImage()?.close() }
            }, handler)
            val dpi = context.resources.displayMetrics.densityDpi.coerceAtLeast(1)
            display = runCatching {
                projection.createVirtualDisplay(
                    "csc-system",
                    64,
                    64,
                    dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.surface,
                    null,
                    handler,
                )
            }.onFailure { Log.w(TAG, "virtual display", it) }.getOrNull()
            val published = synchronized(lock) {
                if (gate.isCompleted) {
                    false
                } else {
                    this.projection = projection
                    this.display = display
                    this.reader = reader
                    gate.complete(projection)
                    true
                }
            }
            if (published) return true
        } catch (t: Throwable) {
            Log.w(TAG, "projection", t)
        }
        discard(projection, display, reader)
        fail()
        return false
    }

    fun release() {
        val toStop = synchronized(lock) {
            if (stopping) return
            stopping = true
            val owned = projection
            projection = null
            val ownedDisplay = display
            val ownedReader = reader
            display = null
            reader = null
            runCatching { ownedDisplay?.release() }
            runCatching { ownedReader?.close() }
            if (!gate.isCompleted) gate.complete(null)
            owned
        }
        runCatching { toStop?.stop() }
        stopping = false
    }

    private fun discard(
        projection: MediaProjection?,
        display: VirtualDisplay?,
        reader: ImageReader?,
    ) {
        synchronized(lock) { stopping = true }
        runCatching { display?.release() }
        runCatching { reader?.close() }
        runCatching { projection?.stop() }
        stopping = false
    }

    private const val TAG = "CscTranslate"
}

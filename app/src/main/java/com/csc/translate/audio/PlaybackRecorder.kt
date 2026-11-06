package com.csc.translate.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Process
import android.util.Log
import androidx.annotation.RequiresApi
import kotlin.math.max

/** Hears audio other apps are playing and emits 100 ms mono PCM frames. */
@RequiresApi(29)
class PlaybackRecorder(
    private val projection: MediaProjection,
    private val onFrame: (ByteArray) -> Unit,
    private val onError: (String) -> Unit,
) : AudioCapture {
    @Volatile
    private var running = false
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private var channels: Int = 1
    var sampleRate: Int = 16_000
        private set

    @SuppressLint("MissingPermission")
    fun open(): Boolean {
        stop()
        val opened = openRecord(projection) ?: return false
        record = opened.record
        sampleRate = opened.sampleRate
        channels = opened.channels
        Log.i(TAG, "playback capture ${sampleRate}Hz x$channels")
        return true
    }

    override fun start() {
        val rec = record ?: error("playback capture is not open")
        running = true
        rec.startRecording()
        thread = Thread({ loop(rec) }, "csc-playback").also { it.start() }
    }

    override fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        val rec = record
        record = null
        try {
            rec?.stop()
        } catch (_: IllegalStateException) {
        }
        rec?.release()
    }

    private fun loop(rec: AudioRecord) {
        val monoBytes = sampleRate / 10 * 2
        val chunk = monoBytes * channels
        val buf = ByteArray(chunk)
        while (running) {
            var filled = 0
            while (running && filled < buf.size) {
                val n = rec.read(buf, filled, buf.size - filled)
                when {
                    n > 0 -> filled += n
                    n == AudioRecord.ERROR_DEAD_OBJECT || n == AudioRecord.ERROR_INVALID_OPERATION -> {
                        if (running) onError("stopped")
                        running = false
                        return
                    }
                    else -> if (!running) return
                }
            }
            if (running && filled == buf.size) {
                onFrame(downmixToMono(buf, channels))
            }
        }
    }

    private data class Opened(val record: AudioRecord, val sampleRate: Int, val channels: Int)

    companion object {
        private const val TAG = "CscTranslate"

        @SuppressLint("MissingPermission")
        private fun openRecord(projection: MediaProjection): Opened? {
            val attempts = listOf(
                16_000 to AudioFormat.CHANNEL_IN_MONO,
                48_000 to AudioFormat.CHANNEL_IN_MONO,
                44_100 to AudioFormat.CHANNEL_IN_MONO,
                48_000 to AudioFormat.CHANNEL_IN_STEREO,
                44_100 to AudioFormat.CHANNEL_IN_STEREO,
            )
            for ((rate, mask) in attempts) {
                val channels = if (mask == AudioFormat.CHANNEL_IN_MONO) 1 else 2
                val min = AudioRecord.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
                if (min <= 0) continue
                val rec = runCatching {
                    val format = AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(mask)
                        .build()
                    val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .excludeUid(Process.myUid())
                        .build()
                    AudioRecord.Builder()
                        .setAudioFormat(format)
                        .setBufferSizeInBytes(max(min, rate / 10 * 2 * channels * 4))
                        .setAudioPlaybackCaptureConfig(config)
                        .build()
                }.getOrNull() ?: continue
                if (rec.state != AudioRecord.STATE_INITIALIZED) {
                    rec.release()
                    continue
                }
                return Opened(rec, rate, channels)
            }
            return null
        }
    }
}

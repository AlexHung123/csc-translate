package com.csc.translate.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.math.max

interface AudioCapture {
    fun start()
    fun stop()
}

class PcmRecorder(
    private val onFrame: (ByteArray) -> Unit,
    private val onError: (String) -> Unit,
) : AudioCapture {
    @Volatile
    private var running = false
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    var sampleRate: Int = 16_000
        private set
    var frameBytes: Int = sampleRate / 10 * 2
        private set

    @SuppressLint("MissingPermission")
    override fun start() {
        stop()
        val chosen = pickSampleRate() ?: run {
            onError("Could not open the microphone")
            error("no sample rate")
        }
        sampleRate = chosen
        frameBytes = sampleRate / 10 * 2
        val min = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferSize = max(min, frameBytes * 4)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            onError("Could not open the microphone")
            error("AudioRecord init failed")
        }
        record = rec
        running = true
        rec.startRecording()
        thread = Thread({ loop(rec) }, "csc-pcm").also { it.start() }
    }

    override fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        try {
            record?.stop()
        } catch (_: IllegalStateException) {
        }
        record?.release()
        record = null
    }

    private fun loop(rec: AudioRecord) {
        val buf = ByteArray(frameBytes)
        while (running) {
            var filled = 0
            while (running && filled < buf.size) {
                val n = rec.read(buf, filled, buf.size - filled)
                when {
                    n > 0 -> filled += n
                    n == AudioRecord.ERROR_DEAD_OBJECT || n == AudioRecord.ERROR_INVALID_OPERATION -> {
                        if (running) onError("Microphone disconnected")
                        running = false
                        return
                    }
                    n == 0 -> {
                        if (!running) return
                    }
                    else -> {
                        if (running) Log.w(TAG, "AudioRecord.read=$n")
                        if (!running) return
                    }
                }
            }
            if (running && filled == buf.size) {
                onFrame(buf.copyOf())
            }
        }
    }

    companion object {
        private const val TAG = "CscTranslate"

        fun pickSampleRate(): Int? {
            for (rate in intArrayOf(16_000, 48_000, 44_100)) {
                val min = AudioRecord.getMinBufferSize(
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
                if (min > 0) return rate
            }
            return null
        }
    }
}

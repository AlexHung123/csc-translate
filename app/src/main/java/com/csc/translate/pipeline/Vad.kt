package com.csc.translate.pipeline

import kotlin.math.sqrt

// 32 ms frames. A run of voice is backdated 64 ms and a run of silence 96 ms, matching the
// desktop engine. The stretch runner still waits 0.25 s to open and 1 s to close.

private const val VOICE_ON = 2 * FRAME
private const val VOICE_OFF = 3 * FRAME

internal interface Voice {
    fun accept(frame: FloatArray)
    val hot: Boolean
}

/** Loudness gate kept for tests. A sine wave is speech here and is not speech to Silero. */
internal class Level(private val floor: Double) : Voice {
    private var noise = 0.0
    override var hot: Boolean = false
        private set

    override fun accept(frame: FloatArray) {
        var sum = 0.0
        for (s in frame) sum += s * s
        val rms = if (frame.isEmpty()) 0.0 else sqrt(sum / frame.size)
        var limit = floor
        if (noise > 0) {
            val raised = noise * 2.5
            if (raised > limit) limit = raised
        }
        hot = rms >= limit && rms >= 0.008
        if (!hot) {
            noise = if (noise == 0.0) rms else noise * 0.9 + rms * 0.1
        }
    }
}

internal data class VoiceAt(val on: Boolean, val tSec: Double)

internal class Detector(private val gate: Voice) {
    private var buf = FloatArray(0)
    private var samples = 0L
    private var voice = false

    fun push(samplesIn: FloatArray): List<VoiceAt> {
        val changes = mutableListOf<VoiceAt>()
        buf += samplesIn
        while (buf.size >= FRAME) {
            gate.accept(buf.copyOfRange(0, FRAME))
            buf = buf.copyOfRange(FRAME, buf.size)
            samples += FRAME
            val on = gate.hot
            if (on != voice) {
                voice = on
                val run = if (on) VOICE_ON else VOICE_OFF
                val t = maxOf(0L, samples - run).toDouble() / RATE
                changes += VoiceAt(on, t)
            }
        }
        return changes
    }
}

package com.csc.translate.pipeline

import kotlin.math.sqrt

// 32 ms frames. Voice starts after two frames at or over 0.5, and stops after three under 0.35,
// at the first frame of that run. A single loud frame is a click, not speech. The stretch runner
// still waits 0.25 s to open and 1 s to close.

private const val SCORE_ON = 0.5f
private const val SCORE_OFF = 0.35f

internal interface Voice {
    fun accept(frame: FloatArray)
    val score: Float
}

/** Loudness gate kept for tests. A sine wave is speech here and is not speech to Silero. */
internal class Level(private val floor: Double) : Voice {
    private var noise = 0.0
    override var score: Float = 0f
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
        val loud = rms >= limit && rms >= 0.008
        score = if (loud) 1f else 0f
        if (!loud) {
            noise = if (noise == 0.0) rms else noise * 0.9 + rms * 0.1
        }
    }
}

internal data class VoiceAt(val on: Boolean, val tSec: Double)

internal class Detector(private val gate: Voice) {
    private var buf = FloatArray(0)
    private var samples = 0L
    private var voice = false
    private var run = 0
    private var runStart = 0L

    fun push(samplesIn: FloatArray): List<VoiceAt> {
        val changes = mutableListOf<VoiceAt>()
        buf += samplesIn
        while (buf.size >= FRAME) {
            val start = samples
            gate.accept(buf.copyOfRange(0, FRAME))
            buf = buf.copyOfRange(FRAME, buf.size)
            samples += FRAME
            val other = if (voice) gate.score < SCORE_OFF else gate.score >= SCORE_ON
            if (!other) {
                run = 0
                continue
            }
            if (run == 0) runStart = start
            run++
            if (run >= if (voice) 3 else 2) {
                voice = !voice
                run = 0
                changes += VoiceAt(voice, runStart.toDouble() / RATE)
            }
        }
        return changes
    }
}

package com.csc.translate.pipeline

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession

/**
 * Silero VAD v4, the 16 kHz ONNX export shipped with sherpa-onnx (MIT).
 * Inputs are `x`, `h`, and `c`. A frame is speech at 0.5, and stays speech down to 0.35 so one
 * quiet window does not flip the stretch runner.
 */
internal class Silero(model: ByteArray) : Voice, AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val h = Array(2) { Array(1) { FloatArray(STATE) } }
    private val c = Array(2) { Array(1) { FloatArray(STATE) } }
    private val gate = Any()
    private var closed = false
    override var hot: Boolean = false
        private set

    init {
        val options = OrtSession.SessionOptions()
        try {
            options.setIntraOpNumThreads(1)
            session = env.createSession(model, options)
        } finally {
            options.close()
        }
        val names = session.inputNames
        check(names.containsAll(listOf("x", "h", "c"))) { "silero inputs $names" }
    }

    override fun accept(frame: FloatArray) {
        synchronized(gate) {
            if (closed) return
            val x = OnnxTensor.createTensor(env, arrayOf(frame))
            val hIn = OnnxTensor.createTensor(env, h)
            val cIn = OnnxTensor.createTensor(env, c)
            try {
                session.run(mapOf("x" to x, "h" to hIn, "c" to cIn)).use { out ->
                    val prob = readProb(out.get(0).value)
                    hot = prob >= if (hot) HOLD else OPEN
                    readState(out.get(1).value, h)
                    readState(out.get(2).value, c)
                }
            } finally {
                x.close()
                hIn.close()
                cIn.close()
            }
        }
    }

    override fun close() {
        synchronized(gate) {
            if (closed) return
            closed = true
            session.close()
        }
    }

    private fun readProb(value: Any?): Float = when (value) {
        is FloatArray -> value[0]
        is Array<*> -> readProb(value.firstOrNull())
        is Float -> value
        else -> 0f
    }

    private fun readState(value: Any?, into: Array<Array<FloatArray>>) {
        val layers = value as Array<*>
        for (i in into.indices) {
            val batch = layers[i] as Array<*>
            (batch[0] as FloatArray).copyInto(into[i][0])
        }
    }

    private companion object {
        const val STATE = 64
        const val OPEN = 0.5f
        const val HOLD = 0.35f
    }
}

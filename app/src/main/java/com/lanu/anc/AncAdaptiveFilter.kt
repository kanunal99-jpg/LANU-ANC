package com.lanu.anc

import kotlin.math.abs

/**
 * Small, allocation-free normalized-LMS adaptive filter primitive.
 * It is intentionally not wired as "ANC" yet: a production ANC path also needs
 * measured secondary-path modeling, reference/error microphones, latency alignment,
 * stability bounds and a safety limiter on a verified hardware topology.
 */
class AncAdaptiveFilter(
    private val taps: Int = 64,
    private val learningRate: Float = 0.0005f,
    private val epsilon: Float = 1e-6f
) {
    init {
        require(taps in 1..1024)
        require(learningRate > 0f)
    }

    private val weights = FloatArray(taps)
    private val history = FloatArray(taps)
    private var cursor = 0

    fun reset() {
        weights.fill(0f)
        history.fill(0f)
        cursor = 0
    }

    /** Produces the adaptive filter output for one reference sample. */
    fun predict(reference: Float): Float {
        history[cursor] = reference
        var output = 0f
        var index = cursor
        for (i in 0 until taps) {
            output += weights[i] * history[index]
            index--
            if (index < 0) index = taps - 1
        }
        return output
    }

    /** Updates coefficients from an externally measured error sample. */
    fun adapt(error: Float) {
        var index = cursor
        var energy = epsilon
        for (i in 0 until taps) {
            val x = history[index]
            energy += x * x
            index--
            if (index < 0) index = taps - 1
        }
        val step = learningRate * error / energy
        index = cursor
        for (i in 0 until taps) {
            weights[i] += step * history[index]
            index--
            if (index < 0) index = taps - 1
        }
        cursor++
        if (cursor == taps) cursor = 0
    }

    fun coefficientMagnitude(): Float {
        var max = 0f
        for (weight in weights) max = maxOf(max, abs(weight))
        return max
    }
}

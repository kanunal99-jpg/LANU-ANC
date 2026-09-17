package com.lanu.anc

import kotlin.math.abs
import kotlin.math.max

/**
 * Allocation-free FxLMS building block.
 *
 * This is intentionally a DSP core, not a claim that the Android device has an
 * ANC-capable acoustic topology. The caller must provide a measured secondary-
 * path model and a physically aligned reference/error signal.
 */
class FxLmsAncCore(
    private val taps: Int = 64,
    private val secondaryPathTaps: FloatArray,
    private val learningRate: Float = 0.0001f,
    private val epsilon: Float = 1e-6f,
    private val maxCoefficient: Float = 1.0f
) {
    init {
        require(taps in 1..1024)
        require(secondaryPathTaps.isNotEmpty())
        require(secondaryPathTaps.size <= taps)
        require(learningRate > 0f)
        require(maxCoefficient > 0f)
    }

    private val weights = FloatArray(taps)
    private val referenceHistory = FloatArray(taps)
    private val filteredReference = FloatArray(taps)
    private var cursor = 0
    private var maxAbsCoefficient = 0f

    fun reset() {
        weights.fill(0f)
        referenceHistory.fill(0f)
        filteredReference.fill(0f)
        cursor = 0
        maxAbsCoefficient = 0f
    }

    fun predict(reference: Float): Float {
        referenceHistory[cursor] = reference
        var output = 0f
        var index = cursor
        for (i in 0 until taps) {
            output += weights[i] * referenceHistory[index]
            index--
            if (index < 0) index = taps - 1
        }
        return output.coerceIn(-0.8912509f, 0.8912509f)
    }

    fun adapt(error: Float) {
        var filtered = 0f
        var sourceIndex = cursor
        for (path in secondaryPathTaps.indices) {
            filtered += secondaryPathTaps[path] * referenceHistory[sourceIndex]
            sourceIndex--
            if (sourceIndex < 0) sourceIndex = taps - 1
        }
        filteredReference[cursor] = filtered

        var energy = epsilon
        var index = cursor
        for (i in 0 until taps) {
            val x = filteredReference[index]
            energy += x * x
            index--
            if (index < 0) index = taps - 1
        }

        val step = learningRate * error / energy
        index = cursor
        for (i in 0 until taps) {
            weights[i] = (weights[i] + step * filteredReference[index])
                .coerceIn(-maxCoefficient, maxCoefficient)
            index--
            if (index < 0) index = taps - 1
        }

        maxAbsCoefficient = 0f
        for (weight in weights) maxAbsCoefficient = max(maxAbsCoefficient, abs(weight))
        cursor++
        if (cursor == taps) cursor = 0
    }

    fun coefficientMagnitude(): Float = maxAbsCoefficient
}

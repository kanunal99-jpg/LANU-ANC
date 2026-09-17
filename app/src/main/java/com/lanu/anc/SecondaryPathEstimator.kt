package com.lanu.anc

import kotlin.math.sqrt

/**
 * Offline/foreground calibration helper for estimating the acoustic secondary path.
 *
 * This class is intentionally not used from the realtime audio callback. It estimates
 * a bounded FIR model from an excitation/reference recording and the measured response.
 * A model is returned only when the recording is finite, sufficiently energetic and the
 * normalized residual gives a meaningful confidence score.
 */
object SecondaryPathEstimator {
    data class Result(
        val model: SecondaryPathModel,
        val latencyAligner: LatencyAligner
    )

    fun estimate(
        excitation: FloatArray,
        response: FloatArray,
        sampleRateHz: Int,
        taps: Int = 64,
        maxLatencySamples: Int = LatencyAligner.MAX_DELAY_SAMPLES,
        learningRate: Float = 0.5f,
        minConfidence: Float = 0.65f
    ): Result? {
        if (sampleRateHz <= 0 || excitation.size < 256 || response.size != excitation.size) return null
        if (taps !in 1..1024 || maxLatencySamples !in 0..LatencyAligner.MAX_DELAY_SAMPLES) return null
        if (learningRate <= 0f || !minConfidence.isFinite() || minConfidence !in 0f..1f) return null
        if (excitation.any { !it.isFinite() } || response.any { !it.isFinite() }) return null

        val latency = findLatency(excitation, response, maxLatencySamples) ?: return null
        if (latency >= excitation.size - taps - 1) return null

        val weights = FloatArray(taps)
        val history = FloatArray(taps)
        var cursor = 0
        var residualEnergy = 0.0
        var responseEnergy = 0.0
        var samples = 0

        val start = latency + taps
        for (n in start until response.size) {
            history[cursor] = excitation[n - latency]
            var prediction = 0f
            var index = cursor
            for (i in 0 until taps) {
                prediction += weights[i] * history[index]
                index--
                if (index < 0) index = taps - 1
            }

            val target = response[n]
            val error = target - prediction
            var energy = 1e-6f
            index = cursor
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

            residualEnergy += error.toDouble() * error.toDouble()
            responseEnergy += target.toDouble() * target.toDouble()
            samples++
            cursor++
            if (cursor == taps) cursor = 0
        }

        if (samples == 0 || responseEnergy <= 1e-9) return null
        val normalizedResidual = sqrt(residualEnergy / responseEnergy).coerceAtMost(1.0)
        val confidence = (1.0 - normalizedResidual).toFloat().coerceIn(0f, 1f)
        if (confidence < minConfidence) return null

        val model = SecondaryPathModel.measured(
            coefficients = weights,
            sampleRateHz = sampleRateHz,
            measurementLatencySamples = latency,
            confidence = confidence
        ) ?: return null
        val aligner = LatencyAligner.calibrated(latency, sampleRateHz, confidence) ?: return null
        return Result(model, aligner)
    }

    private fun findLatency(
        excitation: FloatArray,
        response: FloatArray,
        maxLatencySamples: Int
    ): Int? {
        val usableMax = minOf(maxLatencySamples, excitation.size / 2)
        var bestLag = 0
        var bestScore = Double.NEGATIVE_INFINITY
        for (lag in 0..usableMax) {
            var xy = 0.0
            var xx = 0.0
            var yy = 0.0
            val limit = excitation.size - lag
            for (i in 0 until limit) {
                val x = excitation[i].toDouble()
                val y = response[i + lag].toDouble()
                xy += x * y
                xx += x * x
                yy += y * y
            }
            if (xx <= 1e-12 || yy <= 1e-12) continue
            val score = xy / sqrt(xx * yy)
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }
        return bestLag.takeIf { bestScore.isFinite() && bestScore > 0.15 }
    }
}

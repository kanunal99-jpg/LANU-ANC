package com.lanu.anc

import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

class SecondaryPathEstimatorTest {
    @Test
    fun estimates_latency_and_fir_model_from_known_path() {
        val excitation = FloatArray(4096) { i ->
            (((i * 73) % 997) / 498.5f) - 1f
        }
        val path = floatArrayOf(0.7f, -0.2f, 0.1f)
        val latency = 7
        val response = FloatArray(excitation.size)
        for (n in response.indices) {
            var value = 0f
            for (k in path.indices) {
                val source = n - latency - k
                if (source >= 0) value += path[k] * excitation[source]
            }
            response[n] = value
        }

        val result = SecondaryPathEstimator.estimate(
            excitation = excitation,
            response = response,
            sampleRateHz = 48_000,
            taps = 8,
            maxLatencySamples = 32,
            learningRate = 0.5f,
            minConfidence = 0.8f
        )

        assertNotNull(result)
        assertEquals(latency, result!!.model.measurementLatencySamples)
        assertEquals(48_000, result.model.sampleRateHz)
        assertTrue(result.model.confidence >= 0.8f)
        assertTrue(abs(result.model.coefficients[0] - path[0]) < 0.05f)
        assertTrue(abs(result.model.coefficients[1] - path[1]) < 0.05f)
        assertTrue(abs(result.model.coefficients[2] - path[2]) < 0.05f)
        assertEquals(latency, result.latencyAligner.delaySamples)
    }

    @Test
    fun rejects_non_finite_or_mismatched_recordings() {
        val excitation = FloatArray(256) { 0.1f }
        val response = FloatArray(255) { 0.1f }
        assertNull(SecondaryPathEstimator.estimate(excitation, response, 48_000))
        excitation[10] = Float.NaN
        assertNull(SecondaryPathEstimator.estimate(excitation, FloatArray(256), 48_000))
    }

    @Test
    fun rejects_low_energy_measurement() {
        assertNull(
            SecondaryPathEstimator.estimate(
                FloatArray(512),
                FloatArray(512),
                48_000
            )
        )
    }
}

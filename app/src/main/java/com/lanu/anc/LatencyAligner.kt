package com.lanu.anc

/**
 * Bounded sample-domain alignment for reference/error paths.
 *
 * This does not measure latency. It applies only a calibration delay that has
 * already been measured and validated by the caller. Until calibrated, the
 * aligner remains invalid and must be treated as bypass.
 */
class LatencyAligner private constructor(
    val delaySamples: Int,
    val sampleRateHz: Int,
    val confidence: Float
) {
    val isValid: Boolean = sampleRateHz > 0 &&
        delaySamples in 0..MAX_DELAY_SAMPLES &&
        confidence in 0f..1f

    fun reset() {
        cursor = 0
        java.util.Arrays.fill(history, 0f)
    }

    /** Allocation-free once constructed. */
    fun align(sample: Float): Float {
        if (!isValid) return 0f
        history[cursor] = sample
        val readIndex = (cursor - delaySamples + history.size) % history.size
        val result = history[readIndex]
        cursor = (cursor + 1) % history.size
        return result
    }

    private val history = FloatArray(delaySamples + 1)
    private var cursor = 0

    companion object {
        const val MAX_DELAY_SAMPLES = 4096

        fun calibrated(
            delaySamples: Int,
            sampleRateHz: Int,
            confidence: Float
        ): LatencyAligner? {
            val aligner = LatencyAligner(delaySamples, sampleRateHz, confidence)
            return aligner.takeIf { it.isValid }
        }
    }
}

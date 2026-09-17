package com.lanu.anc

/**
 * Realtime-safe PCM processing chain used by the Kotlin fallback backend.
 * ANC is deliberately not faked here; this is the insertion point for a
 * future reference/error-mic adaptive ANC stage. The current safety stage
 * is a unity-gain hard limiter at -1 dBFS.
 */
class DspPipeline(
    private val limiterPeak: Float = 0.8912509f
) {
    data class Metrics(
        val processingMicros: Long = 0L,
        val maxProcessingMicros: Long = 0L,
        val limiterActivations: Long = 0L
    )

    @Volatile var bypass: Boolean = false

    @Volatile var metrics: Metrics = Metrics()
        private set

    private var maxProcessingMicros = 0L
    private var limiterActivations = 0L

    fun process(samples: ShortArray, length: Int): Int {
        require(length in 0..samples.size)
        if (length == 0 || bypass) return length

        val start = System.nanoTime()
        var activated = 0L
        val peak = limiterPeak.coerceIn(0.1f, 1.0f)
        for (i in 0 until length) {
            val normalized = samples[i] / 32768f
            val limited = normalized.coerceIn(-peak, peak)
            if (limited != normalized) activated++
            samples[i] = (limited * 32767f)
                .toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
        val elapsedMicros = (System.nanoTime() - start) / 1_000L
        limiterActivations += activated
        if (elapsedMicros > maxProcessingMicros) maxProcessingMicros = elapsedMicros
        metrics = Metrics(elapsedMicros, maxProcessingMicros, limiterActivations)
        return length
    }

    fun reset() {
        maxProcessingMicros = 0L
        limiterActivations = 0L
        metrics = Metrics()
    }
}

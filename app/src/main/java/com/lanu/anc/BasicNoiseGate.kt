package com.lanu.anc

import kotlin.math.sqrt

/** Conservative fallback, suppressing quiet gaps. This does not separate voices from crowds. */
class BasicNoiseGate {
    private var envelopeGain = 1f

    fun process(samples: ShortArray, length: Int, sampleRate: Int) {
        require(length in 0..samples.size && sampleRate > 0)
        if (length == 0) return
        var power = 0.0
        for (i in 0 until length) {
            val s = samples[i] / 32768.0
            power += s * s
        }
        val rms = sqrt(power / length).toFloat()
        // Quiet signals below roughly -42 dBFS are attenuated, louder speech stays intact.
        val target = ((rms - 0.004f) / 0.014f).coerceIn(0f, 1f).let { 0.1f + 0.9f * it }
        val response = if (target > envelopeGain) 1f / (sampleRate * 0.003f) else 1f / (sampleRate * 0.06f)
        for (i in 0 until length) {
            envelopeGain += response.coerceAtMost(1f) * (target - envelopeGain)
            samples[i] = (samples[i] * envelopeGain).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    fun reset() { envelopeGain = 1f }
}

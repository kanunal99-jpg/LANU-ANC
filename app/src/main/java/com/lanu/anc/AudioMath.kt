package com.lanu.anc

import kotlin.math.log10
import kotlin.math.sqrt

object AudioMath {
    fun rmsDbFs(samples: ShortArray, length: Int = samples.size): Float {
        val count = length.coerceIn(0, samples.size)
        if (count == 0) return -120f
        var sum = 0.0
        for (i in 0 until count) {
            val normalized = samples[i] / 32768.0
            sum += normalized * normalized
        }
        val rms = sqrt(sum / count)
        if (rms <= 1.0e-6) return -120f
        return (20.0 * log10(rms)).toFloat().coerceIn(-120f, 0f)
    }
}

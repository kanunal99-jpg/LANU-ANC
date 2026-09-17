package com.lanu.anc

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioMathTest {
    @Test
    fun silence_is_clamped() {
        val samples = ShortArray(256)
        assertEquals(-120f, AudioMath.rmsDbFs(samples), 0.01f)
    }

    @Test
    fun full_scale_sine_is_near_expected_level() {
        val samples = ShortArray(1024) { i ->
            (kotlin.math.sin(2.0 * Math.PI * i / 32.0) * 32767.0).toInt().toShort()
        }
        val db = AudioMath.rmsDbFs(samples)
        assertEquals(-3.0f, db, 0.15f)
    }
}

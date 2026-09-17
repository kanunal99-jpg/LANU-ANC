package com.lanu.anc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AncAdaptiveFilterTest {
    @Test
    fun reset_clears_state() {
        val filter = AncAdaptiveFilter(taps = 8)
        repeat(8) {
            filter.predict(0.5f)
            filter.adapt(0.25f)
        }
        assertTrue(filter.coefficientMagnitude() > 0f)
        filter.reset()
        assertEquals(0f, filter.coefficientMagnitude(), 0.000001f)
    }

    @Test
    fun zero_error_does_not_change_coefficients() {
        val filter = AncAdaptiveFilter(taps = 8)
        repeat(4) {
            filter.predict(0.5f)
            filter.adapt(0f)
        }
        assertEquals(0f, filter.coefficientMagnitude(), 0.000001f)
    }

    @Test
    fun adaptation_is_bounded_for_constant_reference() {
        val filter = AncAdaptiveFilter(taps = 16, learningRate = 0.0005f)
        repeat(200) {
            filter.predict(0.5f)
            filter.adapt(0.25f)
        }
        assertTrue(filter.coefficientMagnitude() < 0.1f)
    }
}

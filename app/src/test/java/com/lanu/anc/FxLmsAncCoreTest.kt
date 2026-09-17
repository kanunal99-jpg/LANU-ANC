package com.lanu.anc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FxLmsAncCoreTest {
    @Test
    fun reset_starts_with_zero_coefficients() {
        val core = FxLmsAncCore(taps = 8, secondaryPathTaps = floatArrayOf(1f))
        assertEquals(0f, core.coefficientMagnitude(), 0.000001f)
    }

    @Test
    fun adaptation_changes_coefficients_without_nan_or_infinity() {
        val core = FxLmsAncCore(taps = 8, secondaryPathTaps = floatArrayOf(0.8f, 0.1f))
        repeat(100) {
            core.predict(if (it % 2 == 0) 0.2f else -0.2f)
            core.adapt(0.05f)
        }
        val magnitude = core.coefficientMagnitude()
        assertTrue(magnitude > 0f)
        assertTrue(magnitude.isFinite())
        assertTrue(magnitude <= 1f)
    }

    @Test
    fun reset_returns_core_to_safe_initial_state() {
        val core = FxLmsAncCore(taps = 8, secondaryPathTaps = floatArrayOf(1f))
        repeat(20) {
            core.predict(0.25f)
            core.adapt(0.05f)
        }
        core.reset()
        assertEquals(0f, core.coefficientMagnitude(), 0.000001f)
        assertEquals(0f, core.predict(0.25f), 0.000001f)
    }
}

package com.lanu.anc

import org.junit.Assert.*
import org.junit.Test

class LatencyAlignerTest {
    @Test fun zeroDelayReturnsCurrentSample() {
        val aligner = LatencyAligner.calibrated(0, 48_000, 1f)!!
        assertEquals(1f, aligner.align(1f), 0f)
        assertEquals(-0.5f, aligner.align(-0.5f), 0f)
    }

    @Test fun configuredDelayReturnsOlderSample() {
        val aligner = LatencyAligner.calibrated(2, 48_000, 1f)!!
        assertEquals(0f, aligner.align(1f), 0f)
        assertEquals(0f, aligner.align(2f), 0f)
        assertEquals(1f, aligner.align(3f), 0f)
        assertEquals(2f, aligner.align(4f), 0f)
    }

    @Test fun invalidCalibrationIsRejected() {
        assertNull(LatencyAligner.calibrated(-1, 48_000, 1f))
        assertNull(LatencyAligner.calibrated(LatencyAligner.MAX_DELAY_SAMPLES + 1, 48_000, 1f))
        assertNull(LatencyAligner.calibrated(1, 0, 1f))
        assertNull(LatencyAligner.calibrated(1, 48_000, Float.NaN))
    }

    @Test fun resetClearsDelayHistory() {
        val aligner = LatencyAligner.calibrated(2, 48_000, 1f)!!
        aligner.align(10f)
        aligner.align(20f)
        aligner.reset()
        assertEquals(0f, aligner.align(30f), 0f)
    }
}

package com.lanu.anc

import org.junit.Assert.*
import org.junit.Test

class SecondaryPathModelTest {
    @Test fun validMeasuredModelIsAcceptedAndCopied() {
        val model = SecondaryPathModel.measured(floatArrayOf(0.5f, 0.25f), 48_000, 12, 0.9f)
        assertNotNull(model)
        val destination = FloatArray(4)
        assertTrue(model!!.copyCoefficientsInto(destination))
        assertArrayEquals(floatArrayOf(0.5f, 0.25f), destination.copyOf(2), 0f)
    }

    @Test fun invalidMeasurementIsRejected() {
        assertNull(SecondaryPathModel.measured(floatArrayOf(Float.NaN), 48_000, 0, 1f))
        assertNull(SecondaryPathModel.measured(floatArrayOf(1f), 48_000, -1, 1f))
        assertNull(SecondaryPathModel.measured(floatArrayOf(1f), 48_000, 0, 1.1f))
    }

    @Test fun destinationMustHaveEnoughCapacity() {
        val model = SecondaryPathModel.measured(floatArrayOf(1f, 2f), 48_000, 0, 1f)!!
        assertFalse(model.copyCoefficientsInto(FloatArray(1)))
    }
}

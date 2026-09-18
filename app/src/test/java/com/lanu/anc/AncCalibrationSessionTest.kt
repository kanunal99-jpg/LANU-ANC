package com.lanu.anc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AncCalibrationSessionTest {
    private val route = AncCalibrationSession.RouteIdentity(
        inputDeviceId = 11,
        outputDeviceId = 22,
        sampleRateHz = 48_000,
        inputChannels = 2,
        outputChannels = 1
    )

    @Test
    fun invalidRouteCannotStart() {
        val session = AncCalibrationSession()
        assertFalse(session.begin(route.copy(inputDeviceId = 0)))
        assertEquals(AncCalibrationSession.State.IDLE, session.state)
    }

    @Test
    fun calibrationMustFollowRecordingState() {
        val session = AncCalibrationSession()
        assertFalse(session.markRecordingComplete())
        assertEquals(AncCalibrationSession.State.IDLE, session.state)
        assertTrue(session.begin(route))
        assertTrue(session.markRecordingComplete())
        assertEquals(AncCalibrationSession.State.ESTIMATING, session.state)
    }

    @Test
    fun successfulEstimateProducesValidatedResult() {
        val expected = SecondaryPathEstimator.Result(
            SecondaryPathModel.measured(floatArrayOf(0.7f, -0.2f), 48_000, 7, 0.9f)!!,
            LatencyAligner.calibrated(7, 48_000, 0.9f)!!
        )
        val session = AncCalibrationSession(object : AncCalibrationSession.Estimator {
            override fun estimate(
                excitation: FloatArray,
                response: FloatArray,
                sampleRateHz: Int,
                taps: Int,
                maxLatencySamples: Int,
                learningRate: Float,
                minConfidence: Float
            ) = expected
        })

        assertTrue(session.begin(route))
        assertTrue(session.markRecordingComplete())
        val result = session.estimate(FloatArray(256), FloatArray(256), route)

        assertNotNull(result)
        assertEquals(AncCalibrationSession.State.VALIDATED, session.state)
        assertEquals(7, result!!.estimate.latencyAligner.delaySamples)
        assertNull(session.lastError)
    }

    @Test
    fun failedEstimateKeepsResultEmpty() {
        val session = AncCalibrationSession(object : AncCalibrationSession.Estimator {
            override fun estimate(
                excitation: FloatArray,
                response: FloatArray,
                sampleRateHz: Int,
                taps: Int,
                maxLatencySamples: Int,
                learningRate: Float,
                minConfidence: Float
            ): SecondaryPathEstimator.Result? = null
        })

        assertTrue(session.begin(route))
        assertTrue(session.markRecordingComplete())
        assertNull(session.estimate(FloatArray(256), FloatArray(256), route))
        assertEquals(AncCalibrationSession.State.FAILED, session.state)
        assertNull(session.result)
        assertFalse(session.lastError.isNullOrBlank())
    }

    @Test
    fun resetReturnsToSafeIdleState() {
        val session = AncCalibrationSession()
        session.begin(route)
        session.markRecordingComplete()
        session.fail("test")
        session.reset()
        assertEquals(AncCalibrationSession.State.IDLE, session.state)
        assertNull(session.result)
        assertNull(session.lastError)
    }
}

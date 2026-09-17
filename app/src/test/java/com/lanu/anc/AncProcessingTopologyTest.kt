package com.lanu.anc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AncProcessingTopologyTest {
    private fun calibration(): AncProcessingTopology.Validation {
        val model = SecondaryPathModel.measured(
            coefficients = floatArrayOf(0.8f, 0.15f, 0.03f),
            sampleRateHz = 48_000,
            measurementLatencySamples = 2,
            confidence = 0.95f
        )!!
        val aligner = LatencyAligner.calibrated(2, 48_000, 0.95f)!!
        return AncProcessingTopology.Validation(
            true, true, true, true, model, aligner,
            referenceDeviceId = 101, referenceChannel = 0,
            errorDeviceId = 102, errorChannel = 0, inputChannelCount = 1
        )
    }

    @Test
    fun incomplete_hardware_validation_stays_bypassed() {
        val topology = AncProcessingTopology()
        assertFalse(topology.validate(AncProcessingTopology.Validation(true, false, true, true)))
        assertFalse(topology.activate())
        assertEquals(0f, topology.process(0.5f, 0.1f), 0.000001f)
    }

    @Test
    fun single_mono_input_is_rejected_even_with_calibration() {
        val topology = AncProcessingTopology()
        val v = calibration().copy(errorDeviceId = 101, inputChannelCount = 1)
        assertFalse(topology.validate(v))
        assertEquals(AncProcessingTopology.State.BYPASS, topology.state)
    }

    @Test
    fun same_channel_on_same_device_is_rejected() {
        val topology = AncProcessingTopology()
        val v = calibration().copy(errorChannel = 0, errorDeviceId = 101, inputChannelCount = 2)
        assertFalse(topology.validate(v))
    }

    @Test
    fun physical_calibration_is_required_before_activation() {
        val topology = AncProcessingTopology()
        assertFalse(topology.validate(AncProcessingTopology.Validation(true, true, true, true)))
        assertFalse(topology.activate())
    }

    @Test
    fun validated_distinct_physical_signals_can_activate_and_process() {
        val topology = AncProcessingTopology(fxLmsTaps = 8)
        assertTrue(topology.validate(calibration()))
        assertTrue(topology.activate())
        repeat(20) { topology.process(0.1f, 0.02f) }
        assertTrue(topology.coefficientMagnitude() > 0f)
    }

    @Test
    fun mismatched_sample_rates_are_rejected() {
        val model = SecondaryPathModel.measured(floatArrayOf(1f), 48_000, 0, 1f)!!
        val aligner = LatencyAligner.calibrated(0, 44_100, 1f)!!
        val topology = AncProcessingTopology()
        val v = calibration().copy(secondaryPathModel = model, latencyAligner = aligner)
        assertFalse(topology.validate(v))
    }

    @Test
    fun deactivate_resets_filter_and_returns_to_bypass() {
        val topology = AncProcessingTopology(fxLmsTaps = 8)
        topology.validate(calibration())
        topology.activate()
        repeat(10) { topology.process(0.2f, 0.05f) }
        topology.deactivate()
        assertEquals(AncProcessingTopology.State.BYPASS, topology.state)
        assertEquals(0f, topology.coefficientMagnitude(), 0.000001f)
        assertEquals(0f, topology.process(0.2f, 0.05f), 0.000001f)
    }
}

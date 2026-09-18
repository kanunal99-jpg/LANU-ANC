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
            referenceChannelPresent = true,
            errorChannelPresent = true,
            routeValidated = true,
            latencyAligned = true,
            secondaryPathModel = model,
            latencyAligner = aligner,
            referenceDeviceId = 11,
            referenceChannel = 0,
            errorDeviceId = 12,
            errorChannel = 0,
            inputChannelCount = 2
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
    fun physical_calibration_is_required_before_activation() {
        val topology = AncProcessingTopology()
        assertFalse(topology.validate(AncProcessingTopology.Validation(true, true, true, true)))
        assertFalse(topology.activate())
    }

    @Test
    fun validated_calibration_can_activate_and_process_block() {
        val topology = AncProcessingTopology(fxLmsTaps = 8)
        assertTrue(topology.validate(calibration()))
        assertTrue(topology.activate())
        val reference = FloatArray(32) { 0.1f }
        val error = FloatArray(32) { 0.02f }
        val output = FloatArray(32)
        assertTrue(topology.processBlock(reference, error, output))
        assertTrue(output.any { it != 0f })
        assertTrue(topology.coefficientMagnitude() > 0f)
    }

    @Test
    fun mismatched_sample_rates_are_rejected() {
        val model = SecondaryPathModel.measured(floatArrayOf(1f), 48_000, 0, 1f)!!
        val aligner = LatencyAligner.calibrated(0, 44_100, 1f)!!
        val topology = AncProcessingTopology()
        assertFalse(topology.validate(AncProcessingTopology.Validation(true, true, true, true, model, aligner, 11, 0, 12, 0, 1)))
    }

    @Test
    fun same_physical_signal_is_rejected() {
        val calibration = calibration().copy(errorDeviceId = 11, errorChannel = 0)
        assertFalse(AncProcessingTopology().validate(calibration))
    }

    @Test
    fun non_finite_sample_causes_fault_and_zero_output() {
        val topology = AncProcessingTopology(fxLmsTaps = 8)
        assertTrue(topology.validate(calibration()))
        assertTrue(topology.activate())
        assertEquals(0f, topology.process(Float.NaN, 0.02f), 0.000001f)
        assertEquals(AncProcessingTopology.State.FAULT, topology.state)
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

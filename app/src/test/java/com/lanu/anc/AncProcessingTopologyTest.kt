package com.lanu.anc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AncProcessingTopologyTest {
    @Test
    fun incomplete_hardware_validation_stays_bypassed() {
        val topology = AncProcessingTopology()
        assertFalse(
            topology.validate(
                AncProcessingTopology.Validation(
                    referenceChannelPresent = true,
                    errorChannelPresent = false,
                    routeValidated = true,
                    latencyAligned = true
                )
            )
        )
        assertFalse(topology.activate())
        assertEquals(0f, topology.process(0.5f, 0.1f), 0.000001f)
    }

    @Test
    fun validated_topology_can_activate_and_process() {
        val topology = AncProcessingTopology(AncAdaptiveFilter(taps = 8))
        assertTrue(
            topology.validate(
                AncProcessingTopology.Validation(true, true, true, true)
            )
        )
        assertTrue(topology.activate())
        repeat(20) { topology.process(0.1f, 0.02f) }
        assertTrue(topology.coefficientMagnitude() > 0f)
    }

    @Test
    fun deactivate_resets_filter_and_returns_to_bypass() {
        val topology = AncProcessingTopology(AncAdaptiveFilter(taps = 8))
        topology.validate(AncProcessingTopology.Validation(true, true, true, true))
        topology.activate()
        repeat(10) { topology.process(0.2f, 0.05f) }
        topology.deactivate()
        assertEquals(AncProcessingTopology.State.BYPASS, topology.state)
        assertEquals(0f, topology.coefficientMagnitude(), 0.000001f)
        assertEquals(0f, topology.process(0.2f, 0.05f), 0.000001f)
    }
}

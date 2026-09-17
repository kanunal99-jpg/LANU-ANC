package com.lanu.anc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DspPipelineTest {
    @Test
    fun bypass_does_not_modify_samples() {
        val pipeline = DspPipeline()
        pipeline.bypass = true
        val samples = shortArrayOf(32767, -32768, 1234)
        pipeline.process(samples, samples.size)
        assertEquals(32767, samples[0].toInt())
        assertEquals(-32768, samples[1].toInt())
        assertEquals(1234, samples[2].toInt())
    }

    @Test
    fun limiter_prevents_full_scale_peaks() {
        val pipeline = DspPipeline()
        val samples = shortArrayOf(32767, -32768, 1000)
        pipeline.process(samples, samples.size)
        assertTrue(samples[0] < 32767)
        assertTrue(samples[1] > -32768)
        assertTrue(pipeline.metrics.limiterActivations >= 2)
    }

    @Test
    fun only_requested_length_is_processed() {
        val pipeline = DspPipeline()
        val samples = shortArrayOf(32767, 32767, 32767)
        pipeline.process(samples, 1)
        assertTrue(samples[0] < 32767)
        assertEquals(32767, samples[1].toInt())
        assertEquals(32767, samples[2].toInt())
    }

    @Test
    fun reset_clears_metrics() {
        val pipeline = DspPipeline()
        pipeline.process(shortArrayOf(32767), 1)
        pipeline.reset()
        assertEquals(0L, pipeline.metrics.limiterActivations)
        assertEquals(0L, pipeline.metrics.maxProcessingMicros)
    }
}

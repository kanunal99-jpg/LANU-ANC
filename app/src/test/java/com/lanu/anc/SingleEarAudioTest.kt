package com.lanu.anc

import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class SingleEarAudioTest {
    @Test fun quietBackgroundIsReducedByRealFallback() {
        val gate = BasicNoiseGate()
        val rate = 16000
        val source = ShortArray(160) { (sin(2 * PI * it / 16) * 100).toInt().toShort() }
        var output = source.copyOf()
        repeat(100) { output = source.copyOf(); gate.process(output, output.size, rate) }
        fun energy(a: ShortArray) = a.sumOf { it.toDouble() * it }
        assertTrue("quiet gap must lose >15dB", energy(output) < energy(source) * 0.032)
    }
    @Test fun speechLevelSignalRecoversAfterQuietGap() {
        val gate = BasicNoiseGate()
        repeat(80) { gate.process(ShortArray(160) { 50 }, 160, 16000) }
        val speech = ShortArray(160) { (sin(2 * PI * it / 16) * 6000).toInt().toShort() }
        var out = speech.copyOf()
        repeat(10) { out = speech.copyOf(); gate.process(out, out.size, 16000) }
        assertTrue(out.sumOf { it.toDouble() * it } > speech.sumOf { it.toDouble() * it } * 0.95)
    }
    @Test fun monoRequiresOneMicrophoneNotTwoEarbuds() {
        assertEquals(1, SingleEarPolicy.INPUT_CHANNELS)
        assertEquals(1, SingleEarPolicy.OUTPUT_CHANNELS)
        assertTrue(SingleEarPolicy.canMonitor(true, true, false))
        assertFalse(SingleEarPolicy.canMonitor(false, true, false))
        assertFalse(SingleEarPolicy.canMonitor(true, false, false))
        assertFalse(SingleEarPolicy.canMonitor(true, true, true))
    }
    @Test fun invalidGainCannotBecomeLoudOrNonFinite() {
        assertEquals(0.5f, SingleEarPolicy.gain(100f), 0f)
        assertEquals(0f, SingleEarPolicy.gain(-1f), 0f)
        assertEquals(SingleEarPolicy.DEFAULT_GAIN, SingleEarPolicy.gain(Float.NaN), 0f)
        assertEquals(SingleEarPolicy.DEFAULT_GAIN, SingleEarPolicy.gain(Float.POSITIVE_INFINITY), 0f)
    }
    @Test fun fallbackRespectsBufferBoundaryAndReset() {
        val gate = BasicNoiseGate()
        val block = shortArrayOf(50, 50, 32000)
        gate.process(block, 2, 16000)
        assertEquals(32000, block[2].toInt())
        gate.reset()
        val clean = ShortArray(160) { 6000 }
        gate.process(clean, 160, 16000)
        assertEquals(6000, clean.last().toInt())
    }
}

package com.lanu.anc

import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveAudioControllerTest {
    @Test
    fun clipping_forces_hot_input_profile() {
        val controller = AdaptiveAudioController()
        val snapshot = controller.update(-12f, clipping = true)
        assertEquals(AdaptiveAudioController.Profile.HOT_INPUT, snapshot.profile)
    }

    @Test
    fun quiet_non_speech_is_idle() {
        val controller = AdaptiveAudioController()
        val snapshot = controller.update(-70f, speechLikely = false)
        assertEquals(AdaptiveAudioController.Profile.IDLE, snapshot.profile)
    }

    @Test
    fun moderate_speech_is_clean_when_noise_floor_is_low() {
        val controller = AdaptiveAudioController()
        val snapshot = controller.update(-20f, speechLikely = true)
        assertEquals(AdaptiveAudioController.Profile.SPEECH_CLEAN, snapshot.profile)
    }

    @Test
    fun reset_returns_to_idle_snapshot() {
        val controller = AdaptiveAudioController()
        controller.update(-20f)
        controller.reset()
        assertEquals(AdaptiveAudioController.Profile.IDLE, controller.snapshot.profile)
        assertEquals(-120f, controller.snapshot.inputDbFs, 0.01f)
    }
}

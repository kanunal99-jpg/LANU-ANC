package com.lanu.anc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AncCapabilitiesTest {
    @Test
    fun dualChannelInputIsRequiredForReferenceErrorCapability() {
        val base = AncCapabilities(
            apiLevel = 35,
            lowLatencyFeature = true,
            proAudioFeature = true,
            hasCommunicationDevice = true,
            inputDeviceTypes = setOf(android.media.AudioDeviceInfo.TYPE_USB_HEADSET),
            outputDeviceTypes = setOf(android.media.AudioDeviceInfo.TYPE_USB_HEADSET),
            nativeAaudioAvailable = true,
            hasPotentialDualChannelInput = false
        )
        assertFalse(base.canProvideReferenceErrorChannels)

        val dual = base.copy(hasPotentialDualChannelInput = true)
        assertTrue(dual.canProvideReferenceErrorChannels)
    }

    @Test
    fun dualChannelHintDoesNotClaimPhysicalSignalSeparation() {
        val capabilities = AncCapabilities(
            apiLevel = 35,
            lowLatencyFeature = true,
            proAudioFeature = true,
            hasCommunicationDevice = true,
            inputDeviceTypes = setOf(android.media.AudioDeviceInfo.TYPE_USB_HEADSET),
            outputDeviceTypes = setOf(android.media.AudioDeviceInfo.TYPE_USB_HEADSET),
            nativeAaudioAvailable = true,
            hasPotentialDualChannelInput = true
        )
        assertTrue(capabilities.canProvideReferenceErrorChannels)
        // Physical independence remains a runtime validation responsibility.
        assertTrue(capabilities.hasPotentialDualChannelInput)
    }
}

package com.lanu.anc

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/**
 * Hardware/OS capability gate for real ANC work.
 * A device being low-latency capable does NOT mean it has an ANC-capable mic topology.
 */
data class AncCapabilities(
    val apiLevel: Int,
    val lowLatencyFeature: Boolean,
    val proAudioFeature: Boolean,
    val hasCommunicationDevice: Boolean,
    val inputDeviceTypes: Set<Int>,
    val outputDeviceTypes: Set<Int>,
    val nativeAaudioAvailable: Boolean,
    val hasPotentialDualChannelInput: Boolean
) {
    val canAttemptNativeLowLatency: Boolean
        get() = apiLevel >= Build.VERSION_CODES.O && nativeAaudioAvailable && lowLatencyFeature

    /** True only when an external route exposes a communication-capable input/output pair. */
    val hasExternalDuplexRoute: Boolean
        get() = hasCommunicationDevice && inputDeviceTypes.any { it in EXTERNAL_TYPES } &&
            outputDeviceTypes.any { it in EXTERNAL_TYPES }

    /**
     * Capability hint only: this does not prove that the two channels are physically
     * independent reference/error microphones. Runtime route validation must still prove it.
     */
    val canProvideReferenceErrorChannels: Boolean
        get() = hasExternalDuplexRoute && hasPotentialDualChannelInput

    companion object {
        private val EXTERNAL_TYPES = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET
        )

        fun detect(context: Context): AncCapabilities {
            val pm = context.packageManager
            val audioManager = context.getSystemService(AudioManager::class.java)
            val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
            val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
            val communication = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.availableCommunicationDevices.isNotEmpty()
            } else {
                outputs.any { it.type in EXTERNAL_TYPES }
            }
            val dualChannelInput = inputs.any { device ->
                device.type in EXTERNAL_TYPES && device.channelCounts.any { it >= 2 }
            }
            return AncCapabilities(
                apiLevel = Build.VERSION.SDK_INT,
                lowLatencyFeature = pm.hasSystemFeature(PackageManager.FEATURE_AUDIO_LOW_LATENCY),
                proAudioFeature = pm.hasSystemFeature(PackageManager.FEATURE_AUDIO_PRO),
                hasCommunicationDevice = communication,
                inputDeviceTypes = inputs.map { it.type }.toSet(),
                outputDeviceTypes = outputs.map { it.type }.toSet(),
                nativeAaudioAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && NativeAudioEngine.isAvailable(),
                hasPotentialDualChannelInput = dualChannelInput
            )
        }
    }
}

package com.lanu.anc

/** Pure routing contract: one microphone and mono output never require an ANC mic pair. */
object SingleEarPolicy {
    const val INPUT_CHANNELS = 1
    const val OUTPUT_CHANNELS = 1
    const val DEFAULT_GAIN = 0.15f
    const val MAX_GAIN = 0.5f
    fun gain(value: Float): Float = if (value.isFinite()) value.coerceIn(0f, MAX_GAIN) else DEFAULT_GAIN
    fun canMonitor(hasHeadphoneOutput: Boolean, microphoneGranted: Boolean, callActive: Boolean): Boolean =
        hasHeadphoneOutput && microphoneGranted && !callActive
}

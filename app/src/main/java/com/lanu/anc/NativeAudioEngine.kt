package com.lanu.anc

/** JNI facade for the real-time native AAudio ANC engine. */
object NativeAudioEngine {
    private var loaded = false

    init {
        try {
            System.loadLibrary("lanu_audio_native")
            loaded = true
        } catch (_: UnsatisfiedLinkError) {
            loaded = false
        }
    }

    fun isAvailable(): Boolean = loaded

    fun configureAnc(
        secondaryPathTaps: FloatArray,
        latencySamples: Int,
        confidence: Float,
        inputDeviceId: Int,
        outputDeviceId: Int,
        sampleRateHz: Int,
        inputChannels: Int,
        referenceChannel: Int = 0,
        errorChannel: Int = 1
    ): Boolean = loaded && lanuNativeConfigureAnc(secondaryPathTaps, latencySamples, confidence, inputDeviceId, outputDeviceId, sampleRateHz, inputChannels, referenceChannel, errorChannel)

    fun clearAncConfiguration() { if (loaded) lanuNativeClearAncConfiguration() }
    fun ancConfigured(): Boolean = loaded && lanuNativeAncConfigured()
    fun ancFaulted(): Boolean = loaded && lanuNativeAncFaulted()
    fun ancFaultCode(): Int = if (loaded) lanuNativeAncFaultCode() else 0
    fun start(inputDeviceId: Int, outputDeviceId: Int): Boolean = loaded && lanuNativeStart(inputDeviceId, outputDeviceId)
    fun start(deviceId: Int): Boolean = start(deviceId, deviceId)
    fun stop() { if (loaded) lanuNativeStop() }
    fun isRunning(): Boolean = loaded && lanuNativeIsRunning()
    fun sampleRate(): Int = if (loaded) lanuNativeSampleRate() else 0
    fun framesPerBurst(): Int = if (loaded) lanuNativeFramesPerBurst() else 0
    fun bufferSizeInFrames(): Int = if (loaded) lanuNativeBufferSizeInFrames() else 0
    fun xRunCount(): Int = if (loaded) lanuNativeXRunCount() else 0
    fun inputDeviceId(): Int = if (loaded) lanuNativeInputDeviceId() else 0
    fun outputDeviceId(): Int = if (loaded) lanuNativeOutputDeviceId() else 0
    fun inputChannelCount(): Int = if (loaded) lanuNativeInputChannelCount() else 0
    fun outputChannelCount(): Int = if (loaded) lanuNativeOutputChannelCount() else 0

    private external fun lanuNativeConfigureAnc(secondaryPathTaps: FloatArray, latencySamples: Int, confidence: Float, inputDeviceId: Int, outputDeviceId: Int, sampleRateHz: Int, inputChannels: Int, referenceChannel: Int, errorChannel: Int): Boolean
    private external fun lanuNativeClearAncConfiguration()
    private external fun lanuNativeAncConfigured(): Boolean
    private external fun lanuNativeAncFaulted(): Boolean
    private external fun lanuNativeAncFaultCode(): Int
    private external fun lanuNativeStart(inputDeviceId: Int, outputDeviceId: Int): Boolean
    private external fun lanuNativeStop()
    private external fun lanuNativeIsRunning(): Boolean
    private external fun lanuNativeSampleRate(): Int
    private external fun lanuNativeFramesPerBurst(): Int
    private external fun lanuNativeBufferSizeInFrames(): Int
    private external fun lanuNativeXRunCount(): Int
    private external fun lanuNativeInputDeviceId(): Int
    private external fun lanuNativeOutputDeviceId(): Int
    private external fun lanuNativeInputChannelCount(): Int
    private external fun lanuNativeOutputChannelCount(): Int
}

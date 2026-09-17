package com.lanu.anc

/**
 * Thin JNI facade for the experimental native AAudio duplex engine.
 * The existing Kotlin AudioEngine remains the safe fallback until
 * physical-device validation proves the native path stable.
 */
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

    /** Start a duplex stream with independently requested input/output routes. */
    fun start(inputDeviceId: Int, outputDeviceId: Int): Boolean =
        loaded && lanuNativeStart(inputDeviceId, outputDeviceId)

    /** Backward-compatible convenience for a single route ID. */
    fun start(deviceId: Int): Boolean = start(deviceId, deviceId)

    fun stop() {
        if (loaded) lanuNativeStop()
    }

    fun isRunning(): Boolean = loaded && lanuNativeIsRunning()

    fun sampleRate(): Int = if (loaded) lanuNativeSampleRate() else 0
    fun framesPerBurst(): Int = if (loaded) lanuNativeFramesPerBurst() else 0
    fun bufferSizeInFrames(): Int = if (loaded) lanuNativeBufferSizeInFrames() else 0
    fun xRunCount(): Int = if (loaded) lanuNativeXRunCount() else 0
    fun inputDeviceId(): Int = if (loaded) lanuNativeInputDeviceId() else 0
    fun outputDeviceId(): Int = if (loaded) lanuNativeOutputDeviceId() else 0

    private external fun lanuNativeStart(inputDeviceId: Int, outputDeviceId: Int): Boolean
    private external fun lanuNativeStop()
    private external fun lanuNativeIsRunning(): Boolean
    private external fun lanuNativeSampleRate(): Int
    private external fun lanuNativeFramesPerBurst(): Int
    private external fun lanuNativeBufferSizeInFrames(): Int
    private external fun lanuNativeXRunCount(): Int
    private external fun lanuNativeInputDeviceId(): Int
    private external fun lanuNativeOutputDeviceId(): Int
}

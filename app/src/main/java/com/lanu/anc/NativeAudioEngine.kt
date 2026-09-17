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

    fun start(): Boolean = loaded && lanuNativeStart()

    fun stop() {
        if (loaded) lanuNativeStop()
    }

    fun isRunning(): Boolean = loaded && lanuNativeIsRunning()

    private external fun lanuNativeStart(): Boolean
    private external fun lanuNativeStop()
    private external fun lanuNativeIsRunning(): Boolean
}

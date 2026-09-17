package com.lanu.anc

/**
 * Deterministic control layer for real-time audio policy decisions.
 *
 * This class deliberately does not modify PCM. It converts measured input
 * telemetry into a conservative processing profile so future DSP/native
 * stages can consume one validated decision instead of making ad-hoc choices.
 */
class AdaptiveAudioController {
    enum class Profile {
        IDLE,
        SPEECH_CLEAN,
        NOISY_SPEECH,
        HOT_INPUT
    }

    data class Snapshot(
        val profile: Profile = Profile.IDLE,
        val inputDbFs: Float = -120f,
        val estimatedNoiseDbFs: Float = -120f,
        val clipping: Boolean = false,
        val speechLikely: Boolean = false
    )

    @Volatile
    var snapshot: Snapshot = Snapshot()
        private set

    fun reset() {
        snapshot = Snapshot()
    }

    fun update(inputDbFs: Float, clipping: Boolean = false, speechLikely: Boolean = true): Snapshot {
        val level = inputDbFs.coerceIn(-120f, 0f)
        val previousNoise = snapshot.estimatedNoiseDbFs
        val noise = if (speechLikely) {
            // Track quiet frames more aggressively; never treat a hot speech frame as noise.
            if (level < -45f) previousNoise * 0.8f + level * 0.2f else previousNoise
        } else {
            previousNoise * 0.8f + level * 0.2f
        }.coerceIn(-120f, 0f)

        val profile = when {
            clipping || level > -3f -> Profile.HOT_INPUT
            !speechLikely && level < -55f -> Profile.IDLE
            noise > -42f -> Profile.NOISY_SPEECH
            else -> Profile.SPEECH_CLEAN
        }

        return Snapshot(profile, level, noise, clipping, speechLikely).also { snapshot = it }
    }
}

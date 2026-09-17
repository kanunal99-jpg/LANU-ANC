package com.lanu.anc

/**
 * Safety-first topology contract for the future active-noise-control path.
 *
 * This layer deliberately does not claim that a normal Android microphone/speaker
 * route is an ANC-capable hardware topology. Active anti-noise remains bypassed
 * until both reference and error channels, route identity and latency alignment
 * have been validated on the physical device.
 */
class AncProcessingTopology(
    private val adaptiveFilter: AncAdaptiveFilter = AncAdaptiveFilter()
) {
    enum class State { BYPASS, READY, ACTIVE, FAULT }

    data class Validation(
        val referenceChannelPresent: Boolean,
        val errorChannelPresent: Boolean,
        val routeValidated: Boolean,
        val latencyAligned: Boolean
    ) {
        val canActivate: Boolean
            get() = referenceChannelPresent && errorChannelPresent && routeValidated && latencyAligned
    }

    @Volatile
    var state: State = State.BYPASS
        private set

    fun validate(validation: Validation): Boolean {
        state = if (validation.canActivate) State.READY else State.BYPASS
        return validation.canActivate
    }

    fun activate(): Boolean {
        if (state != State.READY) return false
        state = State.ACTIVE
        return true
    }

    fun deactivate() {
        adaptiveFilter.reset()
        state = State.BYPASS
    }

    /**
     * Processes one aligned reference/error pair. No output is emitted unless
     * the topology has explicitly passed validation and activation.
     */
    fun process(reference: Float, error: Float): Float {
        if (state != State.ACTIVE) return 0f
        val antiNoise = adaptiveFilter.predict(reference)
        adaptiveFilter.adapt(error)
        return antiNoise.coerceIn(-0.8912509f, 0.8912509f)
    }

    fun coefficientMagnitude(): Float = adaptiveFilter.coefficientMagnitude()
}

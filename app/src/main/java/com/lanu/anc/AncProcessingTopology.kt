package com.lanu.anc

/**
 * Safety-first topology contract for the future active-noise-control path.
 *
 * Active anti-noise remains bypassed until physical reference/error channels,
 * route identity, latency calibration and a measured secondary-path model are
 * all validated. Calibration objects are created outside the realtime path.
 */
class AncProcessingTopology(
    private val adaptiveFilter: AncAdaptiveFilter = AncAdaptiveFilter(),
    private val fxLmsTaps: Int = 64,
    private val fxLmsLearningRate: Float = 0.01f,
    private val fxLmsMaxCoefficient: Float = 1f
) {
    enum class State { BYPASS, READY, ACTIVE, FAULT }

    data class Validation(
        val referenceChannelPresent: Boolean,
        val errorChannelPresent: Boolean,
        val routeValidated: Boolean,
        val latencyAligned: Boolean,
        val secondaryPathModel: SecondaryPathModel? = null,
        val latencyAligner: LatencyAligner? = null
    ) {
        val canActivate: Boolean
            get() = referenceChannelPresent &&
                errorChannelPresent &&
                routeValidated &&
                latencyAligned &&
                secondaryPathModel?.isValid == true &&
                latencyAligner?.isValid == true &&
                secondaryPathModel.sampleRateHz == latencyAligner.sampleRateHz
    }

    @Volatile
    var state: State = State.BYPASS
        private set

    private var fxLms: FxLmsAncCore? = null
    private var aligner: LatencyAligner? = null

    fun validate(validation: Validation): Boolean {
        if (!validation.canActivate) {
            fxLms = null
            aligner = null
            state = State.BYPASS
            return false
        }

        val model = validation.secondaryPathModel!!
        fxLms = runCatching {
            FxLmsAncCore(
                taps = minOf(fxLmsTaps, model.coefficients.size.coerceAtLeast(1)),
                secondaryPathTaps = model.coefficients.copyOf(),
                learningRate = fxLmsLearningRate,
                maxCoefficient = fxLmsMaxCoefficient
            )
        }.getOrNull()
        aligner = validation.latencyAligner
        state = if (fxLms != null) State.READY else State.BYPASS
        return state == State.READY
    }

    fun activate(): Boolean {
        if (state != State.READY || fxLms == null || aligner == null) return false
        state = State.ACTIVE
        return true
    }

    fun deactivate() {
        fxLms?.reset()
        adaptiveFilter.reset()
        aligner?.reset()
        state = State.BYPASS
    }

    /** Allocation-free processing once calibrated and activated. */
    fun process(reference: Float, error: Float): Float {
        if (state != State.ACTIVE) return 0f
        val alignedReference = aligner?.align(reference) ?: return 0f
        val core = fxLms ?: return 0f
        val antiNoise = core.predict(alignedReference)
        core.adapt(error)
        return antiNoise.coerceIn(-0.8912509f, 0.8912509f)
    }

    fun coefficientMagnitude(): Float =
        fxLms?.coefficientMagnitude() ?: adaptiveFilter.coefficientMagnitude()
}

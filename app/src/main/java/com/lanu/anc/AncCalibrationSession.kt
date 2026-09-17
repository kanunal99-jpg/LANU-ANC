package com.lanu.anc

/**
 * Foreground/offline calibration state machine.
 *
 * Calibration never authorizes live ANC by itself: the caller must still validate
 * the measured route/reference/error topology before activating AncProcessingTopology.
 * No synthetic calibration data is accepted or generated here.
 */
class AncCalibrationSession(
    private val estimator: Estimator = DefaultEstimator
) {
    enum class State { IDLE, RECORDING, ESTIMATING, VALIDATED, FAILED }

    data class RouteIdentity(
        val inputDeviceId: Int,
        val outputDeviceId: Int,
        val sampleRateHz: Int,
        val inputChannels: Int = 1,
        val outputChannels: Int = 1
    ) {
        val isValid: Boolean
            get() = inputDeviceId > 0 && outputDeviceId > 0 &&
                sampleRateHz > 0 && inputChannels > 0 && outputChannels > 0
    }

    data class Result(
        val route: RouteIdentity,
        val estimate: SecondaryPathEstimator.Result
    )

    interface Estimator {
        fun estimate(
            excitation: FloatArray,
            response: FloatArray,
            sampleRateHz: Int,
            taps: Int,
            maxLatencySamples: Int,
            learningRate: Float,
            minConfidence: Float
        ): SecondaryPathEstimator.Result?
    }

    private object DefaultEstimator : Estimator {
        override fun estimate(
            excitation: FloatArray,
            response: FloatArray,
            sampleRateHz: Int,
            taps: Int,
            maxLatencySamples: Int,
            learningRate: Float,
            minConfidence: Float
        ) = SecondaryPathEstimator.estimate(
            excitation, response, sampleRateHz, taps,
            maxLatencySamples, learningRate, minConfidence
        )
    }

    @Volatile var state: State = State.IDLE
        private set
    @Volatile var lastError: String? = null
        private set
    @Volatile var result: Result? = null
        private set

    fun begin(route: RouteIdentity): Boolean {
        if (!route.isValid || state == State.RECORDING || state == State.ESTIMATING) return false
        state = State.RECORDING
        lastError = null
        result = null
        return true
    }

    fun markRecordingComplete(): Boolean {
        if (state != State.RECORDING) return false
        state = State.ESTIMATING
        return true
    }

    fun estimate(
        excitation: FloatArray,
        response: FloatArray,
        route: RouteIdentity,
        taps: Int = 64,
        maxLatencySamples: Int = LatencyAligner.MAX_DELAY_SAMPLES,
        learningRate: Float = 0.5f,
        minConfidence: Float = 0.65f
    ): Result? {
        if (state != State.ESTIMATING || !route.isValid) {
            fail("Kalibrasyon durumu veya rota geçersiz.")
            return null
        }
        val estimated = estimator.estimate(
            excitation, response, route.sampleRateHz,
            taps, maxLatencySamples, learningRate, minConfidence
        )
        if (estimated == null || !estimated.model.isValid || !estimated.latencyAligner.isValid) {
            fail("Ölçüm güvenilirliği yetersiz; ANC bypass korunuyor.")
            return null
        }
        result = Result(route, estimated)
        state = State.VALIDATED
        lastError = null
        return result
    }

    fun fail(message: String) {
        lastError = message.ifBlank { "Kalibrasyon başarısız." }
        result = null
        state = State.FAILED
    }

    fun reset() {
        state = State.IDLE
        lastError = null
        result = null
    }
}

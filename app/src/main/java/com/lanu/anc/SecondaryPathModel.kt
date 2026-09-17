package com.lanu.anc

/**
 * Measured loudspeaker/ear-canal secondary-path model used by FxLMS.
 *
 * The coefficients are deliberately immutable from the realtime processing side:
 * callers must construct a new model from a measured calibration result.
 * An unmeasured/invalid model can never authorize ANC activation.
 */
class SecondaryPathModel private constructor(
    coefficients: FloatArray,
    val sampleRateHz: Int,
    val measurementLatencySamples: Int,
    val confidence: Float
) {
    val coefficients: FloatArray = coefficients.copyOf()
    val isValid: Boolean = sampleRateHz > 0 &&
        measurementLatencySamples >= 0 &&
        confidence in 0f..1f &&
        this.coefficients.isNotEmpty() &&
        this.coefficients.all { it.isFinite() }

    fun copyCoefficientsInto(destination: FloatArray): Boolean {
        if (!isValid || destination.size < coefficients.size) return false
        coefficients.copyInto(destination, endIndex = coefficients.size)
        return true
    }

    companion object {
        fun measured(
            coefficients: FloatArray,
            sampleRateHz: Int,
            measurementLatencySamples: Int,
            confidence: Float
        ): SecondaryPathModel? {
            val model = SecondaryPathModel(
                coefficients,
                sampleRateHz,
                measurementLatencySamples,
                confidence
            )
            return model.takeIf { it.isValid }
        }
    }
}

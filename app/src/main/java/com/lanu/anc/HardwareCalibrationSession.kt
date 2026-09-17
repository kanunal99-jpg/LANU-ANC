package com.lanu.anc

import android.content.Context

/** Coordinates the calibration state machine with a real Android audio route. */
class HardwareCalibrationSession(context: Context) {
    private val session = AncCalibrationSession()
    private val controller = HardwareCalibrationController(context)

    data class Outcome(
        val state: AncCalibrationSession.State,
        val result: AncCalibrationSession.Result? = null,
        val error: String? = null
    )

    fun run(): Outcome {
        val measured = runCatching { controller.measure() }.getOrNull()
            ?: return Outcome(AncCalibrationSession.State.FAILED, error = "Harici giriş/çıkış rotası veya gerçek ölçüm alınamadı.")
        if (!session.begin(measured.route)) return Outcome(AncCalibrationSession.State.FAILED, error = session.lastError)
        session.markRecordingComplete()
        val result = session.estimate(measured.excitation, measured.response, measured.route)
        return Outcome(session.state, result, session.lastError)
    }

    fun reset() = session.reset()
    fun state(): AncCalibrationSession.State = session.state
    fun result(): AncCalibrationSession.Result? = session.result
    fun error(): String? = session.lastError
}

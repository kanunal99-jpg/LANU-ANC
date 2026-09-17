package com.lanu.anc

/** Coordinates the calibration state machine with a real Android audio route. */
class HardwareCalibrationSession(
    private val measurement: HardwareCalibrationController = HardwareCalibrationControllerPlaceholder.unavailable()
) {
    private val session = AncCalibrationSession()

    data class Outcome(
        val state: AncCalibrationSession.State,
        val result: AncCalibrationSession.Result? = null,
        val error: String? = null
    )

    fun run(): Outcome {
        val controller = measurement as? HardwareCalibrationController
            ?: return Outcome(AncCalibrationSession.State.FAILED, error = "Gerçek cihaz ölçüm sağlayıcısı yok.")
        val measured = runCatching { controller.measure() }.getOrNull()
            ?: return Outcome(AncCalibrationSession.State.FAILED, error = "Harici giriş/çıkış rotası veya gerçek ölçüm alınamadı.")

        if (!session.begin(measured.route)) {
            return Outcome(AncCalibrationSession.State.FAILED, error = session.lastError)
        }
        session.markRecordingComplete()
        val result = session.estimate(
            excitation = measured.excitation,
            response = measured.response,
            route = measured.route
        )
        return Outcome(session.state, result, session.lastError)
    }

    fun reset() = session.reset()
    fun state(): AncCalibrationSession.State = session.state
    fun result(): AncCalibrationSession.Result? = session.result
    fun error(): String? = session.lastError

    /** Internal factory hook keeps the public class deterministic for unit tests. */
    private object HardwareCalibrationControllerPlaceholder {
        fun unavailable(): HardwareCalibrationController = throw IllegalStateException("Use HardwareCalibrationSession(context).")
    }
}

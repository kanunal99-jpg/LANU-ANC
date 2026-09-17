package com.lanu.anc

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

class MainActivity : android.app.Activity() {
    private companion object {
        const val REQ_AUDIO = 10
        const val REQ_NOTIFY = 11
    }

    private var service: AudioProcessingService? = null
    private var bound = false
    private val handler = Handler(Looper.getMainLooper())
    private val calibrationExecutor = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var level: TextView
    private lateinit var route: TextView
    private lateinit var effects: TextView
    private lateinit var calibration: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var calibrateButton: Button

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as? AudioProcessingService.LocalBinder)?.service()
            bound = true
            render()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            service = null
            render()
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        requestPermissionsIfNeeded()
        handler.post(ticker)
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, AudioProcessingService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        if (bound) {
            unbindService(connection)
            bound = false
            service = null
        }
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        calibrationExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun buildUi(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 30f
        }, fullWidth())
        root.addView(TextView(this).apply {
            text = getString(R.string.subtitle)
            textSize = 16f
            setPadding(0, dp(8), 0, dp(20))
        }, fullWidth())
        root.addView(TextView(this).apply {
            text = getString(R.string.safety_note)
            textSize = 14f
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }, fullWidth())

        status = label("Durum: Hazır")
        level = label("Mikrofon seviyesi: -120 dBFS")
        route = label("Çıkış: -")
        effects = label("İşleme: bekleniyor")
        calibration = label("Kalibrasyon: yapılmadı")
        root.addView(status, fullWidth())
        root.addView(level, fullWidth())
        root.addView(route, fullWidth())
        root.addView(effects, fullWidth())
        root.addView(calibration, fullWidth())

        startButton = Button(this).apply {
            text = getString(R.string.start)
            setOnClickListener { startEngine() }
        }
        stopButton = Button(this).apply {
            text = getString(R.string.stop)
            setOnClickListener { stopEngine() }
        }
        calibrateButton = Button(this).apply {
            text = "Gerçek cihazı kalibre et"
            setOnClickListener { calibrateHardware() }
        }
        root.addView(startButton, fullWidth())
        root.addView(stopButton, fullWidth())
        root.addView(calibrateButton, fullWidth())
        root.addView(TextView(this).apply {
            text = getString(R.string.footer)
            textSize = 12f
            setPadding(0, dp(22), 0, 0)
        }, fullWidth())
        return ScrollView(this).apply { addView(root) }
    }

    private fun startEngine() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionsIfNeeded()
            return
        }
        if (!bound) {
            bindService(Intent(this, AudioProcessingService::class.java), connection, Context.BIND_AUTO_CREATE)
            status.text = "Durum: Servis bağlanıyor…"
            return
        }
        val intent = Intent(this, AudioProcessingService::class.java).setAction(AudioProcessingService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        service?.startProcessing()
        render()
    }

    private fun stopEngine() {
        service?.stopProcessing()
        stopService(Intent(this, AudioProcessingService::class.java))
        render()
    }

    private fun calibrateHardware() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionsIfNeeded()
            return
        }
        val s = service ?: return
        calibrateButton.isEnabled = false
        calibration.text = "Kalibrasyon: gerçek cihaz ölçümü yapılıyor…"
        calibrationExecutor.execute {
            val outcome = s.calibrateHardware()
            runOnUiThread {
                calibration.text = when (outcome.state) {
                    AncCalibrationSession.State.VALIDATED -> "Kalibrasyon: DOĞRULANDI • gerçek ölçüm"
                    AncCalibrationSession.State.FAILED -> "Kalibrasyon: BAŞARISIZ • ${outcome.error ?: "ölçüm güvenilir değil"}"
                    else -> "Kalibrasyon: ${outcome.state}"
                }
                calibrateButton.isEnabled = true
                render()
            }
        }
    }

    private fun render() {
        val s = service
        if (s == null) {
            status.text = "Durum: Servis hazır değil"
            level.text = "Mikrofon seviyesi: -120 dBFS"
            route.text = "Çıkış: -"
            effects.text = "İşleme: bekleniyor"
            calibration.text = "Kalibrasyon: yapılmadı"
            startButton.isEnabled = true
            stopButton.isEnabled = false
            return
        }
        status.text = when {
            s.error() != null -> "Durum: HATA — ${s.error()}"
            s.isAncActive() -> "Durum: ANC AKTİF"
            s.isRunning() -> "Durum: ÇALIŞIYOR • güvenli bypass"
            else -> "Durum: Hazır"
        }
        level.text = "Mikrofon seviyesi: ${"%.1f".format(s.inputDbFs())} dBFS"
        route.text = "Çıkış: ${s.routeName()}"
        effects.text = when {
            s.isAncActive() -> "İşleme: Native AAudio ANC • gerçek 2-kanal giriş • FxLMS"
            s.isRunning() -> "İşleme: güvenli bypass • NS=${flag(s.nsActive())} • AEC=${flag(s.aecActive())} • AGC=${flag(s.agcActive())}"
            s.error() != null -> "İşleme: güvenli duruş • fault=${s.ancFaultCode()}"
            else -> "İşleme: bekleniyor"
        }
        if (s.calibrationState() == AncCalibrationSession.State.VALIDATED) calibration.text = "Kalibrasyon: DOĞRULANDI • gerçek ölçüm"
        startButton.isEnabled = !s.isRunning()
        stopButton.isEnabled = s.isRunning()
        calibrateButton.isEnabled = calibrationExecutor.isShutdown.not()
    }

    private fun requestPermissionsIfNeeded() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
        }
    }

    private fun flag(active: Boolean) = if (active) "AÇIK" else "YOK"
    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 16f
        setPadding(0, dp(10), 0, dp(2))
    }
    private fun fullWidth() = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

package com.lanu.anc

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import android.util.Log
import java.util.concurrent.Executors

class AudioProcessingService : Service() {
    companion object {
        const val ACTION_START = "com.lanu.anc.START"
        const val ACTION_STOP = "com.lanu.anc.STOP"
        const val CHANNEL_ID = "lanu_single_ear"
        const val NOTIFICATION_ID = 1001
    }
    inner class LocalBinder : Binder() { fun service() = this@AudioProcessingService }
    private val binder = LocalBinder()
    private lateinit var engine: AudioEngine
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    @Volatile var preparing = false; private set
    @Volatile private var serviceError: String? = null
    private var foreground = false
    private val watcher = object : Runnable {
        override fun run() {
            if (!preparing && !engine.running && foreground) { removeForeground(); stopSelf() }
            else if (foreground) updateNotification()
            handler.postDelayed(this, 1000)
        }
    }
    override fun onCreate() {
        super.onCreate()
        engine = AudioEngine(this)
        val channel = NotificationChannel(CHANNEL_ID, "LANU mikrofon dinleme", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        handler.post(watcher)
    }
    override fun onBind(intent: Intent): IBinder = binder
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) { ACTION_START -> startProcessing(); ACTION_STOP -> stopProcessing() }
        return START_NOT_STICKY
    }
    private fun startProcessing() {
        if (preparing || engine.running) return
        serviceError = null
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTIFICATION_ID, notification("Başlatılıyor…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(NOTIFICATION_ID, notification("Başlatılıyor…"))
            foreground = true
            preparing = true
            executor.execute {
                engine.start()
                handler.post { preparing = false; if (engine.running) updateNotification() else { removeForeground(); stopSelf() } }
            }
        } catch (e: Exception) {
            preparing = false
            serviceError = "Mikrofon servisi başlatılamadı: ${e.message ?: "izinleri kontrol edin"}"
            Log.w("LANU", serviceError, e)
            removeForeground(); stopSelf()
        }
    }
    fun stopProcessing() {
        executor.execute { engine.stop(); handler.post { preparing = false; removeForeground(); stopSelf() } }
    }
    fun setGain(value: Float) = engine.setGain(value)
    fun setReduction(enabled: Boolean) = engine.setReduction(enabled)
    fun isRunning() = engine.running
    fun state() = engine.state
    fun error() = serviceError ?: engine.lastError
    fun inputDbFs() = engine.inputDbFs
    fun routeName() = engine.routeName
    fun processingName() = engine.processingName
    fun sampleRate() = engine.sampleRate
    fun gain() = engine.gain
    fun reductionEnabled() = engine.reductionEnabled
    private fun removeForeground() {
        if (foreground) stopForeground(STOP_FOREGROUND_REMOVE)
        foreground = false
    }
    private fun updateNotification() = getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification("Tek kulaklık • ${engine.processingName}"))
    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, AudioProcessingService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL_ID).setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("LANU Tek Kulaklık").setContentText(text.take(120)).setContentIntent(open)
            .setOngoing(true).addAction(Notification.Action.Builder(null, "Durdur", stop).build()).build()
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        engine.stop()
        executor.shutdownNow()
        removeForeground()
        super.onDestroy()
    }
}

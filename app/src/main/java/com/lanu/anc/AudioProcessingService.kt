package com.lanu.anc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder

class AudioProcessingService : Service() {
    companion object {
        const val ACTION_START = "com.lanu.anc.START"
        const val ACTION_STOP = "com.lanu.anc.STOP"
        const val CHANNEL_ID = "lanu_anc_engine"
        const val NOTIFICATION_ID = 1001
    }

    private val binder = LocalBinder()
    private lateinit var engine: AudioEngine

    inner class LocalBinder : Binder() {
        fun service(): AudioProcessingService = this@AudioProcessingService
    }

    override fun onCreate() {
        super.onCreate()
        engine = AudioEngine(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopProcessing()
                stopSelf()
            }
            ACTION_START -> startProcessing()
        }
        return START_NOT_STICKY
    }

    fun startProcessing(): Boolean {
        startForeground(NOTIFICATION_ID, buildNotification("Başlatılıyor…"))
        val started = engine.start()
        updateNotification()
        return started
    }

    fun stopProcessing() {
        engine.stop()
        updateNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    fun isRunning(): Boolean = engine.running
    fun inputDbFs(): Float = engine.inputDbFs
    fun routeName(): String = engine.routeName
    fun nsActive(): Boolean = engine.noiseSuppressorActive
    fun aecActive(): Boolean = engine.echoCancelerActive
    fun agcActive(): Boolean = engine.agcActive
    fun error(): String? = engine.lastError

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        engine.stop()
        super.onDestroy()
    }

    private fun updateNotification() {
        if (!::engine.isInitialized) return
        val text = when {
            engine.running -> "Çalışıyor • ${engine.routeName}"
            engine.lastError != null -> "Hata: ${engine.lastError}"
            else -> "Hazır"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("LANU ANC")
            .setContentText(text.take(120))
            .setOngoing(engineOrFalse())
            .build()
    }

    private fun engineOrFalse(): Boolean = ::engine.isInitialized && engine.running

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "LANU ANC ses motoru",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "LANU ANC mikrofon gürültü azaltma motoru"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}

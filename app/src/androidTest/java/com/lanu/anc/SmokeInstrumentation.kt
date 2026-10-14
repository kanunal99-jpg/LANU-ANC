package com.lanu.anc

import android.app.Instrumentation
import android.Manifest
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.*
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Android runtime smoke, using silence only. Does not claim a physical headset test. */
class SmokeInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val result = Bundle()
        try {
            val activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitForIdleSync()
            runOnMainSync { check(activity.findViewById<Button>(103).text.toString().contains("başlat")) }
            val engine = AudioEngine(targetContext)
            check(!engine.start()) { "Emulator without headphones must not start speaker monitoring" }
            check(engine.lastError != null)
            engine.stop()
            check(engine.state == AudioEngine.State.IDLE)
            if (targetContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                check(engine.lastError?.contains("Mikrofon") == true)
                result.putString("stream", "OK (1): real microphone permission denial is handled safely")
                finish(android.app.Activity.RESULT_OK, result)
                return
            }
            val connected = CountDownLatch(1)
            var service: AudioProcessingService? = null
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    service = (binder as AudioProcessingService.LocalBinder).service()
                    connected.countDown()
                }
                override fun onServiceDisconnected(name: ComponentName?) {}
            }
            check(targetContext.bindService(Intent(targetContext, AudioProcessingService::class.java), connection, Context.BIND_AUTO_CREATE))
            try {
                check(connected.await(5, TimeUnit.SECONDS))
                runOnMainSync { activity.findViewById<Button>(103).performClick() }
                val deadline = android.os.SystemClock.elapsedRealtime() + 5000
                while (service?.error() == null && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
                check(service?.error() != null && service?.isRunning() == false) { "Foreground service must fail safely without headphones" }
                runOnMainSync { service?.stopProcessing() }
            } finally { targetContext.unbindService(connection) }
            // Exercise real Android mono capture + playback objects, never emit audible audio.
            val rate = 16000
            val captureBytes = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val playbackBytes = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(captureBytes > 0 && playbackBytes > 0)
            val capture = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                .setBufferSizeInBytes(captureBytes * 2).build()
            val playback = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(playbackBytes * 2).setTransferMode(AudioTrack.MODE_STREAM).build()
            try {
                check(capture.state == AudioRecord.STATE_INITIALIZED && capture.channelCount == 1)
                check(playback.state == AudioTrack.STATE_INITIALIZED && playback.channelCount == 1)
                capture.startRecording(); playback.play()
                val block = ShortArray(320)
                check(capture.read(block, 0, block.size, AudioRecord.READ_BLOCKING) > 0)
                check(playback.write(ShortArray(320), 0, 320, AudioTrack.WRITE_BLOCKING) > 0)
            } finally { runCatching { capture.stop() }; runCatching { playback.stop() }; capture.release(); playback.release() }
            runOnMainSync { activity.recreate() }
            waitForIdleSync()
            result.putString("stream", "OK (1): launch, no-headset foreground service safe failure, stop, mono Android capture/playback, recreation")
            finish(android.app.Activity.RESULT_OK, result)
        } catch (t: Throwable) {
            result.putString("stream", "FAILURES!!!\n${t.stackTraceToString()}")
            finish(android.app.Activity.RESULT_CANCELED, result)
        }
    }
}

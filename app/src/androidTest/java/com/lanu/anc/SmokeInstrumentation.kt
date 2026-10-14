package com.lanu.anc

import android.app.Instrumentation
import android.content.Intent
import android.media.*
import android.os.Bundle
import android.widget.Button

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
            result.putString("stream", "OK (1): launch, no-headset safe failure, stop, mono Android capture/playback, recreation")
            finish(android.app.Activity.RESULT_OK, result)
        } catch (t: Throwable) {
            result.putString("stream", "FAILURES!!!\n${t.stackTraceToString()}")
            finish(android.app.Activity.RESULT_CANCELED, result)
        }
    }
}

package com.lanu.anc

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder

/** Real-device secondary-path measurement. The response always comes from AudioRecord. */
class HardwareCalibrationController(private val context: Context) {
    companion object {
        const val SAMPLE_RATE = 48_000
        private const val EXCITATION_FRAMES = 48_000
        private const val TAIL_FRAMES = 24_000
        private const val BUFFER_FRAMES = 2048
    }

    data class Measurement(
        val route: AncCalibrationSession.RouteIdentity,
        val excitation: FloatArray,
        val response: FloatArray
    )

    fun measure(): Measurement? {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return null
        val manager = context.getSystemService(AudioManager::class.java)
        val output = findDevice(manager, false) ?: return null
        val input = findDevice(manager, true) ?: return null
        val route = AncCalibrationSession.RouteIdentity(input.id, output.id, SAMPLE_RATE, 1, 1)
        if (!route.isValid) return null

        val minRecord = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val minTrack = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minRecord <= 0 || minTrack <= 0) return null

        val record = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
            .setBufferSizeInBytes(minRecord.coerceAtLeast(BUFFER_FRAMES * 2) * 4)
            .build()
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(minTrack.coerceAtLeast(BUFFER_FRAMES * 2) * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        try {
            if (record.state != AudioRecord.STATE_INITIALIZED || track.state != AudioTrack.STATE_INITIALIZED) return null
            record.preferredDevice = input
            track.preferredDevice = output

            val excitation = FloatArray(EXCITATION_FRAMES)
            val excitationPcm = ShortArray(EXCITATION_FRAMES)
            buildExcitation(excitation, excitationPcm)
            val responsePcm = ShortArray(EXCITATION_FRAMES + TAIL_FRAMES)

            record.startRecording()
            track.play()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING || track.playState != AudioTrack.PLAYSTATE_PLAYING) return null

            val writer = Thread {
                var offset = 0
                while (offset < excitationPcm.size) {
                    val written = track.write(excitationPcm, offset, minOf(BUFFER_FRAMES, excitationPcm.size - offset), AudioTrack.WRITE_BLOCKING)
                    if (written <= 0) return@Thread
                    offset += written
                }
            }
            writer.start()

            var captured = 0
            while (captured < responsePcm.size) {
                val read = record.read(responsePcm, captured, minOf(BUFFER_FRAMES, responsePcm.size - captured), AudioRecord.READ_BLOCKING)
                if (read <= 0) break
                captured += read
            }
            writer.join(2500)
            if (captured < EXCITATION_FRAMES / 2) return null

            val response = FloatArray(captured)
            for (i in 0 until captured) response[i] = responsePcm[i] / 32768f
            return Measurement(route, excitation, response)
        } finally {
            runCatching { record.stop() }
            runCatching { track.stop() }
            record.release()
            track.release()
        }
    }

    private fun findDevice(manager: AudioManager, input: Boolean): AudioDeviceInfo? {
        val types = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET
        )
        val flags = if (input) AudioManager.GET_DEVICES_INPUTS else AudioManager.GET_DEVICES_OUTPUTS
        return manager.getDevices(flags).firstOrNull { it.type in types }
    }

    /** Controlled excitation; measured response is never synthesized. */
    private fun buildExcitation(target: FloatArray, pcm: ShortArray) {
        var seed = 0x13579BDF
        for (i in target.indices) {
            seed = seed xor (seed shl 13)
            seed = seed xor (seed ushr 17)
            seed = seed xor (seed shl 5)
            val sample = ((seed.toLong() and 0x7fffffffL) / 1073741824.0 - 1.0).toFloat() * 0.12f
            target[i] = sample
            pcm[i] = (sample * 32767f).toInt().toShort()
        }
    }
}

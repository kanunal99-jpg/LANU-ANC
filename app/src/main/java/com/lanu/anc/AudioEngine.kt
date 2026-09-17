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
import android.os.Build

class AudioEngine(private val context: Context) {
    companion object {
        const val SAMPLE_RATE = 48_000
    }

    @Volatile var running: Boolean = false
        private set
    @Volatile var inputDbFs: Float = -120f
        private set
    @Volatile var routeName: String = "-"
        private set
    @Volatile var noiseSuppressorActive: Boolean = false
        private set
    @Volatile var echoCancelerActive: Boolean = false
        private set
    @Volatile var agcActive: Boolean = false
        private set
    @Volatile var lastError: String? = null
        private set

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var noiseSuppressor: android.media.audiofx.NoiseSuppressor? = null
    private var echoCanceler: android.media.audiofx.AcousticEchoCanceler? = null
    private var agc: android.media.audiofx.AutomaticGainControl? = null
    private var worker: Thread? = null
    private var communicationDevice: AudioDeviceInfo? = null

    fun start(): Boolean {
        if (running) return true
        lastError = null

        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            lastError = "Mikrofon izni verilmedi."
            return false
        }

        return try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            communicationDevice = chooseExternalDevice()
            val selectedDevice = communicationDevice
            if (selectedDevice == null) {
                throw IllegalStateException("Harici kulaklık bulunamadı. Güvenli kullanım için kablolu/Bluetooth/USB kulaklık bağlayın.")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (!audioManager.setCommunicationDevice(selectedDevice)) {
                    throw IllegalStateException("Ses iletişim cihazı seçilemedi.")
                }
            }

            val minRecord = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val recordBuffer = (minRecord.coerceAtLeast(1024) * 2)

            record = createRecorder(recordBuffer)
            val sessionId = record!!.audioSessionId
            noiseSuppressor = if (android.media.audiofx.NoiseSuppressor.isAvailable()) android.media.audiofx.NoiseSuppressor.create(sessionId) else null
            echoCanceler = if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) android.media.audiofx.AcousticEchoCanceler.create(sessionId) else null
            agc = if (android.media.audiofx.AutomaticGainControl.isAvailable()) android.media.audiofx.AutomaticGainControl.create(sessionId) else null
            noiseSuppressor?.enabled = true
            echoCanceler?.enabled = true
            agc?.enabled = true
            noiseSuppressorActive = noiseSuppressor?.enabled == true
            echoCancelerActive = echoCanceler?.enabled == true
            agcActive = agc?.enabled == true

            val minTrack = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val trackBuffer = (minTrack.coerceAtLeast(1024) * 2)
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(trackBuffer)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                track?.preferredDevice = selectedDevice
                record?.preferredDevice = selectedDevice
            }

            record!!.startRecording()
            track!!.play()
            routeName = selectedDevice.productName?.toString()?.ifBlank { null } ?: "Harici kulaklık"
            running = true
            worker = Thread(::audioLoop, "LANU-AudioEngine").also { it.start() }
            true
        } catch (t: Throwable) {
            lastError = t.message ?: t.javaClass.simpleName
            stop()
            false
        }
    }

    fun stop() {
        running = false
        worker?.interrupt()
        worker = null

        try { record?.stop() } catch (_: Throwable) { }
        try { track?.stop() } catch (_: Throwable) { }

        record?.release()
        track?.release()
        noiseSuppressor?.release()
        echoCanceler?.release()
        agc?.release()

        record = null
        track = null
        noiseSuppressor = null
        echoCanceler = null
        agc = null
        inputDbFs = -120f
        noiseSuppressorActive = false
        echoCancelerActive = false
        agcActive = false
        routeName = "-"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try { audioManager.clearCommunicationDevice() } catch (_: Throwable) { }
        }
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    private fun createRecorder(bufferSize: Int): AudioRecord {
        val sources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.MIC
        )
        var last: Throwable? = null
        for (source in sources) {
            try {
                val candidate = AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .build()
                if (candidate.state == AudioRecord.STATE_INITIALIZED) return candidate
                candidate.release()
            } catch (t: Throwable) {
                last = t
            }
        }
        throw IllegalStateException("Mikrofon başlatılamadı.", last)
    }

    private fun chooseExternalDevice(): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val preferredTypes = setOf(
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_USB_HEADSET
            )
            return audioManager.availableCommunicationDevices.firstOrNull { it.type in preferredTypes }
        }
        @Suppress("DEPRECATION")
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
    }

    private fun audioLoop() {
        val localRecord = record ?: return
        val localTrack = track ?: return
        val buffer = ShortArray(2048)
        while (running && !Thread.currentThread().isInterrupted) {
            val read = try {
                localRecord.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
            } catch (t: Throwable) {
                lastError = "Mikrofon okuma hatası: ${t.message ?: t.javaClass.simpleName}"
                break
            }
            if (read <= 0) continue
            inputDbFs = AudioMath.rmsDbFs(buffer, read)
            try {
                var offset = 0
                while (offset < read && running) {
                    val written = localTrack.write(buffer, offset, read - offset, AudioTrack.WRITE_BLOCKING)
                    if (written <= 0) {
                        lastError = "Ses çıkışı yazılamadı: $written"
                        running = false
                        break
                    }
                    offset += written
                }
            } catch (t: Throwable) {
                lastError = "Ses çıkışı hatası: ${t.message ?: t.javaClass.simpleName}"
                break
            }
        }
        running = false
    }
}

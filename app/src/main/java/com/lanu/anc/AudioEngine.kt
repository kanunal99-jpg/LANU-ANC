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
    enum class State { IDLE, STARTING, RUNNING, STOPPING, ERROR }
    enum class Backend { NONE, NATIVE_AAUDIO, KOTLIN_AUDIO_RECORD }

    companion object {
        const val SAMPLE_RATE = 48_000
        private const val STOP_JOIN_TIMEOUT_MS = 1500L
    }

    @Volatile var state: State = State.IDLE
        private set

    @Volatile var backend: Backend = Backend.NONE
        private set

    val running: Boolean
        get() = state == State.RUNNING

    val nativeBackendAvailable: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && NativeAudioEngine.isAvailable()

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
    @Volatile var nativeSampleRate: Int = 0
        private set
    @Volatile var nativeFramesPerBurst: Int = 0
        private set
    @Volatile var nativeBufferSizeInFrames: Int = 0
        private set
    @Volatile var nativeXRunCount: Int = 0
        private set
    @Volatile var nativeInputDeviceId: Int = 0
        private set
    @Volatile var nativeOutputDeviceId: Int = 0
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
    private val lifecycleLock = Any()
    private var previousAudioMode = AudioManager.MODE_NORMAL

    fun start(): Boolean = synchronized(lifecycleLock) {
        if (state == State.RUNNING || state == State.STARTING) return true
        if (state == State.STOPPING) return false
        state = State.STARTING
        backend = Backend.NONE
        lastError = null
        resetNativeMetrics()

        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            lastError = "Mikrofon izni verilmedi."
            state = State.ERROR
            return false
        }

        try {
            previousAudioMode = audioManager.mode
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            communicationDevice = chooseExternalDevice()
            val selectedDevice = communicationDevice
                ?: throw IllegalStateException("Harici kulaklık bulunamadı. Güvenli kullanım için kablolu/Bluetooth/USB kulaklık bağlayın.")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !audioManager.setCommunicationDevice(selectedDevice)) {
                throw IllegalStateException("Ses iletişim cihazı seçilemedi.")
            }

            routeName = selectedDevice.productName?.toString()?.ifBlank { null } ?: "Harici kulaklık"

            if (tryStartNativeBackend(selectedDevice)) {
                state = State.RUNNING
                return true
            }

            startKotlinBackend(selectedDevice)
            state = State.RUNNING
            worker = Thread(::audioLoop, "LANU-AudioEngine").also { it.start() }
            true
        } catch (t: Throwable) {
            lastError = t.message ?: t.javaClass.simpleName
            state = State.ERROR
            cleanupAudioResources()
            false
        }
    }

    fun stop() {
        val threadToJoin: Thread?
        synchronized(lifecycleLock) {
            if (state == State.IDLE) return
            state = State.STOPPING
            threadToJoin = worker
            runningSignalStop()
        }

        if (threadToJoin != null && threadToJoin !== Thread.currentThread()) {
            try { threadToJoin.join(STOP_JOIN_TIMEOUT_MS) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        synchronized(lifecycleLock) {
            cleanupAudioResources()
            state = State.IDLE
        }
    }

    private fun tryStartNativeBackend(selectedDevice: AudioDeviceInfo): Boolean {
        if (!nativeBackendAvailable) return false
        return try {
            val started = NativeAudioEngine.start(selectedDevice.id)
            if (started && NativeAudioEngine.isRunning()) {
                backend = Backend.NATIVE_AAUDIO
                nativeSampleRate = NativeAudioEngine.sampleRate()
                nativeFramesPerBurst = NativeAudioEngine.framesPerBurst()
                nativeBufferSizeInFrames = NativeAudioEngine.bufferSizeInFrames()
                nativeXRunCount = NativeAudioEngine.xRunCount()
                nativeInputDeviceId = NativeAudioEngine.inputDeviceId()
                nativeOutputDeviceId = NativeAudioEngine.outputDeviceId()
                true
            } else {
                NativeAudioEngine.stop()
                false
            }
        } catch (_: Throwable) {
            try { NativeAudioEngine.stop() } catch (_: Throwable) { }
            false
        }
    }

    private fun startKotlinBackend(selectedDevice: AudioDeviceInfo) {
        val minRecord = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        require(minRecord > 0) { "Mikrofon buffer boyutu alınamadı." }
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
        require(minTrack > 0) { "Ses çıkışı buffer boyutu alınamadı." }
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
        check(record!!.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Mikrofon kayıt başlatılamadı." }
        track!!.play()
        backend = Backend.KOTLIN_AUDIO_RECORD
    }

    private fun runningSignalStop() {
        if (backend == Backend.NATIVE_AAUDIO) {
            try { NativeAudioEngine.stop() } catch (_: Throwable) { }
        }
        record?.let { try { it.stop() } catch (_: Throwable) { } }
        track?.let { try { it.pause() } catch (_: Throwable) { } }
        worker?.interrupt()
    }

    private fun cleanupAudioResources() {
        try { if (backend == Backend.NATIVE_AAUDIO) NativeAudioEngine.stop() } catch (_: Throwable) { }
        worker = null
        try { record?.release() } catch (_: Throwable) { }
        try { track?.release() } catch (_: Throwable) { }
        try { noiseSuppressor?.release() } catch (_: Throwable) { }
        try { echoCanceler?.release() } catch (_: Throwable) { }
        try { agc?.release() } catch (_: Throwable) { }

        record = null
        track = null
        noiseSuppressor = null
        echoCanceler = null
        agc = null
        communicationDevice = null
        inputDbFs = -120f
        resetNativeMetrics()
        noiseSuppressorActive = false
        echoCancelerActive = false
        agcActive = false
        routeName = "-"
        backend = Backend.NONE

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try { audioManager.clearCommunicationDevice() } catch (_: Throwable) { }
        }
        try { audioManager.mode = previousAudioMode } catch (_: Throwable) { }
    }

    private fun resetNativeMetrics() {
        nativeSampleRate = 0
        nativeFramesPerBurst = 0
        nativeBufferSizeInFrames = 0
        nativeXRunCount = 0
        nativeInputDeviceId = 0
        nativeOutputDeviceId = 0
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
        try {
            while (state == State.RUNNING && !Thread.currentThread().isInterrupted) {
                val read = try {
                    localRecord.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                } catch (t: Throwable) {
                    lastError = "Mikrofon okuma hatası: ${t.message ?: t.javaClass.simpleName}"
                    state = State.ERROR
                    break
                }
                if (read <= 0) continue
                inputDbFs = AudioMath.rmsDbFs(buffer, read)
                var offset = 0
                while (offset < read && state == State.RUNNING) {
                    val written = try {
                        localTrack.write(buffer, offset, read - offset, AudioTrack.WRITE_BLOCKING)
                    } catch (t: Throwable) {
                        lastError = "Ses çıkışı hatası: ${t.message ?: t.javaClass.simpleName}"
                        state = State.ERROR
                        break
                    }
                    if (written <= 0) {
                        lastError = "Ses çıkışı yazılamadı: $written"
                        state = State.ERROR
                        break
                    }
                    offset += written
                }
            }
        } finally {
            if (state == State.ERROR) {
                try { localRecord.stop() } catch (_: Throwable) { }
                try { localTrack.pause() } catch (_: Throwable) { }
            }
        }
    }
}

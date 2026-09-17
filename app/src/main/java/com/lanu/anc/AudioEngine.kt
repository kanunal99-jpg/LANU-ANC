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

    companion object { const val SAMPLE_RATE = 48_000; private const val STOP_JOIN_TIMEOUT_MS = 1500L }

    @Volatile var state: State = State.IDLE
        private set
    @Volatile var backend: Backend = Backend.NONE
        private set
    @Volatile var ancActive: Boolean = false
        private set
    @Volatile var ancFaultCode: Int = 0
        private set
    val running: Boolean get() = state == State.RUNNING
    val nativeBackendAvailable: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && NativeAudioEngine.isAvailable()
    val ancCapabilities: AncCapabilities by lazy { AncCapabilities.detect(context) }

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
    @Volatile var dspProcessingMicros: Long = 0L
        private set
    @Volatile var dspMaxProcessingMicros: Long = 0L
        private set
    @Volatile var limiterActivations: Long = 0L
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
    @Volatile var nativeInputChannels: Int = 0
        private set
    @Volatile var nativeOutputChannels: Int = 0
        private set
    @Volatile var lastError: String? = null
        private set

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val dspPipeline = DspPipeline()
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var noiseSuppressor: android.media.audiofx.NoiseSuppressor? = null
    private var echoCanceler: android.media.audiofx.AcousticEchoCanceler? = null
    private var agc: android.media.audiofx.AutomaticGainControl? = null
    private var worker: Thread? = null
    private var communicationDevice: AudioDeviceInfo? = null
    private val lifecycleLock = Any()
    private var previousAudioMode = AudioManager.MODE_NORMAL
    @Volatile private var validatedCalibration: AncCalibrationSession.Result? = null

    fun applyValidatedCalibration(result: AncCalibrationSession.Result): Boolean = synchronized(lifecycleLock) {
        if (!result.route.isValid || result.route.sampleRateHz != SAMPLE_RATE || result.route.inputChannels < 2) return false
        val model = result.estimate.model
        val aligner = result.estimate.latencyAligner
        if (!model.isValid || !aligner.isValid || model.sampleRateHz != SAMPLE_RATE || aligner.sampleRateHz != SAMPLE_RATE) return false
        if (!NativeAudioEngine.configureAnc(model.coefficients, aligner.delaySamples, model.confidence, result.route.inputDeviceId, result.route.outputDeviceId, result.route.sampleRateHz, result.route.inputChannels, HardwareCalibrationController.REFERENCE_CHANNEL, HardwareCalibrationController.ERROR_CHANNEL)) return false
        validatedCalibration = result
        true
    }

    fun clearValidatedCalibration() = synchronized(lifecycleLock) {
        validatedCalibration = null
        runCatching { NativeAudioEngine.clearAncConfiguration() }
        ancActive = false
    }

    fun start(): Boolean = synchronized(lifecycleLock) {
        if (state == State.RUNNING || state == State.STARTING) return true
        if (state == State.STOPPING) return false
        state = State.STARTING; backend = Backend.NONE; ancActive = false; ancFaultCode = 0; lastError = null
        resetNativeMetrics(); resetDspMetrics()
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { lastError = "Mikrofon izni verilmedi."; state = State.ERROR; return false }
        try {
            previousAudioMode = audioManager.mode
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            val routes = discoverDuplexRoutes()
            communicationDevice = routes.output
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !audioManager.setCommunicationDevice(routes.output)) throw IllegalStateException("Ses iletişim cihazı seçilemedi.")
            routeName = routes.output.productName?.toString()?.ifBlank { null } ?: "Harici kulaklık"

            val calibration = validatedCalibration
            if (calibration != null) {
                if (calibration.route.inputDeviceId != routes.input.id || calibration.route.outputDeviceId != routes.output.id) throw IllegalStateException("Kalibrasyon rotası değişti; ANC güvenli bypass.")
                if (!tryStartNativeBackend(routes.input, routes.output)) throw IllegalStateException("Native AAudio ANC başlatılamadı; ANC güvenli bypass.")
                state = State.RUNNING; ancActive = true; return true
            }

            startKotlinBackend(routes.output)
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
        synchronized(lifecycleLock) { if (state == State.IDLE) return; state = State.STOPPING; threadToJoin = worker; runningSignalStop() }
        if (threadToJoin != null && threadToJoin !== Thread.currentThread()) try { threadToJoin.join(STOP_JOIN_TIMEOUT_MS) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        synchronized(lifecycleLock) { cleanupAudioResources(); state = State.IDLE }
    }

    private data class DuplexRoutes(val input: AudioDeviceInfo, val output: AudioDeviceInfo)

    private fun tryStartNativeBackend(input: AudioDeviceInfo, output: AudioDeviceInfo): Boolean {
        if (!nativeBackendAvailable || !NativeAudioEngine.ancConfigured() || !supportsTwoInputChannels(input)) return false
        return try {
            val started = NativeAudioEngine.start(input.id, output.id)
            if (started && NativeAudioEngine.isRunning()) {
                backend = Backend.NATIVE_AAUDIO
                nativeSampleRate = NativeAudioEngine.sampleRate(); nativeFramesPerBurst = NativeAudioEngine.framesPerBurst(); nativeBufferSizeInFrames = NativeAudioEngine.bufferSizeInFrames(); nativeXRunCount = NativeAudioEngine.xRunCount()
                nativeInputDeviceId = NativeAudioEngine.inputDeviceId(); nativeOutputDeviceId = NativeAudioEngine.outputDeviceId(); nativeInputChannels = NativeAudioEngine.inputChannelCount(); nativeOutputChannels = NativeAudioEngine.outputChannelCount()
                if (nativeInputDeviceId != input.id || nativeOutputDeviceId != output.id || nativeInputChannels != 2 || nativeOutputChannels != 1) { NativeAudioEngine.stop(); resetNativeMetrics(); backend = Backend.NONE; return false }
                true
            } else { NativeAudioEngine.stop(); false }
        } catch (_: Throwable) { runCatching { NativeAudioEngine.stop() }; false }
    }

    private fun startKotlinBackend(selectedDevice: AudioDeviceInfo) {
        val minRecord = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT); require(minRecord > 0) { "Mikrofon buffer boyutu alınamadı." }
        val recordBuffer = minRecord.coerceAtLeast(1024) * 2
        record = createRecorder(recordBuffer)
        val sessionId = record!!.audioSessionId
        noiseSuppressor = if (android.media.audiofx.NoiseSuppressor.isAvailable()) android.media.audiofx.NoiseSuppressor.create(sessionId) else null
        echoCanceler = if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) android.media.audiofx.AcousticEchoCanceler.create(sessionId) else null
        agc = if (android.media.audiofx.AutomaticGainControl.isAvailable()) android.media.audiofx.AutomaticGainControl.create(sessionId) else null
        noiseSuppressor?.enabled = true; echoCanceler?.enabled = true; agc?.enabled = true
        noiseSuppressorActive = noiseSuppressor?.enabled == true; echoCancelerActive = echoCanceler?.enabled == true; agcActive = agc?.enabled == true
        val minTrack = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT); require(minTrack > 0) { "Ses çıkışı buffer boyutu alınamadı." }
        val trackBuffer = minTrack.coerceAtLeast(1024) * 2
        track = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()).setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()).setBufferSizeInBytes(trackBuffer).setTransferMode(AudioTrack.MODE_STREAM).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { track?.preferredDevice = selectedDevice; record?.preferredDevice = selectedDevice }
        record!!.startRecording(); check(record!!.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Mikrofon kayıt başlatılamadı." }; track!!.play(); backend = Backend.KOTLIN_AUDIO_RECORD
    }

    private fun runningSignalStop() { if (backend == Backend.NATIVE_AAUDIO) runCatching { NativeAudioEngine.stop() }; record?.let { runCatching { it.stop() } }; track?.let { runCatching { it.pause() } }; worker?.interrupt() }

    private fun cleanupAudioResources() {
        if (backend == Backend.NATIVE_AAUDIO) runCatching { NativeAudioEngine.stop() }
        worker = null; runCatching { record?.release() }; runCatching { track?.release() }; runCatching { noiseSuppressor?.release() }; runCatching { echoCanceler?.release() }; runCatching { agc?.release() }
        record = null; track = null; noiseSuppressor = null; echoCanceler = null; agc = null; communicationDevice = null; inputDbFs = -120f; resetNativeMetrics(); resetDspMetrics(); noiseSuppressorActive = false; echoCancelerActive = false; agcActive = false; routeName = "-"; backend = Backend.NONE; ancActive = false; ancFaultCode = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) runCatching { audioManager.clearCommunicationDevice() }
        runCatching { audioManager.mode = previousAudioMode }
    }

    private fun resetNativeMetrics() { nativeSampleRate = 0; nativeFramesPerBurst = 0; nativeBufferSizeInFrames = 0; nativeXRunCount = 0; nativeInputDeviceId = 0; nativeOutputDeviceId = 0; nativeInputChannels = 0; nativeOutputChannels = 0 }
    private fun resetDspMetrics() { dspPipeline.reset(); dspProcessingMicros = 0L; dspMaxProcessingMicros = 0L; limiterActivations = 0L }

    private fun createRecorder(bufferSize: Int): AudioRecord {
        var last: Throwable? = null
        for (source in intArrayOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.MIC)) try {
            val candidate = AudioRecord.Builder().setAudioSource(source).setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()).setBufferSizeInBytes(bufferSize).build()
            if (candidate.state == AudioRecord.STATE_INITIALIZED) return candidate
            candidate.release()
        } catch (t: Throwable) { last = t }
        throw IllegalStateException("Mikrofon başlatılamadı.", last)
    }

    private fun supportsTwoInputChannels(device: AudioDeviceInfo): Boolean = device.channelCounts.isEmpty() || device.channelCounts.any { it >= 2 }

    private fun discoverDuplexRoutes(): DuplexRoutes {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) throw IllegalStateException("Duplex harici ses rotası Android 6.0+ gerektirir.")
        val preferredTypes = setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE)
        val devices = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audioManager.availableCommunicationDevices else { @Suppress("DEPRECATION") audioManager.getDevices(AudioManager.GET_DEVICES_ALL).toList() }
        val input = devices.firstOrNull { it.isSource && it.type in preferredTypes && supportsTwoInputChannels(it) } ?: throw IllegalStateException("Gerçek 2 kanallı harici referans/hata girişi bulunamadı.")
        val output = devices.firstOrNull { it.isSink && it.type in preferredTypes } ?: throw IllegalStateException("Harici çıkış cihazı bulunamadı.")
        return DuplexRoutes(input, output)
    }

    private fun audioLoop() {
        val localRecord = record ?: return; val localTrack = track ?: return; val buffer = ShortArray(2048)
        try { while (state == State.RUNNING && !Thread.currentThread().isInterrupted) {
            val read = try { localRecord.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING) } catch (t: Throwable) { lastError = "Mikrofon okuma hatası: ${t.message ?: t.javaClass.simpleName}"; state = State.ERROR; break }
            if (read <= 0) continue
            dspPipeline.process(buffer, read); inputDbFs = AudioMath.rmsDbFs(buffer, read)
            val m = dspPipeline.metrics; dspProcessingMicros = m.processingMicros; dspMaxProcessingMicros = m.maxProcessingMicros; limiterActivations = m.limiterActivations
            var offset = 0
            while (offset < read && state == State.RUNNING) {
                val written = try { localTrack.write(buffer, offset, read - offset, AudioTrack.WRITE_BLOCKING) } catch (t: Throwable) { lastError = "Ses çıkışı hatası: ${t.message ?: t.javaClass.simpleName}"; state = State.ERROR; break }
                if (written <= 0) { lastError = "Ses çıkışı yazılamadı: $written"; state = State.ERROR; break }
                offset += written
            }
        } } finally { if (state == State.ERROR) { runCatching { localRecord.stop() }; runCatching { localTrack.pause() } } }
    }
}

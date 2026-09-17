#!/usr/bin/env bash
set -euo pipefail

cat > app/src/main/java/com/lanu/anc/HardwareCalibrationController.kt <<'EOF'
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

/** Real-device secondary-path measurement using a physical two-channel input. */
class HardwareCalibrationController(private val context: Context) {
    companion object {
        const val SAMPLE_RATE = 48_000
        const val INPUT_CHANNELS = 2
        const val REFERENCE_CHANNEL = 0
        const val ERROR_CHANNEL = 1
        private const val EXCITATION_FRAMES = 48_000
        private const val TAIL_FRAMES = 24_000
        private const val BUFFER_FRAMES = 2048
    }

    data class Measurement(
        val route: AncCalibrationSession.RouteIdentity,
        val excitation: FloatArray,
        val response: FloatArray,
        val referenceChannel: Int = REFERENCE_CHANNEL,
        val errorChannel: Int = ERROR_CHANNEL
    )

    fun measure(): Measurement? {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return null
        val manager = context.getSystemService(AudioManager::class.java)
        val output = findDevice(manager, false) ?: return null
        val input = findDevice(manager, true) ?: return null
        if (!supportsTwoInputChannels(input)) return null

        val route = AncCalibrationSession.RouteIdentity(input.id, output.id, SAMPLE_RATE, INPUT_CHANNELS, 1)
        if (!route.isValid) return null

        val minRecord = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val minTrack = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minRecord <= 0 || minTrack <= 0) return null

        val record = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build())
            .setBufferSizeInBytes(minRecord.coerceAtLeast(BUFFER_FRAMES * INPUT_CHANNELS * 2) * 4)
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
            if (!routeMatches(record, input, track, output) || record.channelCount < INPUT_CHANNELS) return null

            val excitation = FloatArray(EXCITATION_FRAMES)
            val excitationPcm = ShortArray(EXCITATION_FRAMES)
            buildExcitation(excitation, excitationPcm)
            val responsePcm = ShortArray((EXCITATION_FRAMES + TAIL_FRAMES) * INPUT_CHANNELS)

            record.startRecording()
            track.play()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING || track.playState != AudioTrack.PLAYSTATE_PLAYING) return null
            if (!routeMatches(record, input, track, output)) return null

            val writer = Thread {
                var offset = 0
                while (offset < excitationPcm.size) {
                    val written = track.write(excitationPcm, offset, minOf(BUFFER_FRAMES, excitationPcm.size - offset), AudioTrack.WRITE_BLOCKING)
                    if (written <= 0) return@Thread
                    offset += written
                }
            }
            writer.start()

            var capturedSamples = 0
            while (capturedSamples < responsePcm.size) {
                val read = record.read(responsePcm, capturedSamples, minOf(BUFFER_FRAMES * INPUT_CHANNELS, responsePcm.size - capturedSamples), AudioRecord.READ_BLOCKING)
                if (read <= 0) break
                capturedSamples += read
            }
            writer.join(2500)
            if (capturedSamples < responsePcm.size) return null

            // Secondary-path response is measured on the physical error microphone (channel 1).
            val response = FloatArray(EXCITATION_FRAMES)
            for (frame in response.indices) {
                response[frame] = responsePcm[frame * INPUT_CHANNELS + ERROR_CHANNEL] / 32768f
            }
            return Measurement(route, excitation, response)
        } finally {
            runCatching { record.stop() }
            runCatching { track.stop() }
            record.release()
            track.release()
        }
    }

    private fun routeMatches(record: AudioRecord, expectedInput: AudioDeviceInfo, track: AudioTrack, expectedOutput: AudioDeviceInfo): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return true
        return record.routedDevice?.id == expectedInput.id && track.routedDevice?.id == expectedOutput.id
    }

    private fun supportsTwoInputChannels(device: AudioDeviceInfo): Boolean =
        device.channelCounts.isEmpty() || device.channelCounts.any { it >= INPUT_CHANNELS }

    private fun findDevice(manager: AudioManager, input: Boolean): AudioDeviceInfo? {
        val types = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE
        )
        val flags = if (input) AudioManager.GET_DEVICES_INPUTS else AudioManager.GET_DEVICES_OUTPUTS
        return manager.getDevices(flags).firstOrNull { it.type in types && (!input || supportsTwoInputChannels(it)) }
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
EOF

cat > app/src/main/java/com/lanu/anc/NativeAudioEngine.kt <<'EOF'
package com.lanu.anc

/** JNI facade for the real-time native AAudio ANC engine. */
object NativeAudioEngine {
    private var loaded = false

    init {
        try {
            System.loadLibrary("lanu_audio_native")
            loaded = true
        } catch (_: UnsatisfiedLinkError) {
            loaded = false
        }
    }

    fun isAvailable(): Boolean = loaded

    fun configureAnc(
        secondaryPathTaps: FloatArray,
        latencySamples: Int,
        confidence: Float,
        inputDeviceId: Int,
        outputDeviceId: Int,
        sampleRateHz: Int,
        inputChannels: Int,
        referenceChannel: Int = 0,
        errorChannel: Int = 1
    ): Boolean = loaded && lanuNativeConfigureAnc(secondaryPathTaps, latencySamples, confidence, inputDeviceId, outputDeviceId, sampleRateHz, inputChannels, referenceChannel, errorChannel)

    fun clearAncConfiguration() { if (loaded) lanuNativeClearAncConfiguration() }
    fun ancConfigured(): Boolean = loaded && lanuNativeAncConfigured()
    fun ancFaulted(): Boolean = loaded && lanuNativeAncFaulted()
    fun ancFaultCode(): Int = if (loaded) lanuNativeAncFaultCode() else 0
    fun start(inputDeviceId: Int, outputDeviceId: Int): Boolean = loaded && lanuNativeStart(inputDeviceId, outputDeviceId)
    fun start(deviceId: Int): Boolean = start(deviceId, deviceId)
    fun stop() { if (loaded) lanuNativeStop() }
    fun isRunning(): Boolean = loaded && lanuNativeIsRunning()
    fun sampleRate(): Int = if (loaded) lanuNativeSampleRate() else 0
    fun framesPerBurst(): Int = if (loaded) lanuNativeFramesPerBurst() else 0
    fun bufferSizeInFrames(): Int = if (loaded) lanuNativeBufferSizeInFrames() else 0
    fun xRunCount(): Int = if (loaded) lanuNativeXRunCount() else 0
    fun inputDeviceId(): Int = if (loaded) lanuNativeInputDeviceId() else 0
    fun outputDeviceId(): Int = if (loaded) lanuNativeOutputDeviceId() else 0
    fun inputChannelCount(): Int = if (loaded) lanuNativeInputChannelCount() else 0
    fun outputChannelCount(): Int = if (loaded) lanuNativeOutputChannelCount() else 0

    private external fun lanuNativeConfigureAnc(secondaryPathTaps: FloatArray, latencySamples: Int, confidence: Float, inputDeviceId: Int, outputDeviceId: Int, sampleRateHz: Int, inputChannels: Int, referenceChannel: Int, errorChannel: Int): Boolean
    private external fun lanuNativeClearAncConfiguration()
    private external fun lanuNativeAncConfigured(): Boolean
    private external fun lanuNativeAncFaulted(): Boolean
    private external fun lanuNativeAncFaultCode(): Int
    private external fun lanuNativeStart(inputDeviceId: Int, outputDeviceId: Int): Boolean
    private external fun lanuNativeStop()
    private external fun lanuNativeIsRunning(): Boolean
    private external fun lanuNativeSampleRate(): Int
    private external fun lanuNativeFramesPerBurst(): Int
    private external fun lanuNativeBufferSizeInFrames(): Int
    private external fun lanuNativeXRunCount(): Int
    private external fun lanuNativeInputDeviceId(): Int
    private external fun lanuNativeOutputDeviceId(): Int
    private external fun lanuNativeInputChannelCount(): Int
    private external fun lanuNativeOutputChannelCount(): Int
}
EOF

cat > app/src/main/java/com/lanu/anc/AudioEngine.kt <<'EOF'
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
EOF

cat > app/src/main/java/com/lanu/anc/AudioProcessingService.kt <<'EOF'
package com.lanu.anc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder

class AudioProcessingService : Service() {
    companion object { const val ACTION_START = "com.lanu.anc.START"; const val ACTION_STOP = "com.lanu.anc.STOP"; const val CHANNEL_ID = "lanu_anc_engine"; const val NOTIFICATION_ID = 1001 }
    private val binder = LocalBinder()
    private lateinit var engine: AudioEngine
    @Volatile private var calibrationOutcome: HardwareCalibrationSession.Outcome? = null
    inner class LocalBinder : Binder() { fun service(): AudioProcessingService = this@AudioProcessingService }
    override fun onCreate() { super.onCreate(); engine = AudioEngine(this); createNotificationChannel() }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int { when (intent?.action) { ACTION_STOP -> { stopProcessing(); stopSelf() }; ACTION_START -> startProcessing() }; return START_NOT_STICKY }
    fun startProcessing(): Boolean {
        startForegroundCompat(buildNotification("Başlatılıyor…"))
        val calibration = calibrationOutcome
        if (calibration?.state == AncCalibrationSession.State.VALIDATED && calibration.result != null) { if (!engine.applyValidatedCalibration(calibration.result)) { stopProcessing(); return false } } else engine.clearValidatedCalibration()
        val started = engine.start(); updateNotification(); return started
    }
    fun stopProcessing() { engine.stop(); updateNotification(); if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE) else { @Suppress("DEPRECATION") stopForeground(true) } }
    fun calibrateHardware(): HardwareCalibrationSession.Outcome {
        startForegroundCompat(buildNotification("Gerçek cihaz kalibrasyonu hazırlanıyor…")); engine.stop(); engine.clearValidatedCalibration()
        val outcome = HardwareCalibrationSession(this).run(); calibrationOutcome = outcome; if (outcome.state != AncCalibrationSession.State.VALIDATED) engine.clearValidatedCalibration(); updateNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE) else { @Suppress("DEPRECATION") stopForeground(true) }
        return outcome
    }
    fun calibrationState(): AncCalibrationSession.State = calibrationOutcome?.state ?: AncCalibrationSession.State.IDLE
    fun calibrationResult(): AncCalibrationSession.Result? = calibrationOutcome?.result
    fun calibrationError(): String? = calibrationOutcome?.error
    fun isRunning(): Boolean = engine.running
    fun isAncActive(): Boolean = engine.ancActive
    fun ancFaultCode(): Int = engine.ancFaultCode
    fun inputDbFs(): Float = engine.inputDbFs
    fun routeName(): String = engine.routeName
    fun backend(): AudioEngine.Backend = engine.backend
    fun nativeBackendAvailable(): Boolean = engine.nativeBackendAvailable
    fun nsActive(): Boolean = engine.noiseSuppressorActive
    fun aecActive(): Boolean = engine.echoCancelerActive
    fun agcActive(): Boolean = engine.agcActive
    fun error(): String? = engine.lastError
    fun state(): AudioEngine.State = engine.state
    override fun onBind(intent: Intent): IBinder = binder
    override fun onDestroy() { engine.stop(); super.onDestroy() }
    private fun startForegroundCompat(notification: Notification) { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else { @Suppress("DEPRECATION") startForeground(NOTIFICATION_ID, notification) } }
    private fun updateNotification() {
        if (!::engine.isInitialized) return
        val backendText = when (engine.backend) { AudioEngine.Backend.NATIVE_AAUDIO -> "Native AAudio ANC"; AudioEngine.Backend.KOTLIN_AUDIO_RECORD -> "Güvenli bypass"; AudioEngine.Backend.NONE -> "Hazır" }
        val text = when { calibrationOutcome?.state == AncCalibrationSession.State.VALIDATED && engine.ancActive -> "ANC aktif • gerçek kalibrasyon • ${engine.routeName}"; calibrationOutcome?.state == AncCalibrationSession.State.VALIDATED -> "Kalibrasyon doğrulandı • ANC beklemede"; calibrationOutcome?.state == AncCalibrationSession.State.FAILED -> "Kalibrasyon başarısız • ANC bypass"; engine.running -> "Çalışıyor • $backendText • ${engine.routeName}"; engine.lastError != null -> "Hata: ${engine.lastError}"; else -> "Hazır" }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }
    private fun buildNotification(text: String): Notification { val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID) else { @Suppress("DEPRECATION") Notification.Builder(this) }; return builder.setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("LANU ANC").setContentText(text.take(120)).setOngoing(::engine.isInitialized && engine.running).build() }
    private fun createNotificationChannel() { if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return; val channel = NotificationChannel(CHANNEL_ID, "LANU ANC ses motoru", NotificationManager.IMPORTANCE_LOW).apply { description = "LANU ANC mikrofon gürültü azaltma motoru" }; getSystemService(NotificationManager::class.java).createNotificationChannel(channel) }
}
EOF

cat > app/src/main/cpp/native_audio_engine.cpp <<'EOF'
#include <aaudio/AAudio.h>
#include <jni.h>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstring>

namespace {
constexpr int32_t kSampleRate = 48000;
constexpr int32_t kInputChannels = 2;
constexpr int32_t kOutputChannels = 1;
constexpr int32_t kMaxTaps = 64;
constexpr int32_t kMaxDelaySamples = 4096;
constexpr uint32_t kRingFrames = 16384;
constexpr float kOutputLimit = 0.8912509f;
constexpr float kEpsilon = 1e-6f;
constexpr float kLearningRate = 0.01f;

struct Ring {
    int16_t data[kRingFrames * kInputChannels]{};
    std::atomic<uint32_t> read{0};
    std::atomic<uint32_t> write{0};
    uint32_t available() const { return write.load(std::memory_order_acquire) - read.load(std::memory_order_relaxed); }
    uint32_t freeSpace() const { return kRingFrames - available(); }
    uint32_t push(const int16_t* src, uint32_t frames) {
        const uint32_t count = frames < freeSpace() ? frames : freeSpace();
        const uint32_t w = write.load(std::memory_order_relaxed);
        for (uint32_t i = 0; i < count; ++i) { const uint32_t slot = (w + i) % kRingFrames; data[slot * kInputChannels] = src[i * kInputChannels]; data[slot * kInputChannels + 1] = src[i * kInputChannels + 1]; }
        write.store(w + count, std::memory_order_release); return count;
    }
    uint32_t pop(int16_t* dst, uint32_t frames) {
        const uint32_t count = frames < available() ? frames : available();
        const uint32_t r = read.load(std::memory_order_relaxed);
        for (uint32_t i = 0; i < count; ++i) { const uint32_t slot = (r + i) % kRingFrames; dst[i * kInputChannels] = data[slot * kInputChannels]; dst[i * kInputChannels + 1] = data[slot * kInputChannels + 1]; }
        read.store(r + count, std::memory_order_release); return count;
    }
    void clear() { read.store(0, std::memory_order_relaxed); write.store(0, std::memory_order_relaxed); }
};

struct AncConfig {
    bool configured = false;
    int32_t expectedInputDevice = 0;
    int32_t expectedOutputDevice = 0;
    int32_t sampleRate = kSampleRate;
    int32_t inputChannels = kInputChannels;
    int32_t referenceChannel = 0;
    int32_t errorChannel = 1;
    int32_t latencySamples = 0;
    float confidence = 0.0f;
    float secondaryPath[kMaxTaps]{};
    int32_t secondaryTaps = 0;
};

struct Engine {
    AAudioStream* input{};
    AAudioStream* output{};
    Ring ring;
    AncConfig config;
    std::atomic<bool> running{false};
    std::atomic<bool> faulted{false};
    std::atomic<int32_t> faultCode{0};
    std::atomic<bool> primed{false};
    float weights[kMaxTaps]{};
    float referenceHistory[kMaxTaps]{};
    float filteredReference[kMaxTaps]{};
    float delayHistory[kMaxDelaySamples + 1]{};
    int32_t delayCursor = 0;
    int32_t cursor = 0;
    float maxAbsCoefficient = 0.0f;
};
Engine g;

void resetAdaptiveState() { std::memset(g.weights, 0, sizeof(g.weights)); std::memset(g.referenceHistory, 0, sizeof(g.referenceHistory)); std::memset(g.filteredReference, 0, sizeof(g.filteredReference)); std::memset(g.delayHistory, 0, sizeof(g.delayHistory)); g.delayCursor = 0; g.cursor = 0; g.maxAbsCoefficient = 0.0f; }
void fault(int32_t code) { g.faultCode.store(code, std::memory_order_release); g.faulted.store(true, std::memory_order_release); g.running.store(false, std::memory_order_release); }
float alignReference(float sample) { const int32_t delay = g.config.latencySamples; g.delayHistory[g.delayCursor] = sample; const int32_t size = delay + 1; int32_t read = g.delayCursor - delay; if (read < 0) read += size; const float out = g.delayHistory[read]; ++g.delayCursor; if (g.delayCursor >= size) g.delayCursor = 0; return out; }

float predictAndAdapt(float reference, float error) {
    if (!std::isfinite(reference) || !std::isfinite(error)) { fault(4); return 0.0f; }
    const float aligned = alignReference(reference);
    g.referenceHistory[g.cursor] = aligned;
    float prediction = 0.0f; int32_t index = g.cursor;
    for (int32_t i = 0; i < kMaxTaps; ++i) { prediction += g.weights[i] * g.referenceHistory[index]; if (--index < 0) index = kMaxTaps - 1; }
    if (!std::isfinite(prediction)) { fault(4); return 0.0f; }
    float filtered = 0.0f; index = g.cursor;
    for (int32_t i = 0; i < g.config.secondaryTaps; ++i) { filtered += g.config.secondaryPath[i] * g.referenceHistory[index]; if (--index < 0) index = kMaxTaps - 1; }
    if (!std::isfinite(filtered)) { fault(4); return 0.0f; }
    g.filteredReference[g.cursor] = filtered;
    float energy = kEpsilon; index = g.cursor;
    for (int32_t i = 0; i < kMaxTaps; ++i) { const float x = g.filteredReference[index]; energy += x * x; if (--index < 0) index = kMaxTaps - 1; }
    const float step = kLearningRate * error / energy;
    if (!std::isfinite(step)) { fault(4); return 0.0f; }
    index = g.cursor; g.maxAbsCoefficient = 0.0f;
    for (int32_t i = 0; i < kMaxTaps; ++i) { float next = g.weights[i] + step * g.filteredReference[index]; if (!std::isfinite(next)) { fault(5); return 0.0f; } if (next > 1.0f) next = 1.0f; if (next < -1.0f) next = -1.0f; g.weights[i] = next; g.maxAbsCoefficient = std::fmax(g.maxAbsCoefficient, std::fabs(next)); if (--index < 0) index = kMaxTaps - 1; }
    if (g.maxAbsCoefficient > 1.0f || !std::isfinite(g.maxAbsCoefficient)) { fault(5); return 0.0f; }
    ++g.cursor; if (g.cursor == kMaxTaps) g.cursor = 0;
    return std::fmax(-kOutputLimit, std::fmin(kOutputLimit, -prediction));
}

aaudio_data_callback_result_t inputCb(AAudioStream*, void* user, void* data, int32_t frames) {
    auto* e = static_cast<Engine*>(user); if (!e->running.load(std::memory_order_relaxed)) return AAUDIO_CALLBACK_RESULT_STOP; if (frames <= 0) return AAUDIO_CALLBACK_RESULT_CONTINUE;
    const uint32_t pushed = e->ring.push(static_cast<const int16_t*>(data), static_cast<uint32_t>(frames)); if (pushed != static_cast<uint32_t>(frames)) { fault(2); return AAUDIO_CALLBACK_RESULT_STOP; }
    e->primed.store(true, std::memory_order_release); return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

aaudio_data_callback_result_t outputCb(AAudioStream*, void* user, void* data, int32_t frames) {
    auto* e = static_cast<Engine*>(user); auto* out = static_cast<int16_t*>(data); if (frames <= 0) return AAUDIO_CALLBACK_RESULT_CONTINUE;
    const auto started = std::chrono::steady_clock::now();
    if (!e->running.load(std::memory_order_relaxed) || e->faulted.load(std::memory_order_acquire)) { std::memset(out, 0, static_cast<size_t>(frames) * sizeof(int16_t)); return AAUDIO_CALLBACK_RESULT_STOP; }
    if (frames > 2048) { fault(6); std::memset(out, 0, static_cast<size_t>(frames) * sizeof(int16_t)); return AAUDIO_CALLBACK_RESULT_STOP; }
    int16_t inputFrames[4096]{};
    const uint32_t copied = e->ring.pop(inputFrames, static_cast<uint32_t>(frames));
    if (copied < static_cast<uint32_t>(frames)) { std::memset(out, 0, static_cast<size_t>(frames) * sizeof(int16_t)); if (e->primed.load(std::memory_order_acquire)) fault(3); return e->running.load(std::memory_order_relaxed) ? AAUDIO_CALLBACK_RESULT_CONTINUE : AAUDIO_CALLBACK_RESULT_STOP; }
    for (int32_t i = 0; i < frames; ++i) { const float reference = inputFrames[i * kInputChannels + e->config.referenceChannel] / 32768.0f; const float error = inputFrames[i * kInputChannels + e->config.errorChannel] / 32768.0f; const float antiNoise = predictAndAdapt(reference, error); if (e->faulted.load(std::memory_order_acquire)) { std::memset(out + i, 0, static_cast<size_t>(frames - i) * sizeof(int16_t)); return AAUDIO_CALLBACK_RESULT_STOP; } out[i] = static_cast<int16_t>(antiNoise * 32767.0f); }
    const auto elapsedUs = std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now() - started).count(); const int64_t budgetUs = (static_cast<int64_t>(frames) * 1000000LL) / kSampleRate;
    if (budgetUs > 0 && elapsedUs > (budgetUs * 3) / 4) { fault(6); std::memset(out, 0, static_cast<size_t>(frames) * sizeof(int16_t)); return AAUDIO_CALLBACK_RESULT_STOP; }
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

void closeStream(AAudioStream*& s) { if (!s) return; AAudioStream_requestStop(s); AAudioStream_close(s); s = nullptr; }

bool openStreams(int32_t inputDeviceId, int32_t outputDeviceId) {
    AAudioStreamBuilder* in = nullptr; AAudioStreamBuilder* out = nullptr;
    if (AAudio_createStreamBuilder(&in) != AAUDIO_OK || AAudio_createStreamBuilder(&out) != AAUDIO_OK) { if (in) AAudioStreamBuilder_delete(in); if (out) AAudioStreamBuilder_delete(out); fault(7); return false; }
    AAudioStreamBuilder_setDirection(in, AAUDIO_DIRECTION_INPUT); AAudioStreamBuilder_setSampleRate(in, kSampleRate); AAudioStreamBuilder_setChannelCount(in, kInputChannels); AAudioStreamBuilder_setFormat(in, AAUDIO_FORMAT_PCM_I16); AAudioStreamBuilder_setPerformanceMode(in, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY); AAudioStreamBuilder_setDeviceId(in, inputDeviceId); AAudioStreamBuilder_setDataCallback(in, inputCb, &g);
    AAudioStreamBuilder_setDirection(out, AAUDIO_DIRECTION_OUTPUT); AAudioStreamBuilder_setSampleRate(out, kSampleRate); AAudioStreamBuilder_setChannelCount(out, kOutputChannels); AAudioStreamBuilder_setFormat(out, AAUDIO_FORMAT_PCM_I16); AAudioStreamBuilder_setPerformanceMode(out, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY); AAudioStreamBuilder_setDeviceId(out, outputDeviceId); AAudioStreamBuilder_setDataCallback(out, outputCb, &g);
    const auto inResult = AAudioStreamBuilder_openStream(in, &g.input); const auto outResult = AAudioStreamBuilder_openStream(out, &g.output); AAudioStreamBuilder_delete(in); AAudioStreamBuilder_delete(out);
    if (inResult != AAUDIO_OK || outResult != AAUDIO_OK) { closeStream(g.input); closeStream(g.output); fault(7); return false; }
    if (AAudioStream_getDeviceId(g.input) != inputDeviceId || AAudioStream_getDeviceId(g.output) != outputDeviceId || AAudioStream_getChannelCount(g.input) != kInputChannels || AAudioStream_getChannelCount(g.output) != kOutputChannels) { closeStream(g.input); closeStream(g.output); fault(7); return false; }
    return true;
}
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_lanu_anc_NativeAudioEngine_lanuNativeConfigureAnc(JNIEnv* env, jobject, jfloatArray taps, jint latency, jfloat confidence, jint inputDeviceId, jint outputDeviceId, jint sampleRateHz, jint inputChannels, jint referenceChannel, jint errorChannel) {
    if (g.running.load(std::memory_order_acquire) || !taps || sampleRateHz != kSampleRate || inputChannels != kInputChannels || referenceChannel != 0 || errorChannel != 1 || latency < 0 || latency > kMaxDelaySamples || !std::isfinite(confidence) || confidence < 0.65f || confidence > 1.0f || inputDeviceId <= 0 || outputDeviceId <= 0) return JNI_FALSE;
    const jsize size = env->GetArrayLength(taps); if (size <= 0 || size > kMaxTaps) return JNI_FALSE;
    jboolean isCopy = JNI_FALSE; const jfloat* src = env->GetFloatArrayElements(taps, &isCopy); if (!src) return JNI_FALSE;
    for (jsize i = 0; i < size; ++i) { if (!std::isfinite(src[i])) { env->ReleaseFloatArrayElements(taps, const_cast<jfloat*>(src), JNI_ABORT); return JNI_FALSE; } g.config.secondaryPath[i] = src[i]; }
    env->ReleaseFloatArrayElements(taps, const_cast<jfloat*>(src), JNI_ABORT); for (jsize i = size; i < kMaxTaps; ++i) g.config.secondaryPath[i] = 0.0f;
    g.config.secondaryTaps = size; g.config.latencySamples = latency; g.config.confidence = confidence; g.config.expectedInputDevice = inputDeviceId; g.config.expectedOutputDevice = outputDeviceId; g.config.sampleRate = sampleRateHz; g.config.inputChannels = inputChannels; g.config.referenceChannel = referenceChannel; g.config.errorChannel = errorChannel; g.config.configured = true; g.faulted.store(false, std::memory_order_release); g.faultCode.store(0, std::memory_order_release); resetAdaptiveState(); return JNI_TRUE;
}
extern "C" JNIEXPORT void JNICALL Java_com_lanu_anc_NativeAudioEngine_lanuNativeClearAncConfiguration(JNIEnv*, jobject) { if (g.running.load(std::memory_order_acquire)) return; g.config = AncConfig{}; g.faulted.store(false, std::memory_order_release); g.faultCode.store(0, std::memory_order_release); resetAdaptiveState(); }
extern "C" JNIEXPORT jboolean JNICALL Java_com_lanu_anc_NativeAudioEngine_lanuNativeAncConfigured(JNIEnv*, jobject) { return g.config.configured ? JNI_TRUE : JNI_FALSE; }
extern "C" JNIEXPORT jboolean JNICALL Java_com_lanu_anc_NativeAudioEngine_lanuNativeAncFaulted(JNIEnv*, jobject) { return g.faulted.load(std::memory_order_acquire) ? JNI_TRUE : JNI_FALSE; }
extern "C" JNIEXPORT jint JNICALL Java_com_lanu_anc_NativeAudioEngine_lanuNativeAncFaultCode(JNIEnv*, jobject) { return g.faultCode.load(std::memory_order_acquire); }
extern "C" JNIEXPORT jboolean JNICALL Java_com_lanu_anc_NativeAudioEngine_lanuNativeStart(JNIEnv*, jobject, jint inputDeviceId, jint outputDeviceId) {
    if (g.running.load(std::memory_order_acquire)) return JNI_TRUE;
    if (!g.config.configured || inputDeviceId != g.config.expectedInputDevice || outputDeviceId != g.config.expectedOutputDevice) { fault(7); return JNI_FALSE; }
    g.ring.clear(); resetAdaptiveState(); g.faulted.store(false, std::memory_order_release); g.faultCode.store(0, std::memory_order_release); g.primed.store(false, std::memory_order_release);
    if (!openStreams(inputDeviceId, outputDeviceId)) return JNI_FALSE; g.running.store(true, std::memory_order_release);
    if (AAudioStream_requestStart(g.input) != AAUDIO_OK || AAudioStream_requestStart(g.output) != AAUDIO_OK) { g.running.store(false, std::memory_order_release); closeStream(g.input); closeStream(g.output); fault(7); return JNI_FALSE; }
    return JNI_TRUE;
}
extern "C" JNIEXPORT void JNICALL Java_com_lanu_anc_NativeAudioEngine_lanuNativeStop(JNIEnv*, jobject) { g.running.store(false, std::memory_order_release); closeStream(g.input); closeStream(g.output); g.primed.store(false, std::memory_order_release); }
extern "C" JNIEXPORT jboolean JNICALL Java_com_lanu_anc_NativeAudioEngine_lanuNativeIsRunning(JNIEnv*, jobject) { return g.running.load(std::memory_order_acquire) ? JNI_TRUE : JNI_FALSE; }
extern "C" JNIEXPORT jint JNICALL Java_com_lanu_anc_NativeAudioEngine_lanuNativeSampleRate(JNIEnv*, jobject) { return g.output ? AAudioStream_getSampleRate(g.output) : 0; }
extern "C" JNIEXPORT jint JNICALL Java_com_lanu_anc_NativeAudioEngine_lanuNativeFramesPerBurst(JNIEnv*, jobject) { return g.output ? AAudioStream_getFramesPerBurst(g.output) : 0; }
extern "C" JNIEXPORT jint JNICALL Java_com_lanu_anc_NativeAudioEngine_lanuNativeBufferSizeInFrames(JNIEnv*, jobject) { return g.output ? AAudioStream_getBufferSizeInFrames(g.output) : 0; }
extern "C" JNIEXPORT jint JNICALL Java_com_lanu_anc_NativeAudioEngine_lanuNativeXRunCount(JNIEnv*, jobject) { return g.output ? AAudioStream_getXRunCount(g.output) : 0; }
extern "C" JNIEXPORT jint JNICALL Java_com_lanu_anc_NativeAudioEngine_lanuNativeInputDeviceId(JNIEnv*, jobject) { return g.input ? AAudioStream_getDeviceId(g.input) : 0; }
extern "C" JNIEXPORT jint JNICALL Java_com_lanu_anc_NativeAudioEngine_lanuNativeOutputDeviceId(JNIEnv*, jobject) { return g.output ? AAudioStream_getDeviceId(g.output) : 0; }
extern "C" JNIEXPORT jint JNICALL Java_com_lanu_anc_NativeAudioEngine_lanuNativeInputChannelCount(JNIEnv*, jobject) { return g.input ? AAudioStream_getChannelCount(g.input) : 0; }
extern "C" JNIEXPORT jint JNICALL Java_com_lanu_anc_NativeAudioEngine_lanuNativeOutputChannelCount(JNIEnv*, jobject) { return g.output ? AAudioStream_getChannelCount(g.output) : 0; }
EOF

package com.lanu.anc

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/** Local mono microphone monitor; never intercepts calls or produces physical anti-noise. */
class AudioEngine(private val context: Context) {
    enum class State { IDLE, STARTING, RUNNING, STOPPING, ERROR }
    @Volatile var state = State.IDLE; private set
    @Volatile var inputDbFs = -120f; private set
    @Volatile var routeName = "-"; private set
    @Volatile var processingName = "Bekleniyor"; private set
    @Volatile var sampleRate = 0; private set
    @Volatile var lastError: String? = null; private set
    @Volatile var gain = SingleEarPolicy.DEFAULT_GAIN; private set
    @Volatile var reductionEnabled = true; private set
    @Volatile private var keepRunning = false
    @Volatile private var worker: Thread? = null
    private val lock = Any()
    private val manager = context.getSystemService(AudioManager::class.java)
    private val gate = BasicNoiseGate()
    private val limiter = DspPipeline()
    private var recorder: AudioRecord? = null
    private var player: AudioTrack? = null
    private var suppressor: NoiseSuppressor? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var focus: AudioFocusRequest? = null
    private var selectedOutput = 0
    private var previousMode = AudioManager.MODE_NORMAL
    private var changedMode = false
    private var callbackRegistered = false
    val running: Boolean get() = state == State.RUNNING

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(devices: Array<out AudioDeviceInfo>) {
            if (keepRunning && devices.any { it.id == selectedOutput }) fail("Kulaklık bağlantısı kesildi. Yeniden bağlayıp başlatın.")
        }
    }
    fun setGain(value: Float) { gain = SingleEarPolicy.gain(value) }
    fun setReduction(enabled: Boolean) { reductionEnabled = enabled }

    fun start(): Boolean = synchronized(lock) {
        if (state == State.RUNNING || state == State.STARTING) return true
        if (worker?.isAlive == true) return false
        state = State.STARTING
        lastError = null
        try {
            check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { "Mikrofon izni gerekli." }
            check(!isCallActive()) { "Görüşme sırasında kullanılamaz. Bu uygulama arama sesini filtrelemez." }
            val output = chooseOutput() ?: error("Bir kulaklık bağlayın. Sağ veya sol tek kulaklık yeterli.")
            selectedOutput = output.id
            routeName = output.productName.toString().ifBlank { "Kulaklık" }
            val communication = output.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || output.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            previousMode = manager.mode
            if (communication) {
                manager.mode = AudioManager.MODE_IN_COMMUNICATION
                changedMode = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val device = manager.availableCommunicationDevices.firstOrNull { it.id == output.id }
                        ?: error("Bluetooth görüşme ses yolu bulunamadı. Kulaklığı yeniden bağlayın.")
                    check(manager.setCommunicationDevice(device)) { "Bluetooth ses yolu seçilemedi." }
                } else {
                    @Suppress("DEPRECATION") manager.startBluetoothSco()
                    @Suppress("DEPRECATION") manager.isBluetoothScoOn = true
                }
            }
            val usage = if (communication) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA
            val attrs = AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener({ change -> if (change < 0 && keepRunning) fail("Başka bir uygulama sesi kullanıyor. Dinleme durduruldu.") }, Handler(Looper.getMainLooper())).build()
            check(manager.requestAudioFocus(focus!!) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "Ses kullanımı başka bir uygulamada. Görüşmeyi kapatıp tekrar deneyin." }
            openAudio(chooseInput(output, communication), output, attrs, communication)
            gate.reset(); limiter.reset(); setupEffects()
            manager.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))
            callbackRegistered = true
            keepRunning = true
            state = State.RUNNING
            worker = Thread({ audioLoop(output.id) }, "LANU-Mono-Audio").also { it.start() }
            Log.i("LANU", "Mono monitor started: rate=$sampleRate output=${output.id}")
            true
        } catch (e: Exception) {
            lastError = if (e is SecurityException) "Mikrofon / yakındaki cihaz izni gerekli. Telefon ayarlarından LANU izinlerini açın." else e.message ?: "Ses başlatılamadı."
            Log.w("LANU", "Audio start failed", e)
            state = State.ERROR
            cleanup()
            false
        }
    }

    private fun openAudio(input: AudioDeviceInfo?, output: AudioDeviceInfo, attrs: AudioAttributes, communication: Boolean) {
        var last: Exception? = null
        val rates = if (communication) intArrayOf(16000, 48000, 8000) else intArrayOf(48000, 44100, 16000)
        for (rate in rates) for (source in intArrayOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.MIC)) {
            var capture: AudioRecord? = null
            var playback: AudioTrack? = null
            try {
                val cb = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val pb = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                require(cb > 0 && pb > 0)
                capture = AudioRecord.Builder().setAudioSource(source)
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                    .setBufferSizeInBytes(maxOf(cb * 2, rate / 25 * 2)).build()
                check(capture.state == AudioRecord.STATE_INITIALIZED)
                if (input != null) check(capture.setPreferredDevice(input)) { "Mikrofon seçilemedi." }
                playback = AudioTrack.Builder().setAudioAttributes(attrs)
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(maxOf(pb * 2, rate / 25 * 2)).setTransferMode(AudioTrack.MODE_STREAM).build()
                check(playback.state == AudioTrack.STATE_INITIALIZED)
                check(playback.setPreferredDevice(output)) { "Kulaklık seçilemedi." }
                capture.startRecording()
                check(capture.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                playback.play()
                // Routing is valid only after start. Keep output silent until it is verified.
                val silence = ShortArray(maxOf(160, rate / 100))
                val deadline = SystemClock.elapsedRealtime() + 1500
                while (playback.routedDevice?.id != output.id && SystemClock.elapsedRealtime() < deadline) {
                    check(playback.write(silence, 0, silence.size, AudioTrack.WRITE_NON_BLOCKING) >= 0)
                    Thread.sleep(20)
                }
                check(playback.routedDevice?.id == output.id) { "Ses kulaklığa yönlendirilemedi. Telefon hoparlörüne ses verilmedi." }
                recorder = capture; player = playback; sampleRate = rate
                return
            } catch (e: Exception) {
                last = e
                runCatching { capture?.stop() }; runCatching { playback?.stop() }
                runCatching { capture?.release() }; runCatching { playback?.release() }
            }
        }
        throw IllegalStateException(last?.message ?: "Desteklenen ses biçimi bulunamadı.", last)
    }
    private fun setupEffects() {
        val session = recorder!!.audioSessionId
        suppressor = runCatching { if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(session) else null }.getOrNull()
        echoCanceler = runCatching { if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(session) else null }.getOrNull()
        runCatching { echoCanceler?.enabled = true }
        // AGC stays off: it can amplify room noise and feedback.
    }
    private fun chooseOutput(): AudioDeviceInfo? {
        val devices = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val order = intArrayOf(AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        for (type in order) devices.firstOrNull { it.type == type }?.let { return it }
        return null
    }
    private fun chooseInput(output: AudioDeviceInfo, communication: Boolean): AudioDeviceInfo? {
        val devices = manager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        if (output.type != AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) {
            devices.firstOrNull { it.type == output.type && (it.address == output.address || it.productName == output.productName) }?.let { return it }
        }
        // A2DP is playback-only: use the phone microphone, no fake headset capture.
        return if (communication) null else devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
    }
    private fun isCallActive(): Boolean = manager.mode == AudioManager.MODE_IN_CALL || manager.mode == AudioManager.MODE_IN_COMMUNICATION

    private fun audioLoop(expectedOutput: Int) {
        val record = recorder ?: return
        val track = player ?: return
        val block = ShortArray(maxOf(160, sampleRate / 100))
        var effectState: Boolean? = null
        var softwareGate = true
        var silenceBlocks = 0
        try {
            while (keepRunning && !Thread.currentThread().isInterrupted) {
                if (track.routedDevice?.id != expectedOutput || manager.mode == AudioManager.MODE_IN_CALL) { fail("Kulaklık ses yolu değişti veya telefon araması başladı."); break }
                val reduction = reductionEnabled
                if (effectState != reduction) {
                    val systemEnabled = runCatching {
                        val ns = suppressor ?: return@runCatching false
                        ns.enabled = reduction
                        reduction && ns.enabled
                    }.getOrDefault(false)
                    softwareGate = reduction && !systemEnabled
                    gate.reset()
                    processingName = when { !reduction -> "Filtre kapalı"; systemEnabled -> "Android mikrofon gürültü azaltma"; else -> "Temel filtre • konuşma araları" }
                    effectState = reduction
                }
                val read = record.read(block, 0, block.size, AudioRecord.READ_BLOCKING)
                check(read > 0) { "Mikrofon okunamadı ($read). Tekrar başlatın." }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && record.activeRecordingConfiguration?.isClientSilenced == true) { fail("Mikrofon başka bir uygulama tarafından kullanılıyor."); break }
                inputDbFs = AudioMath.rmsDbFs(block, read)
                if (inputDbFs < -119f) silenceBlocks++ else silenceBlocks = 0
                check(silenceBlocks < 500) { "Mikrofon 5 saniyedir veri vermiyor. İzinleri kontrol edin." }
                if (softwareGate) gate.process(block, read, sampleRate)
                limiter.process(block, read)
                val outputGain = gain
                for (i in 0 until read) block[i] = (block[i] * outputGain).toInt().toShort()
                var offset = 0
                while (keepRunning && offset < read) {
                    check(track.routedDevice?.id == expectedOutput) { "Kulaklık bağlantısı değişti." }
                    val written = track.write(block, offset, read - offset, AudioTrack.WRITE_BLOCKING)
                    check(written > 0) { "Kulaklığa ses gönderilemedi ($written)." }
                    offset += written
                }
            }
        } catch (e: Exception) {
            if (keepRunning) { lastError = e.message ?: "Ses işleme hatası."; state = State.ERROR; Log.w("LANU", "Audio loop failed", e) }
        } finally {
            synchronized(lock) { keepRunning = false; cleanup(); if (state != State.ERROR) state = State.IDLE; worker = null }
        }
    }
    private fun fail(message: String) {
        if (!keepRunning) return
        lastError = message; state = State.ERROR; keepRunning = false
        runCatching { player?.pause() }; runCatching { recorder?.stop() }
        Log.w("LANU", message)
    }
    fun stop() {
        val thread: Thread?
        synchronized(lock) { if (state != State.ERROR) state = State.STOPPING; keepRunning = false; thread = worker; runCatching { recorder?.stop() }; runCatching { player?.pause() } }
        if (thread != null && thread !== Thread.currentThread()) {
            thread.interrupt()
            try { thread.join(1500) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
        synchronized(lock) { if (thread?.isAlive != true) { cleanup(); worker = null; state = State.IDLE } }
    }
    private fun cleanup() {
        if (callbackRegistered) { runCatching { manager.unregisterAudioDeviceCallback(deviceCallback) }; callbackRegistered = false }
        runCatching { recorder?.stop() }; runCatching { player?.stop() }
        runCatching { suppressor?.release() }; runCatching { echoCanceler?.release() }
        runCatching { recorder?.release() }; runCatching { player?.release() }
        suppressor = null; echoCanceler = null; recorder = null; player = null
        focus?.let { runCatching { manager.abandonAudioFocusRequest(it) } }; focus = null
        if (changedMode) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) runCatching { manager.clearCommunicationDevice() }
            else { @Suppress("DEPRECATION") runCatching { manager.stopBluetoothSco(); manager.isBluetoothScoOn = false } }
            if (manager.mode == AudioManager.MODE_IN_COMMUNICATION) runCatching { manager.mode = previousMode }
            changedMode = false
        }
        selectedOutput = 0; inputDbFs = -120f; sampleRate = 0
    }
}

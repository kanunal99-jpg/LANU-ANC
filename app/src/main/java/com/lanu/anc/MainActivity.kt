package com.lanu.anc

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.view.ViewGroup
import android.widget.*

class MainActivity : android.app.Activity() {
    private var service: AudioProcessingService? = null
    private var binding = false
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var details: TextView
    private lateinit var start: Button
    private lateinit var stop: Button
    private lateinit var reduction: Switch
    private lateinit var volume: SeekBar
    private var permissionStart = false
    private var startPending = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as? AudioProcessingService.LocalBinder)?.service()
            service?.let { reduction.isChecked = it.reductionEnabled(); volume.progress = (it.gain() * 100).toInt() }
            if (startPending) { startPending = false; startAudio() }
            render()
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null; render() }
    }
    private val ticker = object : Runnable { override fun run() { render(); handler.postDelayed(this, 350) } }
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContentView(buildUi()) }
    override fun onStart() {
        super.onStart()
        binding = bindService(Intent(this, AudioProcessingService::class.java), connection, Context.BIND_AUTO_CREATE)
        handler.post(ticker)
    }
    override fun onStop() {
        handler.removeCallbacks(ticker)
        if (binding) { unbindService(connection); binding = false; service = null }
        super.onStop()
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == 10) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                status.text = "Mikrofon izni verilmedi. Ayarlar → Uygulamalar → LANU → İzinler bölümünden açabilirsiniz."
                permissionStart = false
                return
            }
            if (permissionStart) { permissionStart = false; startAudio() }
        }
    }
    private fun startAudio() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionStart = true
            val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) permissions += Manifest.permission.BLUETOOTH_CONNECT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) permissions += Manifest.permission.POST_NOTIFICATIONS
            requestPermissions(permissions.toTypedArray(), 10)
            return
        }
        if (service == null) { startPending = true; status.text = "Servis hazırlanıyor…"; return }
        try {
            start.isEnabled = false
            startForegroundService(Intent(this, AudioProcessingService::class.java).setAction(AudioProcessingService.ACTION_START))
        } catch (e: Exception) { status.text = "Başlatılamadı: ${e.message ?: "İzinleri kontrol edin"}"; start.isEnabled = true }
    }
    private fun buildUi(): ScrollView {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(24), dp(20), dp(24)) }
        fun text(value: String, size: Float = 16f): TextView = TextView(this).apply { text = value; textSize = size; setPadding(0, dp(10), 0, dp(6)) }.also { root.addView(it, fullWidth()) }
        text(getString(R.string.app_name), 28f)
        text(getString(R.string.subtitle), 18f)
        text(getString(R.string.safety_note), 14f)
        status = text("Hazır • bir kulaklık bağlayın").apply { id = 101 }
        details = text("Sağ veya sol kulaklık • mono ses").apply { id = 102 }
        reduction = Switch(this).apply { text = "Mikrofon gürültü azaltma"; isChecked = true; setOnCheckedChangeListener { _, checked -> service?.setReduction(checked) } }
        root.addView(reduction, fullWidth())
        text("Dinleme seviyesi • düşükten başlayın", 14f)
        volume = SeekBar(this).apply { max = 50; progress = 15; setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) { if (fromUser) service?.setGain(value / 100f) }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        }) }
        root.addView(volume, fullWidth())
        start = Button(this).apply { id = 103; text = getString(R.string.start); setOnClickListener { startAudio() } }
        stop = Button(this).apply { id = 104; text = getString(R.string.stop); setOnClickListener { service?.stopProcessing() } }
        root.addView(start, fullWidth()); root.addView(stop, fullWidth())
        text("Kullanım: Tek kulaklığı bağlayın → Başlat → Telefona veya kulaklık mikrofonuna konuşun. Filtreyi açıp kapatarak dinleyin. Bluetooth kullanırken gecikme duyabilirsiniz.", 14f)
        text(getString(R.string.footer), 12f)
        return ScrollView(this).apply { addView(root) }
    }
    private fun render() {
        val s = service ?: return
        status.text = when { s.preparing -> "Ses yolu hazırlanıyor…"; s.error() != null -> s.error(); s.isRunning() -> "Dinleme açık • tek kulaklık"; else -> "Hazır • sağ veya sol tek kulaklık yeterli" }
        details.text = if (s.isRunning()) "${s.routeName()}\n${s.processingName()}\nMono • ${s.sampleRate()} Hz • ${"%.1f".format(s.inputDbFs())} dBFS" else "Sağ / sol ayrımı gerekmez. Ses mono iletilir."
        start.isEnabled = !s.preparing && s.state() !in listOf(AudioEngine.State.STARTING, AudioEngine.State.RUNNING, AudioEngine.State.STOPPING)
        stop.isEnabled = s.preparing || s.isRunning()
    }
    private fun fullWidth() = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

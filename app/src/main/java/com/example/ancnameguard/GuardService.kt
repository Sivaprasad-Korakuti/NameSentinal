package com.example.ancnameguard

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.BatteryManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

class GuardService : Service() {

    companion object {
        const val ACTION_STOP = "com.example.ancnameguard.STOP"
        const val ACTION_TEST = "com.example.ancnameguard.TEST"
        const val CH_STATUS = "guard_status"
        const val CH_ALERT = "guard_alert"
        const val ID_STATUS = 1
        const val ID_ALERT = 2
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var audioManager: AudioManager
    private lateinit var prefs: SharedPreferences

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private var micWanted = false
    private var loopStarted = false
    private var cooldown = false
    private var beepMuted = false
    private var errorStreak = 0
    private var focusRequest: AudioFocusRequest? = null
    private var listenRunnable: Runnable? = null

    // ---------------------------------------------------------------- lifecycle

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Prefs.of(this).edit().putBoolean(Prefs.ARMED, false).apply()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_TEST) {
            if (GuardState.running && !cooldown) triggerAlert("test")
            return START_NOT_STICKY
        }
        if (!GuardState.running) startGuard()
        return START_NOT_STICKY
    }

    private fun startGuard() {
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        prefs = Prefs.of(this)
        prefs.edit().putBoolean(Prefs.ARMED, true).apply()
        createChannels()

        val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(this, ID_STATUS, statusNotification("Starting…"), type)

        GuardState.running = true
        GuardState.status = GuardState.Status.STANDBY

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                tts?.language = Locale("en", "IN")
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
            }
        }

        lowBattery = readLowBattery()
        ContextCompat.registerReceiver(
            this, batteryReceiver,
            IntentFilter().apply { addAction(Intent.ACTION_BATTERY_LOW); addAction(Intent.ACTION_BATTERY_OKAY) },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        audioManager.addOnModeChangedListener(ContextCompat.getMainExecutor(this), modeListener)
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        audioManager.registerAudioDeviceCallback(deviceCallback, handler)
        refreshRoute()
    }

    override fun onDestroy() {
        val wasRunning = GuardState.running
        GuardState.running = false
        GuardState.status = GuardState.Status.DISABLED
        GuardState.alert = false
        GuardState.level = 0f
        GuardState.bluetooth = null
        GuardState.transcript = "Auto-Guard disabled."

        handler.removeCallbacksAndMessages(null)
        if (wasRunning) {
            runCatching { prefs.unregisterOnSharedPreferenceChangeListener(prefListener) }
            runCatching { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
            runCatching { unregisterReceiver(batteryReceiver) }
            runCatching { audioManager.removeOnModeChangedListener(modeListener) }
            destroyRecognizer()
            setBeepMuted(false)
            abandonFocus()
        }
        tts?.stop()
        tts?.shutdown()
        tts = null
        GuardState.changed()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- state sync

    private var lowBattery = false
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { lowBattery = i.action == Intent.ACTION_BATTERY_LOW; handler.post { sync() } }
    }
    private val modeListener = AudioManager.OnModeChangedListener { handler.post { sync() } }

    private fun readLowBattery(): Boolean {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        val pct = i.getIntExtra(BatteryManager.EXTRA_LEVEL, 100) * 100 / i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        return pct <= 15 && i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) == 0
    }

    private fun inCall() = audioManager.mode.let {
        it == AudioManager.MODE_IN_CALL || it == AudioManager.MODE_IN_COMMUNICATION || it == AudioManager.MODE_RINGTONE
    }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        handler.post { sync() }
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = refreshRoute()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = refreshRoute()
    }

    private fun findBluetooth(): String? {
        val wanted = mutableSetOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        if (Build.VERSION.SDK_INT >= 31) {
            wanted.add(AudioDeviceInfo.TYPE_BLE_HEADSET)
            wanted.add(AudioDeviceInfo.TYPE_BLE_SPEAKER)
        }
        val dev = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type in wanted }
        return dev?.let { it.productName?.toString()?.takeIf { n -> n.isNotBlank() } ?: "Bluetooth audio" }
    }

    private fun refreshRoute() {
        if (!GuardState.running) return
        GuardState.bluetooth = findBluetooth()
        sync()
    }

    private fun sync() {
        if (!GuardState.running) return
        if (prefs.getBoolean(Prefs.SAVER, true) && (lowBattery || inCall())) {
            micWanted = false
            stopRecognition()
            GuardState.status = GuardState.Status.STANDBY
            GuardState.transcript = if (lowBattery) "🔋 Paused to save battery (15% or lower)." else "📞 Paused during your call."
            updateStatusNotification()
            GuardState.changed()
            return
        }
        val require = prefs.getBoolean(Prefs.REQUIRE_BT, true)
        if (!require || GuardState.bluetooth != null) {
            if (GuardState.status != GuardState.Status.LISTENING) {
                GuardState.transcript = if (GuardState.bluetooth != null)
                    "🎧 Earbuds verified. Listening for your name…"
                else "🎤 Listening for your name…"
            }
            GuardState.status = GuardState.Status.LISTENING
            micWanted = true
            if (!cooldown && !loopStarted) {
                loopStarted = true
                listen()
            }
        } else {
            micWanted = false
            stopRecognition()
            GuardState.status = GuardState.Status.STANDBY
            GuardState.transcript = "⏸️ Mic paused: connect your Bluetooth earbuds to auto-start."
        }
        updateStatusNotification()
        GuardState.changed()
    }

    // ---------------------------------------------------------------- recognition

    private fun recognizerIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
    }

    private fun listen() {
        if (!GuardState.running || !micWanted || cooldown) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            GuardState.transcript = "❌ No speech recognition service. Install/enable “Speech Services by Google”."
            GuardState.changed()
            return
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(listener) }
        }
        setBeepMuted(prefs.getBoolean(Prefs.MUTE_BEEPS, false))
        try {
            recognizer?.startListening(recognizerIntent())
        } catch (e: Exception) {
            destroyRecognizer()
            scheduleListen(1000)
        }
    }

    private fun scheduleListen(delayMs: Long) {
        listenRunnable?.let { handler.removeCallbacks(it) }
        val r = Runnable { listen() }
        listenRunnable = r
        handler.postDelayed(r, delayMs)
    }

    private fun stopRecognition() {
        loopStarted = false
        listenRunnable?.let { handler.removeCallbacks(it) }
        listenRunnable = null
        runCatching { recognizer?.cancel() }
        setBeepMuted(false)
    }

    private fun destroyRecognizer() {
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) { GuardState.level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onError(error: Int) {
            if (!micWanted || cooldown) return
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    errorStreak = 0
                    scheduleListen(150)
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    GuardState.transcript = "❌ Microphone permission missing."
                    GuardState.changed()
                    stopSelf()
                }
                else -> {
                    errorStreak++
                    GuardState.transcript = "⚠️ Recognizer error $error, retrying…"
                    GuardState.changed()
                    destroyRecognizer()
                    scheduleListen(min(1000L * errorStreak, 5000L))
                }
            }
        }

        override fun onResults(results: Bundle?) = handleResults(results, final = true)
        override fun onPartialResults(partialResults: Bundle?) = handleResults(partialResults, final = false)
    }

    private fun handleResults(bundle: Bundle?, final: Boolean) {
        if (!micWanted || cooldown) return
        val list = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        val text = list.firstOrNull().orEmpty()
        if (text.isNotEmpty()) {
            GuardState.transcript = text.lowercase()
            GuardState.changed()
        }
        val hit = findMatch(list)
        if (hit != null) {
            triggerAlert(hit)
            return
        }
        if (final) {
            errorStreak = 0
            scheduleListen(100)
        }
    }

    private fun findMatch(candidates: List<String>): String? {
        val words = (prefs.getString(Prefs.WAKE, Prefs.DEFAULT_WAKE) ?: "")
            .split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        for (c in candidates) {
            val text = c.lowercase()
            val tokens = text.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.toSet()
            val hit = words.firstOrNull { if (it.contains(' ')) text.contains(it) else it in tokens }
            if (hit != null) return hit
        }
        return null
    }

    // ---------------------------------------------------------------- alert

    private fun triggerAlert(word: String) {
        cooldown = true
        listenRunnable?.let { handler.removeCallbacks(it) }
        runCatching { recognizer?.cancel() }
        setBeepMuted(false)

        GuardState.alert = true
        GuardState.transcript = "✅ Detected keyword: “${word.uppercase()}”"
        GuardState.changed()

        if (prefs.getBoolean(Prefs.USE_VIBRATE, true)) vibrate()
        requestDuck()
        if (prefs.getBoolean(Prefs.USE_CHIME, true)) playChime()
        if (prefs.getBoolean(Prefs.USE_NOTIF, true)) postAlertNotification(word)
        if (prefs.getBoolean(Prefs.USE_VOICE, true)) {
            handler.postDelayed({ speakAlert() }, 550)
        }
        handler.postDelayed({ endCooldown() }, 5000)
    }

    private fun endCooldown() {
        if (tts?.isSpeaking == true) {
            handler.postDelayed({ endCooldown() }, 1000)
            return
        }
        cooldown = false
        GuardState.alert = false
        abandonFocus()
        if (GuardState.running && micWanted) {
            GuardState.transcript = "🎧 Listening for your name…"
            GuardState.changed()
            listen()
        } else {
            GuardState.changed()
        }
    }

    private fun speakAlert() {
        if (!ttsReady) return
        val msg = prefs.getString(Prefs.MSG, Prefs.DEFAULT_MSG) ?: Prefs.DEFAULT_MSG
        tts?.speak(msg, TextToSpeech.QUEUE_FLUSH, null, "alert")
    }

    @Suppress("DEPRECATION")
    private fun vibrate() {
        val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 100, 300), -1))
    }

    private fun requestDuck() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .build()
        focusRequest = req
        audioManager.requestAudioFocus(req)
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    /** Two-tone chime generated in code (no audio asset needed). */
    private fun playChime() {
        val sr = 44100
        val pcm = ArrayList<Short>()
        fun tone(freq: Double, dur: Double) {
            val n = (sr * dur).toInt()
            for (i in 0 until n) {
                val t = i.toDouble() / sr
                val env = exp(-5 * t / dur)
                pcm.add((sin(2 * PI * freq * t) * env * 0.6 * Short.MAX_VALUE).toInt().toShort())
            }
        }
        tone(880.0, 0.2)
        tone(1174.66, 0.3)
        val data = pcm.toShortArray()

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sr)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(data.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(data, 0, data.size)
        track.play()
        handler.postDelayed({ runCatching { track.release() } }, 900)
    }

    /** Optional, best-effort: some phones beep each time recognition restarts. */
    private fun setBeepMuted(mute: Boolean) {
        if (mute == beepMuted) return
        try {
            val dir = if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
            audioManager.adjustStreamVolume(AudioManager.STREAM_SYSTEM, dir, 0)
            audioManager.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, dir, 0)
            beepMuted = mute
        } catch (_: SecurityException) {
            // Do Not Disturb policy prevents it; ignore.
        }
    }

    // ---------------------------------------------------------------- notifications

    private fun createChannels() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CH_STATUS, "Guard status", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "Name alerts", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun statusNotification(text: String): Notification {
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, GuardService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Name Sentinel")
            .setContentText(text)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun updateStatusNotification() {
        val text = when (GuardState.status) {
            GuardState.Status.LISTENING -> "Listening for your name…"
            GuardState.Status.STANDBY -> "Standby: mic is off"
            GuardState.Status.DISABLED -> "Disabled"
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(ID_STATUS, statusNotification(text))
    }

    @SuppressLint("MissingPermission")
    private fun postAlertNotification(word: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val n = NotificationCompat.Builder(this, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("🚨 Someone is calling you!")
            .setContentText("Heard “$word” nearby. Check around you!")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        NotificationManagerCompat.from(this).notify(ID_ALERT, n)
    }
}

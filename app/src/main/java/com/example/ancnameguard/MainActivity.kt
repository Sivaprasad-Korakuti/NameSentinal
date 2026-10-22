package com.example.ancnameguard

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.SpeechRecognizer
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var tvBt: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvTranscript: TextView
    private lateinit var tvAlert: TextView
    private lateinit var scene: WebView
    private lateinit var btnToggle: MaterialButton
    private var sceneMode = 0
    private val ui = Handler(Looper.getMainLooper())

    // Feeds live state (mode, alert, mic level) into the 3D scene ~14x per second while visible.
    private val pump = object : Runnable {
        override fun run() {
            scene.evaluateJavascript(
                "window.setState&&setState($sceneMode,${GuardState.alert},${GuardState.level})", null
            )
            ui.postDelayed(this, 70)
        }
    }

    inner class Bridge {
        @JavascriptInterface fun tap() { runOnUiThread { onToggle() } }
    }

    private val stateListener: () -> Unit = { render() }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasMic()) startGuard()
            else Toast.makeText(this, "Microphone permission is required.", Toast.LENGTH_LONG).show()
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs.of(this)

        tvBt = findViewById(R.id.tvBt)
        tvStatus = findViewById(R.id.tvStatus)
        tvTranscript = findViewById(R.id.tvTranscript)
        tvAlert = findViewById(R.id.tvAlert)
        btnToggle = findViewById(R.id.btnToggle)

        scene = findViewById(R.id.scene)
        scene.setBackgroundColor(Color.TRANSPARENT)
        scene.settings.javaScriptEnabled = true
        scene.addJavascriptInterface(Bridge(), "Bridge")
        scene.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                scene.evaluateJavascript("window.setChar&&setChar('${prefs.getString(Prefs.CHAR, "robot")}')", null)
            }
        }
        scene.loadUrl("file:///android_asset/scene.html")

        val chars = mapOf(R.id.chRobot to "robot", R.id.chOwl to "owl", R.id.chAlien to "alien")
        fun mark() = chars.forEach { (id, n) ->
            findViewById<View>(id).alpha = if (prefs.getString(Prefs.CHAR, "robot") == n) 1f else 0.5f
        }
        chars.forEach { (id, n) ->
            findViewById<View>(id).setOnClickListener {
                prefs.edit().putString(Prefs.CHAR, n).apply()
                scene.evaluateJavascript("window.setChar&&setChar('$n')", null)
                mark()
            }
        }
        mark()

        findViewById<View>(R.id.btnTest).setOnClickListener {
            if (GuardState.running)
                startService(Intent(this, GuardService::class.java).setAction(GuardService.ACTION_TEST))
            else Toast.makeText(this, "Enable Auto-Guard first.", Toast.LENGTH_SHORT).show()
        }

        val etWake = findViewById<EditText>(R.id.etWake)
        val etMsg = findViewById<EditText>(R.id.etMsg)
        etWake.setText(prefs.getString(Prefs.WAKE, Prefs.DEFAULT_WAKE))
        etMsg.setText(prefs.getString(Prefs.MSG, Prefs.DEFAULT_MSG))
        etWake.doAfterTextChanged { prefs.edit().putString(Prefs.WAKE, it.toString()).apply() }
        etMsg.doAfterTextChanged { prefs.edit().putString(Prefs.MSG, it.toString()).apply() }

        bindSwitch(R.id.swBt, Prefs.REQUIRE_BT, true)
        bindSwitch(R.id.swVoice, Prefs.USE_VOICE, true)
        bindSwitch(R.id.swNotif, Prefs.USE_NOTIF, true)
        bindSwitch(R.id.swChime, Prefs.USE_CHIME, true)
        bindSwitch(R.id.swVibrate, Prefs.USE_VIBRATE, true)
        bindSwitch(R.id.swSaver, Prefs.SAVER, true)
        bindSwitch(R.id.swMute, Prefs.MUTE_BEEPS, false)

        btnToggle.setOnClickListener { onToggle() }
        findViewById<View>(R.id.btnBattery).setOnClickListener { showSetup() }
        autoArm(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        autoArm(intent)
    }

    private fun autoArm(i: Intent?) {
        if (i?.getBooleanExtra("arm", false) == true && !GuardState.running) {
            i.removeExtra("arm")
            onToggle()
        }
    }

    override fun onStart() {
        super.onStart()
        GuardState.add(stateListener)
        scene.onResume()
        ui.post(pump)
        render()
    }

    override fun onStop() {
        ui.removeCallbacks(pump)
        scene.onPause()           // stops 3D rendering: zero GPU/battery cost when the app is in the background
        GuardState.remove(stateListener)
        super.onStop()
    }

    override fun onDestroy() {
        scene.destroy()
        super.onDestroy()
    }

    private fun bindSwitch(id: Int, key: String, default: Boolean) {
        val sw = findViewById<SwitchCompat>(id)
        sw.isChecked = prefs.getBoolean(key, default)
        sw.setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(key, checked).apply() }
    }

    private fun hasMic() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun onToggle() {
        if (GuardState.running) {
            prefs.edit().putBoolean(Prefs.ARMED, false).apply()
            stopService(Intent(this, GuardService::class.java))
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(
                this, "No speech recognition service. Install or update “Speech Services by Google”.", Toast.LENGTH_LONG
            ).show()
            return
        }
        val needed = mutableListOf<String>()
        if (!hasMic()) needed.add(Manifest.permission.RECORD_AUDIO)
        for (p in listOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.BLUETOOTH_CONNECT))
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) needed.add(p)
        if (needed.isEmpty()) startGuard() else permissionLauncher.launch(needed.toTypedArray())
    }

    private fun startGuard() {
        ContextCompat.startForegroundService(this, Intent(this, GuardService::class.java))
        if (!prefs.getBoolean("setup_shown", false)) {
            prefs.edit().putBoolean("setup_shown", true).apply()
            showSetup()
        }
    }

    // ---------------------------------------------------------------- OPPO / ColorOS keep-alive help

    private fun showSetup() {
        AlertDialog.Builder(this)
            .setTitle("Keep it alive on OPPO (ColorOS)")
            .setMessage(
                "Do these once so ColorOS never kills the guard:\n\n" +
                "1. Battery: allow unrestricted / background activity.\n" +
                "2. Auto-launch: turn ON for Name Sentinel.\n" +
                "3. Recent apps: long-press the Name Sentinel card and tap Lock.\n" +
                "4. If listening fails, update “Speech Services by Google” in Play Store."
            )
            .setPositiveButton("Auto-launch") { _, _ -> openAutoLaunch() }
            .setNeutralButton("Battery") { _, _ -> openBattery() }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun openBattery() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun openAutoLaunch() {
        val targets = listOf(
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
            "com.oplus.battery" to "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity",
            "com.coloros.oppoguardelf" to "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"
        )
        for ((pkg, cls) in targets) {
            try { startActivity(Intent().setComponent(ComponentName(pkg, cls))); return } catch (_: Exception) {}
        }
        Toast.makeText(this, "Open App info > Battery / Auto-launch for Name Sentinel.", Toast.LENGTH_LONG).show()
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    // ---------------------------------------------------------------- render

    private fun render() {
        val bt = GuardState.bluetooth
        tvBt.text = when {
            bt != null -> "🎧 $bt"
            GuardState.running -> "No Bluetooth earbuds detected"
            else -> "Bluetooth not scanned yet"
        }
        tvBt.setTextColor(Color.parseColor(if (bt != null) "#E879F9" else "#8AA0C8"))

        val (label, col, mode) = when {
            GuardState.alert -> Triple("Your name was called!", "#FF2D4B", 2)
            GuardState.status == GuardState.Status.LISTENING -> Triple("Listening for your name", "#22D3EE", 2)
            GuardState.status == GuardState.Status.STANDBY -> Triple("On standby (mic off)", "#FBBF24", 1)
            else -> Triple("Sentinel offline", "#8AA0C8", 0)
        }
        sceneMode = mode
        tvStatus.text = label
        tvStatus.setTextColor(Color.parseColor(col))
        tvTranscript.text = GuardState.transcript
        tvAlert.visibility = if (GuardState.alert) View.VISIBLE else View.GONE

        if (GuardState.running) {
            btnToggle.text = "Disable Auto-Guard"
            btnToggle.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#D7263D"))
            btnToggle.setTextColor(Color.WHITE)
        } else {
            btnToggle.text = "Enable Auto-Guard"
            btnToggle.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#22D3EE"))
            btnToggle.setTextColor(Color.parseColor("#06061A"))
        }
    }
}

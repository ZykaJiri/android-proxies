package com.truthshield.androidproxies

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var settings: SettingsStore
    private lateinit var status: TextView
    private lateinit var battStatus: TextView
    private lateinit var a11yStatus: TextView
    private lateinit var toggleBtn: Button

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val running = intent.getBooleanExtra(ProxyService.EXTRA_RUNNING, false)
            applyRunningState(running)
        }
    }

    private val askNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, "Foreground notification disabled — service may not show status", Toast.LENGTH_LONG).show()
        }
    }

    private val askRecordAudio = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) openAssistantSettings() else Toast.makeText(
            this, "RECORD_AUDIO is required so the system will let this app act as the assistant", Toast.LENGTH_LONG
        ).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settings = SettingsStore(this)

        val host = findViewById<EditText>(R.id.host)
        val port = findViewById<EditText>(R.id.port)
        val user = findViewById<EditText>(R.id.user)
        val key = findViewById<EditText>(R.id.key)
        val pass = findViewById<EditText>(R.id.password)
        val remoteHost = findViewById<EditText>(R.id.remoteBindHost)
        val remotePort = findViewById<EditText>(R.id.remoteBindPort)
        val localPort = findViewById<EditText>(R.id.localProxyPort)
        val killStale = findViewById<CheckBox>(R.id.killStale)
        val splitNetworks = findViewById<CheckBox>(R.id.splitNetworks)
        val cycleEnabled = findViewById<CheckBox>(R.id.cycleEnabled)
        val cycleInterval = findViewById<EditText>(R.id.cycleInterval)
        val cycleDuration = findViewById<EditText>(R.id.cycleDuration)
        val a11yBtn = findViewById<Button>(R.id.a11yBtn)
        a11yStatus = findViewById(R.id.a11yStatus)
        val autoStart = findViewById<CheckBox>(R.id.autoStart)
        val battBtn = findViewById<Button>(R.id.battBtn)
        battStatus = findViewById(R.id.battStatus)
        toggleBtn = findViewById(R.id.toggleBtn)
        status = findViewById(R.id.status)

        host.setText(settings.sshHost)
        port.setText(settings.sshPort.toString())
        user.setText(settings.sshUser)
        key.setText(settings.privateKeyPem)
        pass.setText(settings.password)
        remoteHost.setText(settings.remoteBindHost)
        remotePort.setText(settings.remoteBindPort.toString())
        localPort.setText(settings.localProxyPort.toString())
        killStale.isChecked = settings.killStale
        splitNetworks.isChecked = settings.splitNetworks
        cycleEnabled.isChecked = settings.cycleEnabled
        cycleInterval.setText(settings.cycleIntervalSec.toString())
        cycleDuration.setText(settings.airplaneOnSec.toString())
        autoStart.isChecked = settings.autoStart

        killStale.setOnCheckedChangeListener { _, checked ->
            settings.killStale = checked
        }
        splitNetworks.setOnCheckedChangeListener { _, checked ->
            settings.splitNetworks = checked
        }
        cycleEnabled.setOnCheckedChangeListener { _, checked ->
            settings.cycleEnabled = checked
        }
        a11yBtn.setOnClickListener { requestAssistantRole() }
        autoStart.setOnCheckedChangeListener { _, checked ->
            settings.autoStart = checked
        }

        battBtn.setOnClickListener { requestBatteryExempt() }

        toggleBtn.setOnClickListener {
            if (isServiceRunning()) {
                ProxyService.stop(this)
                status.text = "Service stopping…"
                return@setOnClickListener
            }

            val hostV = host.text.toString().trim()
            val portV = port.text.toString().toIntOrNull()
            val userV = user.text.toString().trim()
            val keyV = key.text.toString()
            val passV = pass.text.toString()
            val rhostV = remoteHost.text.toString().trim().ifEmpty { "127.0.0.1" }
            val rportV = remotePort.text.toString().toIntOrNull()
            val lportV = localPort.text.toString().toIntOrNull()

            if (hostV.isEmpty() || userV.isEmpty() ||
                portV == null || rportV == null || lportV == null
            ) {
                Toast.makeText(this, "Fill host, user, and all ports", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (portV !in 1..65535 || rportV !in 1..65535 || lportV !in 1..65535) {
                Toast.makeText(this, "Ports must be 1-65535", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val hasKey = keyV.isNotBlank()
            val hasPass = passV.isNotBlank()
            if (!hasKey && !hasPass) {
                Toast.makeText(this, "Provide either a private key or a password", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (hasKey && hasPass) {
                Toast.makeText(this, "Provide only one: private key OR password", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            settings.sshHost = hostV
            settings.sshPort = portV
            settings.sshUser = userV
            settings.privateKeyPem = keyV
            settings.password = passV
            settings.remoteBindHost = rhostV
            settings.remoteBindPort = rportV
            settings.localProxyPort = lportV
            cycleInterval.text.toString().toIntOrNull()?.let { settings.cycleIntervalSec = it.coerceAtLeast(30) }
            cycleDuration.text.toString().toIntOrNull()?.let { settings.airplaneOnSec = it.coerceAtLeast(2) }

            maybeRequestNotificationPermission()
            ProxyService.start(this)
            status.text = "Service starting…"
        }
    }

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(
            this,
            stateReceiver,
            IntentFilter(ProxyService.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        applyRunningState(isServiceRunning())
        refreshBatteryStatus()
        refreshA11yStatus()
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(stateReceiver) } catch (_: Exception) {}
    }

    private fun applyRunningState(running: Boolean) {
        toggleBtn.text = if (running) "Stop" else "Start"
        status.text = if (running) "Service running (see notification for live status)" else "Service stopped"
    }

    private fun refreshA11yStatus() {
        val ok = AssistantVoiceInteractionService.isDefaultAssistant(this)
        a11yStatus.text = if (ok) "Default digital assistant: this app ✓"
        else "Default digital assistant: NOT this app — airplane cycling won't work until set"
    }

    private fun requestAssistantRole() {
        if (AssistantVoiceInteractionService.isDefaultAssistant(this)) {
            Toast.makeText(this, "Already the default assistant", Toast.LENGTH_SHORT).show()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            askRecordAudio.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        openAssistantSettings()
    }

    private fun openAssistantSettings() {
        Toast.makeText(
            this,
            "Pick 'Android Proxies' under Digital assistant app",
            Toast.LENGTH_LONG,
        ).show()
        val candidates = listOf(
            Intent("android.settings.VOICE_INPUT_SETTINGS"),
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in candidates) {
            try {
                startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
            } catch (e: Exception) {
                android.util.Log.w("MainActivity", "intent failed: $intent", e)
            }
        }
        Toast.makeText(this, "Open Settings → Apps → Default apps → Digital assistant app manually", Toast.LENGTH_LONG).show()
    }

    private fun refreshBatteryStatus() {
        val pm = getSystemService(PowerManager::class.java)
        val ok = pm?.isIgnoringBatteryOptimizations(packageName) == true
        battStatus.text = if (ok) "Battery optimization: exempt ✓" else "Battery optimization: ACTIVE — service may be killed in background"
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryExempt() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm?.isIgnoringBatteryOptimizations(packageName) == true) {
            Toast.makeText(this, "Already exempt", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun isServiceRunning(): Boolean {
        @Suppress("DEPRECATION")
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.getRunningServices(Int.MAX_VALUE).any { it.service.className == ProxyService::class.java.name }
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        askNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

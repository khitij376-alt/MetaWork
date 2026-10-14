package com.metawork.agent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var transcriptView: TextView
    private lateinit var accessibilityBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0B0F19"))
            setPadding(56, 56, 56, 56)
        }

        val titleView = TextView(this).apply {
            text = "METAWORK 24/7 VOICE AGENT"
            textSize = 24f
            setTextColor(Color.parseColor("#38BDF8"))
            gravity = Gravity.CENTER
        }

        statusView = TextView(this).apply {
            text = "24/7 Voice Loop Active — Speak Anytime"
            textSize = 17f
            setTextColor(Color.parseColor("#10B981"))
            gravity = Gravity.CENTER
            setPadding(0, 36, 0, 24)
        }

        transcriptView = TextView(this).apply {
            text = "Say: \"Book a train ticket on IRCTC\", \"Book an Uber\", or \"Send WhatsApp message\""
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(16, 24, 16, 48)
        }

        accessibilityBtn = Button(this).apply {
            text = "Enable Screen Controller (One-Time Setup)"
            setBackgroundColor(Color.parseColor("#1E293B"))
            setTextColor(Color.parseColor("#38BDF8"))
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        root.addView(titleView)
        root.addView(statusView)
        root.addView(transcriptView)
        root.addView(accessibilityBtn)
        setContentView(root)

        VoiceAgentForegroundService.statusCallback = { state ->
            runOnUiThread { statusView.text = state }
        }
        VoiceAgentForegroundService.transcriptCallback = { line ->
            runOnUiThread { transcriptView.text = line }
        }

        ensurePermissionsAndStart()
    }

    override fun onResume() {
        super.onResume()
        accessibilityBtn.visibility = if (AgentAccessibilityService.instance == null) {
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }
    }

    private fun ensurePermissionsAndStart() {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.SEND_SMS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100)
            return
        }

        try {
            val pm = getSystemService(PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }
        } catch (_: Throwable) {}

        ContextCompat.startForegroundService(this, Intent(this, VoiceAgentForegroundService::class.java))
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        ContextCompat.startForegroundService(this, Intent(this, VoiceAgentForegroundService::class.java))
    }
}

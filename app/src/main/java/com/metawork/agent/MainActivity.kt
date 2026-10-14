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
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0B0F19"))
            setPadding(64, 64, 64, 64)
        }
        val titleView = TextView(this).apply {
            text = "METAWORK 24/7 VOICE AGENT"
            textSize = 26f
            setTextColor(Color.parseColor("#38BDF8"))
            gravity = Gravity.CENTER
        }
        statusView = TextView(this).apply {
            text = "Initializing 24/7 Voice-Only Mode (No Keyboard)..."
            textSize = 17f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 48, 0, 0)
        }
        root.addView(titleView)
        root.addView(statusView)
        setContentView(root)

        VoiceAgentForegroundService.statusCallback = { state ->
            runOnUiThread { statusView.text = state }
        }
        ensurePermissionsAndStart()
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

        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        if (AgentAccessibilityService.instance == null) {
            statusView.text = "Enable 'MetaWork Autonomous Screen Controller' in Accessibility Settings once, then speak freely."
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        ContextCompat.startForegroundService(this, Intent(this, VoiceAgentForegroundService::class.java))
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        ensurePermissionsAndStart()
    }
}

package com.metawork.agent

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0B0F19"))
            addView(TextView(this@MainActivity).apply {
                text = "METAWORK 24/7 VOICE AGENT"
                textSize = 24f
                setTextColor(Color.parseColor("#38BDF8"))
                gravity = Gravity.CENTER
            })
        }
        setContentView(root)
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.SEND_SMS, Manifest.permission.CALL_PHONE, Manifest.permission.READ_CONTACTS), 100)
        ContextCompat.startForegroundService(this, Intent(this, VoiceAgentForegroundService::class.java))
    }
}
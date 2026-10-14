package com.metawork.agent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    companion object {
        @Volatile
        var activeInstance: MainActivity? = null
            private set
    }

    private lateinit var faceView: AgentFaceView
    private lateinit var statusBadge: TextView
    private lateinit var transcriptView: TextView
    private lateinit var logView: TextView
    private lateinit var micStatusPill: TextView
    private lateinit var accStatusPill: TextView
    private lateinit var wakeBtn: Button
    private lateinit var muteToggleBtn: Button
    private lateinit var accessibilityBannerBtn: Button

    private val logLines = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activeInstance = this
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val dp = resources.displayMetrics.density
        fun Int.dp(): Int = (this * dp).toInt()

        val scrollRoot = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#070B14"))
            isFillViewport = true
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18.dp(), 20.dp(), 18.dp(), 32.dp())
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(4.dp(), 0, 4.dp(), 12.dp())
        }
        val brandTitle = TextView(this).apply {
            text = "MUSE AI • VOICE AGENT"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#F8FAFC"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        statusBadge = TextView(this).apply {
            text = "🌙 SLEEPING — Say 'Muse'"
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#818CF8"))
            background = roundedBg("#1E1B4B", "#818CF8", 999.dp())
            setPadding(12.dp(), 6.dp(), 12.dp(), 6.dp())
        }
        headerRow.addView(brandTitle)
        headerRow.addView(statusBadge)
        container.addView(headerRow)

        val heroCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = roundedBg("#111827", "#1E293B", 20.dp())
            setPadding(16.dp(), 20.dp(), 16.dp(), 18.dp())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 14.dp()
            }
        }

        faceView = AgentFaceView(this, isMiniOverlay = false).apply {
            layoutParams = LinearLayout.LayoutParams(165.dp(), 165.dp())
            setOnClickListener {
                VoiceAgentForegroundService.instance?.wakeUpMuse("Yes? Tell me your command.")
            }
        }

        val tapFaceHint = TextView(this).apply {
            text = "SAY 'MUSE' OR TAP MY FACE TO WAKE ME UP"
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#38BDF8"))
            gravity = Gravity.CENTER
            setPadding(0, 10.dp(), 0, 6.dp())
        }

        transcriptView = TextView(this).apply {
            text = "🌙 Muse is Sleeping. Say 'Muse' (e.g. 'Muse open WhatsApp and message Rahul hello')."
            textSize = 14f
            setTextColor(Color.parseColor("#F1F5F9"))
            gravity = Gravity.CENTER
            setPadding(8.dp(), 4.dp(), 8.dp(), 10.dp())
        }

        val modeButtonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        wakeBtn = Button(this).apply {
            text = "✨ Wake Muse"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#052E16"))
            background = roundedBg("#10B981", "#059669", 10.dp())
            setPadding(10.dp(), 10.dp(), 10.dp(), 10.dp())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = 6.dp()
            }
            setOnClickListener {
                VoiceAgentForegroundService.instance?.wakeUpMuse("Yes? Tell me your command.")
                refreshSystemPills()
            }
        }

        muteToggleBtn = Button(this).apply {
            text = "🔇 Mute Mic"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#F8FAFC"))
            background = roundedBg("#1E293B", "#475569", 10.dp())
            setPadding(10.dp(), 10.dp(), 10.dp(), 10.dp())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = 6.dp()
            }
            setOnClickListener {
                val muted = VoiceAgentForegroundService.instance?.toggleFullMicMute() ?: false
                text = if (muted) "🌙 Enable 'Muse' Wake" else "🔇 Mute Mic"
                refreshSystemPills()
            }
        }

        modeButtonRow.addView(wakeBtn)
        modeButtonRow.addView(muteToggleBtn)

        heroCard.addView(faceView)
        heroCard.addView(tapFaceHint)
        heroCard.addView(transcriptView)
        heroCard.addView(modeButtonRow)
        container.addView(heroCard)

        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 12.dp()
            }
        }
        micStatusPill = TextView(this).apply {
            text = "🎙️ Mic: Checking..."
            textSize = 12f
            setTextColor(Color.WHITE)
            background = roundedBg("#111827", "#334155", 12.dp())
            setPadding(12.dp(), 10.dp(), 12.dp(), 10.dp())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = 6.dp()
            }
        }
        accStatusPill = TextView(this).apply {
            text = "📱 Screen AI: Checking..."
            textSize = 12f
            setTextColor(Color.WHITE)
            background = roundedBg("#111827", "#334155", 12.dp())
            setPadding(12.dp(), 10.dp(), 12.dp(), 10.dp())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = 6.dp()
            }
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        statusRow.addView(micStatusPill)
        statusRow.addView(accStatusPill)
        container.addView(statusRow)

        accessibilityBannerBtn = Button(this).apply {
            text = "⚡ TAP HERE TO ENABLE MUSE SCREEN CONTROLLER & FLOATING FACE"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#052E16"))
            background = roundedBg("#10B981", "#059669", 12.dp())
            setPadding(14.dp(), 12.dp(), 14.dp(), 12.dp())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 14.dp()
            }
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        container.addView(accessibilityBannerBtn)

        val gridHeader = TextView(this).apply {
            text = "MUSE TASK DASHBOARD (SAY 'MUSE ...' OR TAP TO RUN)"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(4.dp(), 4.dp(), 4.dp(), 8.dp())
        }
        container.addView(gridHeader)

        val gridContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 14.dp()
            }
        }

        val quickTasks = listOf(
            Triple("💬 Open WhatsApp", "Say: 'Muse, Rahul ko hello bhejo'", "Open WhatsApp"),
            Triple("🚆 Book Train", "Say: 'Muse, book a train on IRCTC'", "Book a train ticket on IRCTC"),
            Triple("✈️ Book Flight", "Say: 'Muse, book a flight to Mumbai'", "Book a flight to Mumbai"),
            Triple("🚕 Book Uber Cab", "Say: 'Muse, book an Uber to Station'", "Book an Uber to Railway Station"),
            Triple("▶️ Play YouTube", "Say: 'Muse, play music on YouTube'", "Play trending music on YouTube"),
            Triple("🚌 Book RedBus", "Say: 'Muse, book a bus on RedBus'", "Book a bus on RedBus"),
            Triple("🎬 Movie Tickets", "Say: 'Muse, open BookMyShow'", "Book movie tickets on BookMyShow"),
            Triple("🔍 Google Search", "Say: 'Muse, Google search weather'", "Google search weather today")
        )

        for (i in quickTasks.indices step 2) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            for (j in 0..1) {
                if (i + j < quickTasks.size) {
                    val item = quickTasks[i + j]
                    val card = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        background = roundedBg("#111827", "#1E293B", 14.dp())
                        setPadding(14.dp(), 12.dp(), 14.dp(), 12.dp())
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                            setMargins(4.dp(), 4.dp(), 4.dp(), 4.dp())
                        }
                        setOnClickListener {
                            VoiceAgentForegroundService.instance?.handleSpokenCommand(item.third)
                        }
                    }
                    val tView = TextView(this).apply {
                        text = item.first
                        textSize = 14f
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(Color.parseColor("#38BDF8"))
                    }
                    val sView = TextView(this).apply {
                        text = item.second
                        textSize = 11f
                        setTextColor(Color.parseColor("#94A3B8"))
                        setPadding(0, 3.dp(), 0, 0)
                    }
                    card.addView(tView)
                    card.addView(sView)
                    rowLayout.addView(card)
                }
            }
            gridContainer.addView(rowLayout)
        }
        container.addView(gridContainer)

        val logCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg("#050811", "#1E293B", 14.dp())
            setPadding(14.dp(), 12.dp(), 14.dp(), 12.dp())
        }
        val logTitle = TextView(this).apply {
            text = "LIVE MUSE EXECUTION LOG"
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#10B981"))
            setPadding(0, 0, 0, 6.dp())
        }
        logView = TextView(this).apply {
            text = "• Muse initialized in Sleep Mode. Say 'Muse' or tap the face to wake."
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#CBD5E1"))
        }
        logCard.addView(logTitle)
        logCard.addView(logView)
        container.addView(logCard)

        scrollRoot.addView(container)
        setContentView(scrollRoot)

        VoiceAgentForegroundService.statusCallback = { state ->
            runOnUiThread {
                statusBadge.text = state
                refreshSystemPills()
            }
        }
        VoiceAgentForegroundService.transcriptCallback = { line ->
            runOnUiThread { transcriptView.text = line }
        }
        VoiceAgentForegroundService.logCallback = { entry ->
            runOnUiThread {
                if (logLines.size >= 8) logLines.removeAt(0)
                logLines.add("• $entry")
                logView.text = logLines.joinToString(10.toChar().toString())
            }
        }
        VoiceAgentForegroundService.faceStateCallback = { state, level ->
            runOnUiThread {
                faceView.faceMode = state
                faceView.audioLevel = level
            }
        }

        ensurePermissionsAndStart()
    }

    override fun onResume() {
        super.onResume()
        activeInstance = this
        refreshSystemPills()
        val svc = VoiceAgentForegroundService.instance
        if (svc != null && !svc.isMicMuted) {
            svc.startListeningCycle()
        }
    }

    private fun refreshSystemPills() {
        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val isMuted = VoiceAgentForegroundService.instance?.isMicMuted == true
        micStatusPill.text = when {
            !hasMic -> "🎙️ Mic: NEEDED"
            isMuted -> "🔇 Mic: MUTED"
            else -> "🎙️ Wake: 'Muse'"
        }
        micStatusPill.setTextColor(
            when {
                !hasMic -> Color.parseColor("#F59E0B")
                isMuted -> Color.parseColor("#94A3B8")
                else -> Color.parseColor("#10B981")
            }
        )

        val hasAcc = AgentAccessibilityService.instance != null
        accStatusPill.text = if (hasAcc) "📱 Screen AI: ON" else "📱 Screen AI: OFF"
        accStatusPill.setTextColor(if (hasAcc) Color.parseColor("#10B981") else Color.parseColor("#F59E0B"))
        accessibilityBannerBtn.visibility = if (hasAcc) View.GONE else View.VISIBLE
    }

    private fun roundedBg(fillHex: String, strokeHex: String, radiusPx: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(fillHex))
            setStroke(3, Color.parseColor(strokeHex))
            cornerRadius = radiusPx.toFloat()
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

        refreshSystemPills()
        ContextCompat.startForegroundService(this, Intent(this, VoiceAgentForegroundService::class.java))
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshSystemPills()
        ContextCompat.startForegroundService(this, Intent(this, VoiceAgentForegroundService::class.java))
    }
}

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
import android.widget.GridLayout
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
    private lateinit var accessibilityBannerBtn: Button

    private val logLines = ArrayDeque<String>()

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
            text = "METAWORK 24/7 AI"
            textSize = 19f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#F8FAFC"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        statusBadge = TextView(this).apply {
            text = "● LIVE 24/7"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#10B981"))
            background = roundedBg("#132F2B", "#10B981", 999.dp())
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
            layoutParams = LinearLayout.LayoutParams(170.dp(), 170.dp())
            setOnClickListener {
                VoiceAgentForegroundService.instance?.triggerImmediateListen("I am listening. Tell me your command.")
            }
        }

        val tapFaceHint = TextView(this).apply {
            text = "TAP MY FACE OR SPEAK ANYTIME (HINDI / ENGLISH)"
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#38BDF8"))
            gravity = Gravity.CENTER
            setPadding(0, 10.dp(), 0, 6.dp())
        }

        transcriptView = TextView(this).apply {
            text = "🤖 Agent: Initializing 24/7 voice brain..."
            textSize = 15f
            setTextColor(Color.parseColor("#F1F5F9"))
            gravity = Gravity.CENTER
            setPadding(8.dp(), 4.dp(), 8.dp(), 4.dp())
        }

        heroCard.addView(faceView)
        heroCard.addView(tapFaceHint)
        heroCard.addView(transcriptView)
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
            text = "⚡ TAP HERE TO ENABLE SCREEN CONTROLLER & FLOATING FACE"
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
            text = "AUTONOMOUS TASK DASHBOARD (SPEAK OR TAP TO TEST)"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(4.dp(), 4.dp(), 4.dp(), 8.dp())
        }
        container.addView(gridHeader)

        val grid = GridLayout(this).apply {
            columnCount = 2
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 14.dp()
            }
        }

        val quickTasks = listOf(
            Triple("🚆 Book Train", "Say: 'Book a train on IRCTC'", "Book a train ticket on IRCTC"),
            Triple("✈️ Book Flight", "Say: 'Book a flight to Mumbai'", "Book a flight to Mumbai"),
            Triple("🚕 Book Uber Cab", "Say: 'Book an Uber to Station'", "Book an Uber to Railway Station"),
            Triple("💬 Send WhatsApp", "Say: 'Send WhatsApp message'", "Send WhatsApp message saying Hello"),
            Triple("▶️ Play YouTube", "Say: 'Play music on YouTube'", "Play trending music on YouTube"),
            Triple("🚌 Book RedBus", "Say: 'Book a bus ticket'", "Book a bus on RedBus"),
            Triple("🎬 Movie Tickets", "Say: 'Open BookMyShow'", "Book movie tickets on BookMyShow"),
            Triple("🔍 Google Search", "Say: 'Search weather today'", "Search Google for weather today")
        )

        quickTasks.forEachIndexed { idx, (title, subtitle, command) ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = roundedBg("#111827", "#1E293B", 14.dp())
                setPadding(14.dp(), 12.dp(), 14.dp(), 12.dp())
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                    columnSpec = GridLayout.spec(idx % 2, 1f)
                    setMargins(4.dp(), 4.dp(), 4.dp(), 4.dp())
                }
                setOnClickListener {
                    VoiceAgentForegroundService.instance?.handleSpokenCommand(command)
                }
            }
            val tView = TextView(this).apply {
                text = title
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor("#38BDF8"))
            }
            val sView = TextView(this).apply {
                text = subtitle
                textSize = 11f
                setTextColor(Color.parseColor("#94A3B8"))
                setPadding(0, 3.dp(), 0, 0)
            }
            card.addView(tView)
            card.addView(sView)
            grid.addView(card)
        }
        container.addView(grid)

        val logCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg("#050811", "#1E293B", 14.dp())
            setPadding(14.dp(), 12.dp(), 14.dp(), 12.dp())
        }
        val logTitle = TextView(this).apply {
            text = "LIVE AGENT EXECUTION LOG"
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#10B981"))
            setPadding(0, 0, 0, 6.dp())
        }
        logView = TextView(this).apply {
            text = "• Agent initialized. Speak any command or tap a task card above."
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
            runOnUiThread { statusBadge.text = "● $state" }
        }
        VoiceAgentForegroundService.transcriptCallback = { line ->
            runOnUiThread { transcriptView.text = line }
        }
        VoiceAgentForegroundService.logCallback = { entry ->
            runOnUiThread {
                if (logLines.size >= 8) logLines.removeFirst()
                logLines.addLast("• $entry")
                logView.text = logLines.joinToString("\n")
            }
        }
        VoiceAgentForegroundService.faceStateCallback = { state, level ->
            runOnUiThread {
                faceView.state = state
                faceView.audioLevel = level
            }
        }

        ensurePermissionsAndStart()
    }

    override fun onResume() {
        super.onResume()
        activeInstance = this
        refreshSystemPills()
        VoiceAgentForegroundService.instance?.startContinuousOnDeviceListening()
    }

    private fun refreshSystemPills() {
        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        micStatusPill.text = if (hasMic) "🎙️ Mic: ACTIVE" else "🎙️ Mic: NEEDED"
        micStatusPill.setTextColor(if (hasMic) Color.parseColor("#10B981") else Color.parseColor("#F59E0B"))

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

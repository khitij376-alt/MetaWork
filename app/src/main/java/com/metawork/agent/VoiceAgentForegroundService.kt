package com.metawork.agent

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale

class VoiceAgentForegroundService : Service(), TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "MuseVoiceService"
        private const val CHANNEL_ID = "muse_24_7_channel"
        private const val NOTIFICATION_ID = 1001
        private const val AWAKE_WINDOW_MS = 16000L

        @Volatile
        var instance: VoiceAgentForegroundService? = null
            private set

        var statusCallback: ((String) -> Unit)? = null
        var transcriptCallback: ((String) -> Unit)? = null
        var logCallback: ((String) -> Unit)? = null
        var faceStateCallback: ((String, Float) -> Unit)? = null
    }

    private val safeHandler = CoroutineExceptionHandler { _, t ->
        Log.e(TAG, "Handled coroutine error: ${t.message}")
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + safeHandler)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var wakeLock: PowerManager.WakeLock? = null
    lateinit var taskExecutor: TaskActionExecutor
        private set
    private var gemini: GeminiVoiceSessionManager? = null
    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null

    private var isDestroyed = false
    private var isSpeaking = false
    private var isListeningActive = false

    @Volatile
    var handsFreeWakeMode: Boolean = false
        private set
    @Volatile
    var isAwakeForCommand: Boolean = false
        private set
    private var awakeUntilMs: Long = 0L
    private var ignoreSpeechUntilMs: Long = 0L
    private var lastAgentUtterance: String = ""

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (isDestroyed) return
            val now = System.currentTimeMillis()
            if (isAwakeForCommand && now > awakeUntilMs && !isSpeaking && !isListeningActive) {
                enterStandbyMode()
            }
            if (handsFreeWakeMode && !isSpeaking && !isListeningActive && now > ignoreSpeechUntilMs) {
                startListeningCycle()
            }
            mainHandler.postDelayed(this, 10000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        try {
            val chan = NotificationChannel(CHANNEL_ID, "Muse AI Universal Controller", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(chan)

            val notification = buildNotification("⚡ Muse v5.0 Ready — Tap Floating Face on Any App")
            val hasMicPerm = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && hasMicPerm) {
                try {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                } catch (_: Throwable) {
                    startForeground(NOTIFICATION_ID, notification)
                }
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Muse::AlwaysOnWakeLock")
                .apply {
                    setReferenceCounted(false)
                    acquire()
                }

            taskExecutor = TaskActionExecutor(applicationContext)
            tts = TextToSpeech(applicationContext, this)

            gemini = GeminiVoiceSessionManager(
                applicationContext,
                taskExecutor.getEffectiveGeminiKey(),
                taskExecutor
            ) { state ->
                updateStatus(state)
            }

            enterStandbyMode()
            mainHandler.postDelayed(watchdogRunnable, 10000L)
        } catch (t: Throwable) {
            Log.e(TAG, "Error in onCreate", t)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.forLanguageTag("en-IN")
            tts?.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    isSpeaking = true
                    ignoreSpeechUntilMs = System.currentTimeMillis() + 3000L
                    setFaceState("SPEAKING", 0.85f)
                }
                override fun onDone(utteranceId: String?) {
                    isSpeaking = false
                    ignoreSpeechUntilMs = System.currentTimeMillis() + 800L
                    mainHandler.postDelayed({
                        if (isAwakeForCommand) {
                            setFaceState("LISTENING", 0.4f)
                            updateStatus("🟢 MUSE LISTENING — Speak Command Now")
                            startListeningCycle()
                        } else if (handsFreeWakeMode) {
                            setFaceState("WAKE_WORD", 0.15f)
                            updateStatus("🎙️ Wake Mode ('Muse') Active")
                            startListeningCycle()
                        } else {
                            releaseMicCompletely()
                            setFaceState("STANDBY", 0.1f)
                            updateStatus("⚡ READY (Zero Mic Lag) — Tap Face to Speak")
                        }
                    }, 800L)
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    isSpeaking = false
                    if (isAwakeForCommand || handsFreeWakeMode) {
                        mainHandler.postDelayed({ startListeningCycle() }, 800L)
                    } else {
                        releaseMicCompletely()
                    }
                }
            })
        }
    }

    private fun releaseMicCompletely() {
        isListeningActive = false
        mainHandler.post {
            try {
                speechRecognizer?.cancel()
                speechRecognizer?.destroy()
                speechRecognizer = null
            } catch (_: Throwable) {}
        }
    }

    fun wakeUpMuse(greeting: String? = null) {
        AgentAccessibilityService.instance?.stopAutoScroll()
        isAwakeForCommand = true
        awakeUntilMs = System.currentTimeMillis() + AWAKE_WINDOW_MS
        setFaceState("LISTENING", 0.45f)
        updateStatus("🟢 MUSE LISTENING — Speak Command Now")
        transcriptCallback?.invoke("🎙️ Listening... Speak any command (Click, Scroll, Search, Open, WhatsApp, Torch, Alarm)")
        logCallback?.invoke("🎙️ Mic opened — Speak command")

        if (!greeting.isNullOrBlank()) {
            speakOut(greeting, keepAwakeAfter = true)
        } else {
            isSpeaking = false
            startListeningCycle()
        }
    }

    fun enterStandbyMode() {
        isAwakeForCommand = false
        awakeUntilMs = 0L
        if (handsFreeWakeMode) {
            setFaceState("WAKE_WORD", 0.15f)
            updateStatus("🎙️ Hands-Free ('Muse') Active")
            transcriptCallback?.invoke("🎙️ Hands-Free Mode: Say 'Muse' + any command.")
            startListeningCycle()
        } else {
            releaseMicCompletely()
            setFaceState("STANDBY", 0.1f)
            updateStatus("⚡ READY (Zero Mic Lag) — Tap Face to Speak")
            transcriptCallback?.invoke("⚡ Smooth Zero-Lag Mode: Tap floating Muse face on any app to Click, Scroll, or Run Tasks!")
        }
    }

    fun toggleHandsFreeWakeMode(): Boolean {
        handsFreeWakeMode = !handsFreeWakeMode
        enterStandbyMode()
        return handsFreeWakeMode
    }

    fun speakOut(message: String, keepAwakeAfter: Boolean = false) {
        lastAgentUtterance = message.lowercase(Locale.ROOT).trim()
        isSpeaking = true
        isListeningActive = false
        isAwakeForCommand = keepAwakeAfter
        if (keepAwakeAfter) {
            awakeUntilMs = System.currentTimeMillis() + AWAKE_WINDOW_MS
        } else if (!handsFreeWakeMode) {
            releaseMicCompletely()
        }
        ignoreSpeechUntilMs = System.currentTimeMillis() + 2800L

        setFaceState("SPEAKING", 0.85f)
        transcriptCallback?.invoke("🤖 Muse: $message")
        logCallback?.invoke("Muse: $message")

        val params = Bundle()
        val res = tts?.speak(message, TextToSpeech.QUEUE_FLUSH, params, "muse_${System.currentTimeMillis()}")
        if (res != TextToSpeech.SUCCESS) {
            mainHandler.postDelayed({
                isSpeaking = false
                if (keepAwakeAfter) {
                    startListeningCycle()
                } else {
                    enterStandbyMode()
                }
            }, 1400L)
        }
    }

    private fun setFaceState(state: String, level: Float) {
        mainHandler.post {
            faceStateCallback?.invoke(state, level)
            AgentAccessibilityService.instance?.updateFloatingFaceState(state)
        }
    }

    private fun initOnDeviceSpeechRecognizer() {
        try {
            speechRecognizer?.destroy()
            val ctx = MainActivity.activeInstance ?: applicationContext
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(ctx).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        isListeningActive = true
                        if (isAwakeForCommand) {
                            updateStatus("🟢 MUSE LISTENING — Speak Now")
                            setFaceState("LISTENING", 0.35f)
                        } else {
                            setFaceState("WAKE_WORD", 0.15f)
                        }
                    }
                    override fun onBeginningOfSpeech() {
                        isListeningActive = true
                        if (isAwakeForCommand) {
                            setFaceState("LISTENING", 0.7f)
                        }
                    }
                    override fun onRmsChanged(rmsdB: Float) {
                        if (isAwakeForCommand) {
                            val norm = ((rmsdB + 2f) / 12f).coerceIn(0.15f, 1.0f)
                            setFaceState("LISTENING", norm)
                        }
                    }
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {
                        isListeningActive = false
                    }
                    override fun onError(error: Int) {
                        isListeningActive = false
                        if (isDestroyed || isSpeaking) return
                        if (handsFreeWakeMode) {
                            val delayMs = if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 2500L else 1800L
                            mainHandler.postDelayed({ startListeningCycle() }, delayMs)
                        } else {
                            enterStandbyMode()
                        }
                    }
                    override fun onResults(results: Bundle?) {
                        isListeningActive = false
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val spoken = matches?.firstOrNull()?.trim().orEmpty()
                        processRecognizedSpeech(spoken)
                    }
                    override fun onPartialResults(partialResults: Bundle?) {
                        if (!isAwakeForCommand) return
                        val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        if (!partial.isNullOrBlank()) {
                            transcriptCallback?.invoke("🎙️ Hearing: '$partial...'")
                        }
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
        } catch (t: Throwable) {
            Log.e(TAG, "SpeechRecognizer init error", t)
        }
    }

    private fun processRecognizedSpeech(rawSpoken: String) {
        val now = System.currentTimeMillis()
        if (rawSpoken.isBlank() || isSpeaking || now < ignoreSpeechUntilMs) {
            enterStandbyMode()
            return
        }

        val lower = rawSpoken.lowercase(Locale.ROOT).trim()
        if (isSelfEcho(lower)) {
            enterStandbyMode()
            return
        }

        val wakeResult = extractWakeWordCommand(rawSpoken)
        if (wakeResult != null) {
            val inlineCommand = wakeResult.trim()
            if (inlineCommand.isEmpty()) {
                wakeUpMuse("Yes? Tell me your command.")
                return
            } else {
                transcriptCallback?.invoke("🗣️ You: '$rawSpoken'")
                logCallback?.invoke("You: $inlineCommand")
                handleSpokenCommand(inlineCommand)
                return
            }
        }

        if (isAwakeForCommand) {
            transcriptCallback?.invoke("🗣️ You: '$rawSpoken'")
            logCallback?.invoke("You: $rawSpoken")
            handleSpokenCommand(rawSpoken)
        } else {
            enterStandbyMode()
        }
    }

    private fun isSelfEcho(lower: String): Boolean {
        if (lastAgentUtterance.isNotBlank() && (lower == lastAgentUtterance || (lastAgentUtterance.contains(lower) && lower.length > 8))) {
            return true
        }
        val selfPrefixes = listOf(
            "hi i am muse",
            "yes tell me your command",
            "opening whatsapp",
            "sending whatsapp",
            "searching for",
            "stopped auto scrolling"
        )
        return selfPrefixes.any { lower.startsWith(it) || lower.contains(it) }
    }

    private fun extractWakeWordCommand(spoken: String): String? {
        val wakeRegex = Regex(
            "^(?:hey[ ]+|hi[ ]+|hello[ ]+|ok[ ]+|okay[ ]+|o[ ]+)?(?:muse|myooz|mews|miuz|म्यूज|म्यूज़)(?:[ ,.:!]+|$)(.*)$",
            RegexOption.IGNORE_CASE
        )
        val match = wakeRegex.find(spoken.trim())
        if (match != null) {
            return match.groupValues[1].trim()
        }
        return null
    }

    fun startListeningCycle() {
        if (isDestroyed || isSpeaking) return
        if (!isAwakeForCommand && !handsFreeWakeMode) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            updateStatus("Grant Microphone Permission to Speak")
            return
        }
        mainHandler.post {
            try {
                if (speechRecognizer == null) {
                    initOnDeviceSpeechRecognizer()
                }
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 5000L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
                }
                speechRecognizer?.startListening(intent)
                isListeningActive = true
            } catch (t: Throwable) {
                isListeningActive = false
                initOnDeviceSpeechRecognizer()
            }
        }
    }

    private fun parseWhatsAppIntent(spoken: String): Pair<String, String> {
        val clean = spoken.trim()
        val lower = clean.lowercase(Locale.ROOT)

        val strippedOpen = lower
            .replace("open", "")
            .replace("launch", "")
            .replace("kholo", "")
            .replace("whatsapp", "")
            .replace("app", "")
            .replace("please", "")
            .trim()
        if (strippedOpen.isEmpty()) {
            return Pair("", "")
        }

        val hinglishRegex = Regex(
            "(?:whatsapp[ ]+(?:open[ ]+karke|khol[ ]+ke|par|pe|mein|me)[ ]+|open[ ]+whatsapp[ ]+and[ ]+)?([a-zA-Z0-9+ ]{2,25}?)[ ]+ko[ ]+(.+?)(?:[ ]+(?:whatsapp[ ]+karo|message[ ]+karo|msg[ ]+karo|bhejo|bhej[ ]+do|send[ ]+karo|likho))?$",
            RegexOption.IGNORE_CASE
        )
        val hMatch = hinglishRegex.find(clean)
        if (hMatch != null) {
            val rec = hMatch.groupValues[1]
                .replace(Regex("^(?:whatsapp|par|pe|open|karke|and|message|send)[ ]+", RegexOption.IGNORE_CASE), "")
                .trim()
            val msg = hMatch.groupValues[2]
                .replace(Regex("^(?:whatsapp[ ]+message|message|msg|ki|saying)[ ]+", RegexOption.IGNORE_CASE), "")
                .trim()
                .ifBlank { "Hello" }
            if (rec.isNotBlank()) return Pair(rec, msg)
        }

        val englishToRegex = Regex(
            "(?:to|for)[ ]+([a-zA-Z0-9+]+(?:[ ]+[a-zA-Z0-9+]+)?)[ ]+(?:saying|that|message|msg|:)[ ]+(.+)$",
            RegexOption.IGNORE_CASE
        )
        val eMatch = englishToRegex.find(clean)
        if (eMatch != null) {
            return Pair(eMatch.groupValues[1].trim(), eMatch.groupValues[2].trim())
        }

        val msgOnWaRegex = Regex(
            "(?:message|msg|text|whatsapp)[ ]+(?:to[ ]+)?([a-zA-Z0-9+]+)[ ]+(?:on[ ]+whatsapp[ ]+)?(?:saying[ ]+|that[ ]+)?(.+)$",
            RegexOption.IGNORE_CASE
        )
        val mMatch = msgOnWaRegex.find(clean)
        if (mMatch != null) {
            val candidateRec = mMatch.groupValues[1].trim()
            if (!candidateRec.equals("whatsapp", ignoreCase = true) && !candidateRec.equals("message", ignoreCase = true)) {
                val msg = mMatch.groupValues[2]
                    .replace(Regex("^on[ ]+whatsapp[ ]*", RegexOption.IGNORE_CASE), "")
                    .trim()
                    .ifBlank { "Hello" }
                return Pair(candidateRec, msg)
            }
        }

        if (lower.contains(" to ")) {
            val afterTo = clean.substringAfter(" to ", "").trim()
            val rec = afterTo.substringBefore(" saying").substringBefore(" that").substringBefore(" ").trim()
            val msg = if (afterTo.contains("saying ", ignoreCase = true)) {
                afterTo.substringAfter("saying ", "Hello").trim()
            } else {
                afterTo.substringAfter(rec, "Hello").trim().ifBlank { "Hello" }
            }
            return Pair(rec, msg)
        }

        return Pair("", "")
    }

    private fun splitChainedCommands(spoken: String): List<String> {
        val lower = spoken.lowercase(Locale.ROOT)
        if (lower.contains("whatsapp") && (lower.contains("saying") || lower.contains(" ko ") || lower.contains("message"))) {
            return listOf(spoken.trim())
        }
        val parts = spoken.split(Regex("[ ]+(?:and then|then|aur phir|uske baad|and|aur)[ ]+", RegexOption.IGNORE_CASE))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return if (parts.isEmpty()) listOf(spoken.trim()) else parts
    }

    fun handleSpokenCommand(spoken: String) {
        isAwakeForCommand = false
        if (!handsFreeWakeMode) {
            releaseMicCompletely()
        }
        setFaceState("EXECUTING", 0.8f)
        updateStatus("⚡ EXECUTING: $spoken")

        scope.launch {
            try {
                val steps = splitChainedCommands(spoken)
                var finalSpokenFeedback = "Done."
                for ((idx, step) in steps.withIndex()) {
                    if (idx > 0) {
                        delay(1900L)
                    }
                    logCallback?.invoke("⚡ Step ${idx + 1}/${steps.size}: $step")
                    finalSpokenFeedback = executeSingleStep(step)
                }
                speakOut(finalSpokenFeedback, keepAwakeAfter = false)
            } catch (t: Throwable) {
                Log.e(TAG, "Command execution error", t)
                enterStandbyMode()
            }
        }
    }

    private fun extractNumbers(text: String): List<Int> {
        return Regex("[0-9]+").findAll(text).mapNotNull { it.value.toIntOrNull() }.toList()
    }

    private fun executeSingleStep(spoken: String): String {
        val lower = spoken.lowercase(Locale.ROOT).trim()
        return when {
            lower == "stop" || lower == "ruko" || lower.contains("stop scroll") -> {
                AgentAccessibilityService.instance?.stopAutoScroll()
                "Stopped."
            }
            // Flashlight / Torch
            lower.contains("torch") || lower.contains("flashlight") -> {
                val turnOff = lower.contains("off") || lower.contains("band")
                val res = taskExecutor.executeToolCall("set_torch", JSONObject().put("enable", !turnOff))
                if (res.optString("status") == "ok") {
                    if (turnOff) "Turned off flashlight." else "Turned on flashlight."
                } else {
                    "Could not toggle flashlight."
                }
            }
            // Alarms
            lower.contains("alarm") -> {
                val nums = extractNumbers(lower)
                var hour = nums.getOrElse(0) { 7 }
                val minute = nums.getOrElse(1) { 0 }
                if ((lower.contains("pm") || lower.contains("shaam") || lower.contains("raat")) && hour in 1..11) {
                    hour += 12
                }
                taskExecutor.executeToolCall("set_alarm", JSONObject().put("hour", hour).put("minute", minute))
                "Set alarm for $hour:${minute.toString().padStart(2, '0')}."
            }
            // Timers
            lower.contains("timer") -> {
                val nums = extractNumbers(lower)
                val amount = nums.firstOrNull() ?: 5
                val seconds = if (lower.contains("second")) amount else amount * 60
                taskExecutor.executeToolCall("set_timer", JSONObject().put("seconds", seconds))
                "Started timer for $amount ${if (lower.contains("second")) "seconds" else "minutes"}."
            }
            // Google Maps Navigation
            lower.contains("navigate to ") || lower.contains("direction to ") || lower.contains("rasta") || lower.startsWith("map ") -> {
                val dest = spoken
                    .replace(Regex("^(?:navigate[ ]+to|direction[ ]+to|directions[ ]+to|map[ ]+to|maps[ ]+to)[ ]*", RegexOption.IGNORE_CASE), "")
                    .replace("ka rasta dikhao", "")
                    .replace("ka rasta", "")
                    .trim()
                taskExecutor.executeToolCall("maps_navigate", JSONObject().put("destination", dest))
                "Starting Google Maps navigation to $dest."
            }
            // System Settings
            lower.contains("wifi") || lower.contains("wi-fi") -> {
                taskExecutor.executeToolCall("open_setting", JSONObject().put("setting", "wifi"))
                "Opened Wi-Fi settings."
            }
            lower.contains("bluetooth") -> {
                taskExecutor.executeToolCall("open_setting", JSONObject().put("setting", "bluetooth"))
                "Opened Bluetooth settings."
            }
            lower.contains("brightness") || lower.contains("display setting") -> {
                taskExecutor.executeToolCall("open_setting", JSONObject().put("setting", "display"))
                "Opened display and brightness settings."
            }
            lower.contains("battery") -> {
                taskExecutor.executeToolCall("open_setting", JSONObject().put("setting", "battery"))
                "Opened battery status."
            }
            lower.contains("location setting") || lower.contains("gps") -> {
                taskExecutor.executeToolCall("open_setting", JSONObject().put("setting", "location"))
                "Opened location settings."
            }
            // Auto-Scroll
            lower.contains("auto scroll") || lower.contains("keep scrolling") || lower.contains("reels scroll") -> {
                val dir = if (lower.contains("up")) "UP" else "DOWN"
                val res = taskExecutor.executeToolCall("auto_scroll", JSONObject().put("enable", true).put("direction", dir))
                if (res.optString("status") == "ok") "Auto scrolling started. Tap my floating face to stop." else "Please enable Muse Screen Controller in Accessibility Settings first."
            }
            // Read Screen
            lower.contains("read screen") || lower.contains("screen padho") || lower.contains("what is on screen") || lower.contains("kya likha hai") -> {
                val res = taskExecutor.executeToolCall("read_screen", JSONObject())
                res.optString("summary", "").ifBlank { "Please enable Muse Screen Controller in Accessibility Settings." }
            }
            // System Global Actions
            lower.contains("screenshot") -> {
                taskExecutor.executeToolCall("navigate_system", JSONObject().put("action", "SCREENSHOT"))
                "Took a screenshot."
            }
            lower.contains("lock screen") || lower.contains("phone lock") -> {
                taskExecutor.executeToolCall("navigate_system", JSONObject().put("action", "LOCK_SCREEN"))
                "Locked screen."
            }
            lower.contains("notification") -> {
                taskExecutor.executeToolCall("navigate_system", JSONObject().put("action", "NOTIFICATIONS"))
                "Opened notifications."
            }
            lower.contains("quick setting") || lower.contains("control center") -> {
                taskExecutor.executeToolCall("navigate_system", JSONObject().put("action", "QUICK_SETTINGS"))
                "Opened quick settings."
            }
            lower.contains("recent app") || lower.contains("recents") -> {
                taskExecutor.executeToolCall("navigate_system", JSONObject().put("action", "RECENTS"))
                "Opened recent apps."
            }
            lower.contains("volume up") || lower.contains("awaz badhao") || lower.contains("sound badhao") -> {
                taskExecutor.executeToolCall("adjust_volume", JSONObject().put("direction", "UP"))
                "Increased volume."
            }
            lower.contains("volume down") || lower.contains("awaz kam") || lower.contains("sound kam") -> {
                taskExecutor.executeToolCall("adjust_volume", JSONObject().put("direction", "DOWN"))
                "Decreased volume."
            }
            // Screen Gestures & Ordinal Clicks
            lower.contains("double tap") || lower == "like" || lower == "like karo" -> {
                val res = taskExecutor.executeToolCall("double_tap", JSONObject())
                if (res.optString("status") == "ok") "Double tapped screen." else "Enable Muse Screen Controller first."
            }
            lower.startsWith("long press") || lower.startsWith("hold ") || lower.contains("long press") -> {
                val target = spoken.replace(Regex("^(?:long[ ]+press|hold)(?:[ ]+on)?[ ]*", RegexOption.IGNORE_CASE), "").trim()
                taskExecutor.executeToolCall("long_press", JSONObject().put("label", target))
                "Long pressed $target."
            }
            lower.contains("first ") || lower.contains("1st") || lower.contains("pehla") || lower.contains("top result") || lower.contains("first video") || lower == "first" -> {
                val res = taskExecutor.executeToolCall("click_ordinal", JSONObject().put("index", 1))
                if (res.optString("status") == "ok") "Clicked first item." else "Could not click first item. Make sure Screen Controller is ON."
            }
            lower.contains("second ") || lower.contains("2nd") || lower.contains("dusra") || lower == "second" -> {
                val res = taskExecutor.executeToolCall("click_ordinal", JSONObject().put("index", 2))
                if (res.optString("status") == "ok") "Clicked second item." else "Could not click second item."
            }
            lower.contains("third ") || lower.contains("3rd") || lower.contains("teesra") || lower == "third" -> {
                val res = taskExecutor.executeToolCall("click_ordinal", JSONObject().put("index", 3))
                if (res.optString("status") == "ok") "Clicked third item." else "Could not click third item."
            }
            // Scroll Up / Down / Left / Right (Includes "scroll", "niche", "upar", "next", "swipe")
            lower == "scroll" || lower.contains("scroll down") || lower.contains("niche") || lower.contains("next reel") || lower.contains("next video") || lower.contains("next short") || lower == "next" || lower.contains("swipe up") -> {
                val res = taskExecutor.executeToolCall("scroll_screen", JSONObject().put("direction", "DOWN"))
                if (res.optString("status") == "ok") "Scrolled down." else "Please turn ON Muse Screen Controller in Accessibility Settings."
            }
            lower.contains("scroll up") || lower.contains("upar") || lower.contains("previous reel") || lower.contains("previous video") || lower.contains("swipe down") -> {
                val res = taskExecutor.executeToolCall("scroll_screen", JSONObject().put("direction", "UP"))
                if (res.optString("status") == "ok") "Scrolled up." else "Please turn ON Muse Screen Controller in Accessibility Settings."
            }
            lower.contains("swipe left") || lower.contains("left swipe") || lower.contains("scroll left") -> {
                taskExecutor.executeToolCall("scroll_screen", JSONObject().put("direction", "LEFT"))
                "Swiped left."
            }
            lower.contains("swipe right") || lower.contains("right swipe") || lower.contains("scroll right") -> {
                taskExecutor.executeToolCall("scroll_screen", JSONObject().put("direction", "RIGHT"))
                "Swiped right."
            }
            // Text Entry
            lower.startsWith("write ") || lower.startsWith("type ") || lower.contains("likho ") -> {
                val txt = spoken
                    .replace(Regex("^(write|type|likho)[ ]+", RegexOption.IGNORE_CASE), "")
                    .trim()
                val res = taskExecutor.executeToolCall("enter_text", JSONObject().put("text", txt).put("click_send_after", true))
                if (res.optString("status") == "ok") "Typed $txt." else "Tap an input box first or enable Screen Controller."
            }
            // WhatsApp
            lower.contains("whatsapp") || lower.contains("bhejo") || (lower.contains("message") && !lower.contains("sms")) -> {
                val (recipient, msg) = parseWhatsAppIntent(spoken)
                if (recipient.isBlank()) {
                    taskExecutor.executeToolCall("open_app_or_booking", JSONObject().put("category", "app").put("app_name", "whatsapp"))
                    "Opened WhatsApp."
                } else {
                    taskExecutor.executeToolCall("send_whatsapp_message", JSONObject().put("recipient", recipient).put("message", msg))
                    "Sending WhatsApp message to $recipient."
                }
            }
            // SMS & Calls & Bookings
            lower.contains("sms ") || lower.contains("text message") -> {
                val recipient = spoken.substringAfter("to ", "").substringBefore(" saying").substringBefore(" ko ").trim()
                val msg = spoken.substringAfter("saying ", "Hello").trim()
                taskExecutor.executeToolCall("send_sms", JSONObject().put("recipient", recipient).put("message", msg))
                "Sent SMS to $recipient."
            }
            lower.contains("train") || lower.contains("irctc") || lower.contains("rail") -> {
                taskExecutor.executeToolCall("open_app_or_booking", JSONObject().put("category", "train"))
                "Opening IRCTC train booking."
            }
            lower.contains("flight") || lower.contains("makemytrip") || lower.contains("plane") -> {
                val dest = lower.substringAfter("to ", "Mumbai").substringBefore(" ").trim()
                taskExecutor.executeToolCall("open_app_or_booking", JSONObject().put("category", "flight").put("destination", dest))
                "Opening flight search to $dest."
            }
            lower.contains("uber") || lower.contains("cab") || lower.contains("taxi") || lower.contains("ola") -> {
                val dest = spoken.substringAfter("to ", "Railway Station").trim()
                taskExecutor.executeToolCall("open_app_or_booking", JSONObject().put("category", "cab").put("destination", dest))
                "Booking Uber ride to $dest."
            }
            lower.contains("bus") || lower.contains("redbus") -> {
                taskExecutor.executeToolCall("open_app_or_booking", JSONObject().put("category", "bus"))
                "Opening RedBus."
            }
            lower.contains("movie") || lower.contains("bookmyshow") || lower.contains("cinema") -> {
                taskExecutor.executeToolCall("open_app_or_booking", JSONObject().put("category", "movie"))
                "Opening BookMyShow."
            }
            lower.contains("call ") || lower.contains("dial ") || lower.contains("phone lagao") -> {
                val target = lower.substringAfter("call ", "").substringAfter("dial ", "").replace("ko", "").trim()
                val res = taskExecutor.executeToolCall("make_phone_call", JSONObject().put("recipient", target))
                if (res.optString("status") == "ok") "Calling $target." else "Could not find contact $target."
            }
            lower.contains("youtube") || lower.startsWith("play ") || lower.contains(" play ") -> {
                val query = lower
                    .replace("open youtube", "")
                    .replace("play", "")
                    .replace("on youtube", "")
                    .replace("youtube", "")
                    .trim()
                taskExecutor.executeToolCall("open_app_or_booking", JSONObject().put("category", "youtube").put("destination", query))
                if (query.isNotEmpty()) {
                    mainHandler.postDelayed({
                        AgentAccessibilityService.instance?.clickByOrdinal(1)
                    }, 3200L)
                    "Playing $query on YouTube."
                } else {
                    "Opening YouTube."
                }
            }
            lower.contains("google search") || lower.contains("search on google") || lower.contains("search google") -> {
                val query = lower
                    .replace("google search", "")
                    .replace("search on google", "")
                    .replace("search google", "")
                    .replace("for ", "")
                    .trim()
                    .ifBlank { spoken }
                taskExecutor.executeToolCall("open_app_or_booking", JSONObject().put("category", "search").put("destination", query))
                "Searching Google for $query."
            }
            lower.startsWith("search ") || lower.endsWith(" search karo") || lower.contains("search for ") -> {
                val query = spoken
                    .replace(Regex("^(?:search[ ]+for|search)[ ]+", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("[ ]+search[ ]+karo$", RegexOption.IGNORE_CASE), "")
                    .trim()
                val res = taskExecutor.executeToolCall("in_app_search", JSONObject().put("query", query))
                if (res.optString("status") == "ok") "Searched for $query on screen." else "Could not find a search box on this screen."
            }
            lower.contains("open ") || lower.contains("launch ") || lower.contains("kholo") -> {
                val appName = lower
                    .replace("open ", "")
                    .replace("launch ", "")
                    .replace("kholo", "")
                    .replace("app", "")
                    .replace("please", "")
                    .trim()
                val res = taskExecutor.executeToolCall("open_app_or_booking", JSONObject().put("category", "app").put("app_name", appName))
                if (res.optString("status") == "ok") {
                    "Opened $appName."
                } else {
                    // Maybe the user said "Open Settings" or "Open Shorts" which is a button on screen!
                    val clicked = AgentAccessibilityService.instance?.clickElementByLabel(appName) ?: false
                    if (clicked) "Opened $appName on screen." else "Could not find app or button $appName."
                }
            }
            lower.contains("click ") || lower.contains("tap ") || lower.contains("dabao") || lower.contains("select ") || lower.contains("press ") -> {
                val label = spoken
                    .replace(Regex("^(?:click|tap|select|press)(?:[ ]+on)?[ ]+", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("[ ]+(?:par[ ]+click[ ]+karo|click[ ]+karo|dabao)$", RegexOption.IGNORE_CASE), "")
                    .trim()
                val res = taskExecutor.executeToolCall("click_element", JSONObject().put("label", label))
                if (res.optString("status") == "ok") "Clicked $label." else "Could not find '$label' on screen."
            }
            lower.contains("back") || lower.contains("piche") -> {
                taskExecutor.executeToolCall("navigate_system", JSONObject().put("action", "BACK"))
                "Went back."
            }
            lower.contains("home") -> {
                taskExecutor.executeToolCall("navigate_system", JSONObject().put("action", "HOME"))
                "Went to home screen."
            }
            else -> {
                // 1. Try clicking any visible element matching the spoken text directly on screen
                val clickedDirectly = AgentAccessibilityService.instance?.clickElementByLabel(spoken.trim()) ?: false
                if (clickedDirectly) {
                    "Clicked $spoken."
                } else {
                    // 2. Run Multi-Step Gemini Autonomous Screen Agent Loop!
                    val aiSpoken = taskExecutor.runGeminiAutonomousScreenPlan(spoken)
                    aiSpoken ?: "Done checking screen for '$spoken'."
                }
            }
        }
    }

    private fun updateStatus(state: String) {
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(state))
            statusCallback?.invoke(state)
        } catch (_: Throwable) {}
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Muse AI v5.0 Universal Agent")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        isDestroyed = true
        instance = null
        mainHandler.removeCallbacksAndMessages(null)
        releaseMicCompletely()
        try { tts?.shutdown() } catch (_: Throwable) {}
        try { gemini?.stopSession() } catch (_: Throwable) {}
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Throwable) {}
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

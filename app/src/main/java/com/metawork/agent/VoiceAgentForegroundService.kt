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

    // Default: Zero-Lag On-Demand Mode (handsFreeWakeMode = false) so mic NEVER causes laggy sound in background!
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
            val chan = NotificationChannel(CHANNEL_ID, "Muse AI Autonomous Controller", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(chan)

            val notification = buildNotification("⚡ Muse Ready (Zero Mic Lag) — Tap Floating Face to Speak")
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
                BuildConfig.GEMINI_API_KEY,
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
            // Use USAGE_MEDIA so Android never switches speaker into low-quality voice call mode
            tts?.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    isSpeaking = true
                    ignoreSpeechUntilMs = System.currentTimeMillis() + 3500L
                    setFaceState("SPEAKING", 0.85f)
                }
                override fun onDone(utteranceId: String?) {
                    isSpeaking = false
                    ignoreSpeechUntilMs = System.currentTimeMillis() + 900L
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
                    }, 900L)
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    isSpeaking = false
                    if (isAwakeForCommand || handsFreeWakeMode) {
                        mainHandler.postDelayed({ startListeningCycle() }, 900L)
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

    /**
     * Wakes Muse up immediately when user taps the floating Muse face or Wake button.
     * Starts listening right away without lag!
     */
    fun wakeUpMuse(greeting: String? = null) {
        AgentAccessibilityService.instance?.stopAutoScroll()
        isAwakeForCommand = true
        awakeUntilMs = System.currentTimeMillis() + AWAKE_WINDOW_MS
        setFaceState("LISTENING", 0.45f)
        updateStatus("🟢 MUSE LISTENING — Speak Command Now")
        transcriptCallback?.invoke("🎙️ Listening... Speak your command now (Hindi / English)")
        logCallback?.invoke("🎙️ Mic opened for command")

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
            transcriptCallback?.invoke("🎙️ Hands-Free Mode: Say 'Muse' + command.")
            startListeningCycle()
        } else {
            releaseMicCompletely()
            setFaceState("STANDBY", 0.1f)
            updateStatus("⚡ READY (Zero Mic Lag) — Tap Face to Speak")
            transcriptCallback?.invoke("⚡ Zero-Lag Mode: Mic is OFF (no lag sound). Tap my face on any screen to speak!")
        }
    }

    /**
     * Toggles between Zero-Lag Tap-to-Speak Mode (default, mic off when idle) and Hands-Free 'Muse' Wake-Word Mode.
     */
    fun toggleHandsFreeWakeMode(): Boolean {
        handsFreeWakeMode = !handsFreeWakeMode
        if (handsFreeWakeMode) {
            logCallback?.invoke("🎙️ Hands-Free 'Muse' Wake-Word enabled")
            enterStandbyMode()
        } else {
            logCallback?.invoke("⚡ Zero-Lag Tap-to-Speak Mode enabled (Mic OFF when idle)")
            enterStandbyMode()
        }
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
        ignoreSpeechUntilMs = System.currentTimeMillis() + 3000L

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
            }, 1600L)
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
                            // In Zero-Lag mode, do NOT loop-restart the mic on silence timeout!
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

        // Check if user prefixed with "Muse"
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
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 6000L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 5000L)
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

    /**
     * Splits multi-step voice commands like "Open YouTube and search Arijit Singh and click first item"
     * (except when it is a single WhatsApp message command).
     */
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

    private fun executeSingleStep(spoken: String): String {
        val lower = spoken.lowercase(Locale.ROOT).trim()
        return when {
            lower == "stop" || lower == "ruko" || lower.contains("stop scroll") -> {
                AgentAccessibilityService.instance?.stopAutoScroll()
                "Stopped."
            }
            lower.contains("auto scroll") || lower.contains("keep scrolling") || lower.contains("reels scroll") -> {
                val dir = if (lower.contains("up")) "UP" else "DOWN"
                taskExecutor.executeToolCall("auto_scroll", JSONObject().put("enable", true).put("direction", dir))
                "Started auto scrolling. Tap my floating face anytime to stop."
            }
            lower.contains("read screen") || lower.contains("screen padho") || lower.contains("what is on screen") || lower.contains("kya likha hai") -> {
                val res = taskExecutor.executeToolCall("read_screen", JSONObject())
                val summary = res.optString("summary", "")
                summary.ifBlank { "Please enable Muse Screen Controller in Accessibility Settings first." }
            }
            lower.contains("screenshot") -> {
                taskExecutor.executeToolCall("navigate_system", JSONObject().put("action", "SCREENSHOT"))
                "Took a screenshot."
            }
            lower.contains("lock screen") || lower.contains("phone lock") -> {
                taskExecutor.executeToolCall("navigate_system", JSONObject().put("action", "LOCK_SCREEN"))
                "Locked the screen."
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
            lower.contains("double tap") || lower == "like" || lower == "like karo" -> {
                taskExecutor.executeToolCall("double_tap", JSONObject())
                "Double tapped screen."
            }
            lower.startsWith("long press") || lower.startsWith("hold ") || lower.contains("long press") -> {
                val target = spoken.replace(Regex("^(?:long[ ]+press|hold)(?:[ ]+on)?[ ]*", RegexOption.IGNORE_CASE), "").trim()
                taskExecutor.executeToolCall("long_press", JSONObject().put("label", target))
                "Long pressed $target."
            }
            lower.contains("first ") || lower.contains("1st") || lower.contains("pehla") || lower.contains("top result") || lower.contains("first video") -> {
                taskExecutor.executeToolCall("click_ordinal", JSONObject().put("index", 1))
                "Clicked the first item on screen."
            }
            lower.contains("second ") || lower.contains("2nd") || lower.contains("dusra") -> {
                taskExecutor.executeToolCall("click_ordinal", JSONObject().put("index", 2))
                "Clicked the second item on screen."
            }
            lower.contains("third ") || lower.contains("3rd") || lower.contains("teesra") -> {
                taskExecutor.executeToolCall("click_ordinal", JSONObject().put("index", 3))
                "Clicked the third item on screen."
            }
            lower.startsWith("write ") || lower.startsWith("type ") || lower.contains("likho ") -> {
                val txt = spoken
                    .replace(Regex("^(write|type|likho)[ ]+", RegexOption.IGNORE_CASE), "")
                    .trim()
                val res = taskExecutor.executeToolCall("enter_text", JSONObject().put("text", txt).put("click_send_after", true))
                if (res.optString("status") == "ok") "Typed $txt." else "Please tap an input field or enable Muse Screen Controller."
            }
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
            lower.contains("sms ") || lower.contains("text message") -> {
                val recipient = spoken.substringAfter("to ", "").substringBefore(" saying").substringBefore(" ko ").trim()
                val msg = spoken.substringAfter("saying ", "Hello").trim()
                taskExecutor.executeToolCall("send_sms", JSONObject().put("recipient", recipient).put("message", msg))
                "Sent SMS to $recipient."
            }
            lower.contains("train") || lower.contains("irctc") || lower.contains("rail") -> {
                taskExecutor.executeToolCall("open_app_or_booking", JSONObject().put("category", "train"))
                "Opening IRCTC train ticket booking."
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
                "Opening RedBus ticket booking."
            }
            lower.contains("movie") || lower.contains("bookmyshow") || lower.contains("cinema") -> {
                taskExecutor.executeToolCall("open_app_or_booking", JSONObject().put("category", "movie"))
                "Opening BookMyShow movie tickets."
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
                if (query.isNotEmpty()) "Playing $query on YouTube." else "Opening YouTube."
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
            // Universal In-App Search ("search iPhone 16", "Arijit Singh search karo") inside whatever app is currently open!
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
                if (res.optString("status") == "ok") "Opened $appName." else "Could not find installed app $appName."
            }
            lower.contains("scroll down") || lower.contains("niche") || lower.contains("next reel") || lower.contains("next video") || lower.contains("next short") || lower == "next" -> {
                taskExecutor.executeToolCall("scroll_screen", JSONObject().put("direction", "DOWN"))
                "Scrolled down."
            }
            lower.contains("scroll up") || lower.contains("upar") || lower.contains("previous reel") || lower.contains("previous video") -> {
                taskExecutor.executeToolCall("scroll_screen", JSONObject().put("direction", "UP"))
                "Scrolled up."
            }
            lower.contains("swipe left") || lower.contains("left swipe") -> {
                taskExecutor.executeToolCall("scroll_screen", JSONObject().put("direction", "LEFT"))
                "Swiped left."
            }
            lower.contains("swipe right") || lower.contains("right swipe") -> {
                taskExecutor.executeToolCall("scroll_screen", JSONObject().put("direction", "RIGHT"))
                "Swiped right."
            }
            lower.contains("click ") || lower.contains("tap ") || lower.contains("dabao") || lower.contains("select ") -> {
                val label = spoken
                    .replace(Regex("^(?:click|tap|select)(?:[ ]+on)?[ ]+", RegexOption.IGNORE_CASE), "")
                    .replace("dabao", "")
                    .trim()
                val res = taskExecutor.executeToolCall("click_element", JSONObject().put("label", label))
                if (res.optString("status") == "ok") "Clicked $label." else "Could not find $label on screen."
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
                // 1. Try clicking any visible element matching the spoken text directly
                val clickedDirectly = AgentAccessibilityService.instance?.clickElementByLabel(spoken.trim()) ?: false
                if (clickedDirectly) {
                    "Clicked $spoken."
                } else {
                    // 2. If Gemini API key is configured, run AI UI Tree Planner on current screen
                    val aiSpoken = taskExecutor.runGeminiAutonomousScreenPlan(spoken, BuildConfig.GEMINI_API_KEY)
                    aiSpoken ?: "I could not find '$spoken' on this screen. Try saying Click, Scroll, Search, Type, or Open App."
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
            .setContentTitle("Muse AI Screen Controller")
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

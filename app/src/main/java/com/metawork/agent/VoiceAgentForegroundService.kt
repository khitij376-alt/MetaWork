package com.metawork.agent

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
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

    // Muse Wake-Word & Sleep/Mute State Machine
    @Volatile
    var isMicMuted: Boolean = false
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
            if (isAwakeForCommand && now > awakeUntilMs && !isSpeaking) {
                enterSleepStandby()
            }
            if (!isMicMuted && !isSpeaking && !isListeningActive && now > ignoreSpeechUntilMs) {
                startListeningCycle()
            }
            mainHandler.postDelayed(this, 8000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        try {
            val chan = NotificationChannel(CHANNEL_ID, "Muse AI Voice Agent", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(chan)

            val notification = buildNotification("🌙 Muse is Sleeping — Say 'Muse' to Wake")
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

            initOnDeviceSpeechRecognizer()
            enterSleepStandby()
            mainHandler.postDelayed(watchdogRunnable, 8000L)
        } catch (t: Throwable) {
            Log.e(TAG, "Error in onCreate", t)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("en", "IN")
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    isSpeaking = true
                    ignoreSpeechUntilMs = System.currentTimeMillis() + 4000L
                    setFaceState("SPEAKING", 0.85f)
                }
                override fun onDone(utteranceId: String?) {
                    isSpeaking = false
                    ignoreSpeechUntilMs = System.currentTimeMillis() + 1200L
                    mainHandler.postDelayed({
                        if (isMicMuted) {
                            setFaceState("MUTED", 0.05f)
                            updateStatus("🔇 MUTED — Tap Muse Face to Speak")
                        } else if (isAwakeForCommand) {
                            setFaceState("LISTENING", 0.35f)
                            updateStatus("🟢 MUSE AWAKE — Speak Command")
                            startListeningCycle()
                        } else {
                            setFaceState("SLEEP", 0.1f)
                            updateStatus("🌙 SLEEPING — Say 'Muse' to Wake")
                            startListeningCycle()
                        }
                    }, 1200L)
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    isSpeaking = false
                    ignoreSpeechUntilMs = System.currentTimeMillis() + 1000L
                    mainHandler.postDelayed({
                        if (!isMicMuted) startListeningCycle()
                    }, 1000L)
                }
            })
            speakOut("Hi, I am Muse. Say Muse anytime to wake me up.", keepAwakeAfter = false)
        } else {
            startListeningCycle()
        }
    }

    /**
     * Wakes Muse up immediately (from floating face tap, dashboard button, or wake word "Muse").
     */
    fun wakeUpMuse(greeting: String? = "Yes? Tell me your command.") {
        isMicMuted = false
        isAwakeForCommand = true
        awakeUntilMs = System.currentTimeMillis() + AWAKE_WINDOW_MS
        setFaceState("LISTENING", 0.4f)
        updateStatus("🟢 MUSE AWAKE — Speak Command")
        transcriptCallback?.invoke("🟢 Muse Awake: Speak your command now...")
        if (!greeting.isNullOrBlank()) {
            speakOut(greeting, keepAwakeAfter = true)
        } else {
            isSpeaking = false
            startListeningCycle()
        }
    }

    /**
     * Puts Muse into Sleep/Standby mode where background speech is ignored until "Muse" is spoken.
     */
    fun enterSleepStandby() {
        isAwakeForCommand = false
        awakeUntilMs = 0L
        if (isMicMuted) {
            setFaceState("MUTED", 0.05f)
            updateStatus("🔇 MUTED — Tap Muse Face to Speak")
            transcriptCallback?.invoke("🔇 Mic is Muted. Tap Muse's face anytime to give a command.")
        } else {
            setFaceState("SLEEP", 0.1f)
            updateStatus("🌙 SLEEPING — Say 'Muse' to Wake")
            transcriptCallback?.invoke("🌙 Muse is Sleeping. Say "Muse" or tap my face to wake me.")
        }
    }

    /**
     * Completely mutes/unmutes the microphone (when muted, SpeechRecognizer is stopped & green mic dot turns off).
     */
    fun toggleFullMicMute(): Boolean {
        isMicMuted = !isMicMuted
        if (isMicMuted) {
            isAwakeForCommand = false
            isListeningActive = false
            mainHandler.post {
                try {
                    speechRecognizer?.cancel()
                    speechRecognizer?.destroy()
                    speechRecognizer = null
                } catch (_: Throwable) {}
            }
            setFaceState("MUTED", 0.05f)
            updateStatus("🔇 MUTED — Tap Muse Face to Speak")
            transcriptCallback?.invoke("🔇 Mic Muted (Green mic dot OFF). Tap Muse face to speak.")
            logCallback?.invoke("🔇 Microphone muted (Tap-Only mode enabled)")
        } else {
            enterSleepStandby()
            logCallback?.invoke("🌙 Wake-word standby enabled (Say 'Muse')")
            startListeningCycle()
        }
        return isMicMuted
    }

    fun speakOut(message: String, keepAwakeAfter: Boolean = false) {
        lastAgentUtterance = message.lowercase(Locale.ROOT).trim()
        isSpeaking = true
        isListeningActive = false
        isAwakeForCommand = keepAwakeAfter
        if (keepAwakeAfter) {
            awakeUntilMs = System.currentTimeMillis() + AWAKE_WINDOW_MS
        }
        ignoreSpeechUntilMs = System.currentTimeMillis() + 3500L

        mainHandler.post {
            try { speechRecognizer?.cancel() } catch (_: Throwable) {}
        }
        setFaceState("SPEAKING", 0.85f)
        transcriptCallback?.invoke("🤖 Muse: $message")
        logCallback?.invoke("Muse: $message")

        val params = Bundle()
        val res = tts?.speak(message, TextToSpeech.QUEUE_FLUSH, params, "muse_${System.currentTimeMillis()}")
        if (res != TextToSpeech.SUCCESS) {
            mainHandler.postDelayed({
                isSpeaking = false
                ignoreSpeechUntilMs = System.currentTimeMillis() + 800L
                if (keepAwakeAfter) {
                    setFaceState("LISTENING", 0.3f)
                    updateStatus("🟢 MUSE AWAKE — Speak Command")
                } else {
                    enterSleepStandby()
                }
                if (!isMicMuted) startListeningCycle()
            }, 2000L)
        }
    }

    private fun setFaceState(state: String, level: Float) {
        mainHandler.post {
            faceStateCallback?.invoke(state, level)
            AgentAccessibilityService.instance?.updateFloatingFaceState(state)
        }
    }

    private fun initOnDeviceSpeechRecognizer() {
        mainHandler.post {
            try {
                speechRecognizer?.destroy()
                val ctx = MainActivity.activeInstance ?: applicationContext
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(ctx).apply {
                    setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {
                            isListeningActive = true
                            if (isAwakeForCommand) {
                                updateStatus("🟢 MUSE AWAKE — Speak Command")
                                setFaceState("LISTENING", 0.3f)
                            } else {
                                updateStatus("🌙 SLEEPING — Say 'Muse' to Wake")
                                setFaceState("SLEEP", 0.1f)
                            }
                        }
                        override fun onBeginningOfSpeech() {
                            isListeningActive = true
                            if (isAwakeForCommand) {
                                setFaceState("LISTENING", 0.65f)
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
                            if (!isDestroyed && !isSpeaking && !isMicMuted) {
                                val delayMs = if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1200L else 650L
                                mainHandler.postDelayed({ startListeningCycle() }, delayMs)
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
                                transcriptCallback?.invoke("🎙️ Hearing: "$partial..."")
                            }
                        }
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                }
            } catch (t: Throwable) {
                Log.e(TAG, "SpeechRecognizer init error", t)
            }
        }
    }

    private fun processRecognizedSpeech(rawSpoken: String) {
        val now = System.currentTimeMillis()
        if (rawSpoken.isBlank() || isSpeaking || now < ignoreSpeechUntilMs) {
            if (!isMicMuted) startListeningCycle()
            return
        }

        val lower = rawSpoken.lowercase(Locale.ROOT).trim()

        // Guard against TTS speaker echo so Muse NEVER loops on its own voice
        if (isSelfEcho(lower)) {
            if (!isMicMuted) startListeningCycle()
            return
        }

        // Check if user said "Muse" wake word
        val wakeResult = extractWakeWordCommand(rawSpoken)
        if (wakeResult != null) {
            val inlineCommand = wakeResult.trim()
            if (inlineCommand.isEmpty()) {
                // User only said "Muse" / "Hey Muse" -> Wake up and listen for command
                logCallback?.invoke("✨ Wake word 'Muse' detected!")
                wakeUpMuse("Yes? Tell me your command.")
                return
            } else {
                // User said "Muse <command>" in one sentence -> Run command & go back to sleep
                transcriptCallback?.invoke("🗣️ You: "$rawSpoken"")
                logCallback?.invoke("You (Wake+Cmd): $inlineCommand")
                handleSpokenCommand(inlineCommand)
                return
            }
        }

        // If Muse is currently AWAKE (within 16s window after saying "Muse" or tapping face)
        if (isAwakeForCommand && now <= awakeUntilMs) {
            transcriptCallback?.invoke("🗣️ You: "$rawSpoken"")
            logCallback?.invoke("You said: $rawSpoken")
            handleSpokenCommand(rawSpoken)
        } else {
            // Muse is SLEEPING -> Completely ignore background conversations!
            isAwakeForCommand = false
            setFaceState("SLEEP", 0.1f)
            if (!isMicMuted) startListeningCycle()
        }
    }

    private fun isSelfEcho(lower: String): Boolean {
        if (lastAgentUtterance.isNotBlank() && (lower == lastAgentUtterance || lastAgentUtterance.contains(lower) && lower.length > 8)) {
            return true
        }
        val selfPrefixes = listOf(
            "hi i am muse",
            "yes tell me your command",
            "opening whatsapp",
            "sending whatsapp",
            "opening irctc",
            "opening youtube",
            "searching google for",
            "booked uber",
            "i could not find",
            "muse is sleeping"
        )
        return selfPrefixes.any { lower.startsWith(it) || lower.contains(it) }
    }

    /**
     * Checks if the utterance starts with or contains the wake word "Muse" (or common STT phonetic variants).
     * Returns the remaining command text after "Muse" (empty string if user only said "Muse"), or null if wake word not spoken.
     */
    private fun extractWakeWordCommand(spoken: String): String? {
        val wakeRegex = Regex(
            "^(?:hey\s+|hi\s+|hello\s+|ok\s+|okay\s+|o\s+)?(?:muse|myooz|mews|miuz|म्यूज|म्यूज़)(?:\s+|[,.:!]+|$)(.*)$",
            RegexOption.IGNORE_CASE
        )
        val match = wakeRegex.find(spoken.trim())
        if (match != null) {
            return match.groupValues[1].trim()
        }
        // Also handle if STT transcribed "Hey Muse" as "hey news" or "hey music"
        val phoneticRegex = Regex(
            "^(?:hey|hi|hello|ok)\s+(?:news|music)(?:\s+|[,.:!]+|$)(.*)$",
            RegexOption.IGNORE_CASE
        )
        val phoneticMatch = phoneticRegex.find(spoken.trim())
        if (phoneticMatch != null) {
            return phoneticMatch.groupValues[1].trim()
        }
        return null
    }

    fun startListeningCycle() {
        if (isDestroyed || isSpeaking || isMicMuted) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            updateStatus("Grant Microphone Permission to Speak")
            return
        }
        mainHandler.post {
            try {
                if (speechRecognizer == null) {
                    initOnDeviceSpeechRecognizer()
                }
                try { speechRecognizer?.cancel() } catch (_: Throwable) {}
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                }
                speechRecognizer?.startListening(intent)
                isListeningActive = true
            } catch (t: Throwable) {
                isListeningActive = false
                initOnDeviceSpeechRecognizer()
            }
        }
    }

    /**
     * Parses English + Hinglish WhatsApp commands into Pair(recipient, message).
     */
    private fun parseWhatsAppIntent(spoken: String): Pair<String, String> {
        val clean = spoken.trim()
        val lower = clean.lowercase(Locale.ROOT)

        // 1. Check if user ONLY asked to open WhatsApp ("open whatsapp", "whatsapp kholo")
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

        // 2. Hinglish pattern: "[whatsapp open karke / whatsapp par] <recipient> ko <message> [message karo / bhejo / send karo]"
        val hinglishRegex = Regex(
            "(?:whatsapp\s+(?:open\s+karke|khol\s+ke|par|pe|mein|me)\s+|open\s+whatsapp\s+and\s+)?([a-zA-Z0-9+\s]{2,25}?)\s+ko\s+(.+?)(?:\s+(?:whatsapp\s+karo|message\s+karo|msg\s+karo|bhejo|bhej\s+do|send\s+karo|likho))?$",
            RegexOption.IGNORE_CASE
        )
        val hMatch = hinglishRegex.find(clean)
        if (hMatch != null) {
            val rec = hMatch.groupValues[1]
                .replace(Regex("^(?:whatsapp|par|pe|open|karke|and|message|send)\s+", RegexOption.IGNORE_CASE), "")
                .trim()
            val msg = hMatch.groupValues[2]
                .replace(Regex("^(?:whatsapp\s+message|message|msg|ki|saying)\s+", RegexOption.IGNORE_CASE), "")
                .trim()
                .ifBlank { "Hello" }
            if (rec.isNotBlank()) return Pair(rec, msg)
        }

        // 3. English pattern: "send whatsapp [message] to <recipient> [saying/that] <message>"
        val englishToRegex = Regex(
            "(?:to|for)\s+([a-zA-Z0-9+]+(?:\s+[a-zA-Z0-9+]+)?)\s+(?:saying|that|message|msg|:)\s+(.+)$",
            RegexOption.IGNORE_CASE
        )
        val eMatch = englishToRegex.find(clean)
        if (eMatch != null) {
            return Pair(eMatch.groupValues[1].trim(), eMatch.groupValues[2].trim())
        }

        // 4. English pattern: "message <recipient> on whatsapp <message>" or "whatsapp <recipient> <message>"
        val msgOnWaRegex = Regex(
            "(?:message|msg|text|whatsapp)\s+(?:to\s+)?([a-zA-Z0-9+]+)\s+(?:on\s+whatsapp\s+)?(?:saying\s+|that\s+)?(.+)$",
            RegexOption.IGNORE_CASE
        )
        val mMatch = msgOnWaRegex.find(clean)
        if (mMatch != null) {
            val candidateRec = mMatch.groupValues[1].trim()
            if (!candidateRec.equals("whatsapp", ignoreCase = true) && !candidateRec.equals("message", ignoreCase = true)) {
                val msg = mMatch.groupValues[2]
                    .replace(Regex("^on\s+whatsapp\s*", RegexOption.IGNORE_CASE), "")
                    .trim()
                    .ifBlank { "Hello" }
                return Pair(candidateRec, msg)
            }
        }

        // 5. Fallback extraction if "to <name>" is present
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

    fun handleSpokenCommand(spoken: String) {
        val lower = spoken.lowercase(Locale.ROOT).trim()
        // Immediately reset awake state so after this command completes, Muse returns to Sleep Mode!
        isAwakeForCommand = false
        setFaceState("EXECUTING", 0.75f)
        updateStatus("⚡ EXECUTING: $spoken")

        scope.launch {
            try {
                when {
                    lower == "sleep" || lower == "go to sleep" || lower == "so jao" || lower.contains("sleep mode") -> {
                        logCallback?.invoke("🌙 Muse entering Sleep Mode")
                        speakOut("Going to sleep. Say Muse whenever you need me.", keepAwakeAfter = false)
                    }
                    lower == "mute" || lower.contains("mute mic") || lower.contains("chup") -> {
                        mainHandler.post { toggleFullMicMute() }
                    }
                    lower.startsWith("write ") || lower.startsWith("type ") || lower.contains("likho ") -> {
                        val txt = spoken
                            .replace(Regex("^(write|type|likho)\s+", RegexOption.IGNORE_CASE), "")
                            .trim()
                        logCallback?.invoke("⚡ Action: Typing text on screen: '$txt'")
                        val res = taskExecutor.executeToolCall("enter_text", JSONObject().put("text", txt).put("click_send_after", true))
                        if (res.optString("status") == "ok") {
                            speakOut("Typed $txt.", keepAwakeAfter = false)
                        } else {
                            speakOut("Please tap an input box or enable Muse Screen Controller first.", keepAwakeAfter = false)
                        }
                    }
                    lower.contains("whatsapp") || lower.contains("bhejo") || (lower.contains("message") && !lower.contains("sms")) -> {
                        val (recipient, msg) = parseWhatsAppIntent(spoken)
                        if (recipient.isBlank()) {
                            logCallback?.invoke("⚡ Action: Opening WhatsApp")
                            taskExecutor.executeToolCall("open_app_or_booking", JSONObject().put("category", "app").put("app_name", "whatsapp"))
                            speakOut("Opened WhatsApp.", keepAwakeAfter = false)
                        } else {
                            logCallback?.invoke("⚡ Action: WhatsApp to '$recipient' -> '$msg'")
                            taskExecutor.executeToolCall(
                                "send_whatsapp_message",
                                JSONObject().put("recipient", recipient).put("message", msg)
                            )
                            speakOut("Sending WhatsApp message to $recipient.", keepAwakeAfter = false)
                        }
                    }
                    lower.contains("sms ") || lower.contains("text message") -> {
                        val recipient = spoken.substringAfter("to ", "").substringBefore(" saying").substringBefore(" ko ").trim()
                        val msg = spoken.substringAfter("saying ", "Hello").trim()
                        logCallback?.invoke("⚡ Action: Sending SMS to $recipient: $msg")
                        taskExecutor.executeToolCall("send_sms", JSONObject().put("recipient", recipient).put("message", msg))
                        speakOut("Sent SMS to $recipient.", keepAwakeAfter = false)
                    }
                    lower.contains("train") || lower.contains("irctc") || lower.contains("rail") -> {
                        logCallback?.invoke("⚡ Action: Launching IRCTC Train Booking")
                        taskExecutor.executeToolCall("open_app_or_booking", JSONObject().put("category", "train"))
                        speakOut("Opening IRCTC train ticket booking.", keepAwakeAfter = false)
                    }
                    lower.contains("flight") || lower.contains("makemytrip") || lower.contains("plane") -> {
                        val dest = lower.substringAfter("to ", "Mumbai").substringBefore(" ").trim()
                        logCallback?.invoke("⚡ Action: Launching Flight Search to $dest")
                        taskExecutor.executeToolCall("open_app_or_booking", JSONObject().put("category", "flight").put("destination", dest))
                        speakOut("Opening flight search to $dest.", keepAwakeAfter = false)
                    }
                    lower.contains("uber") || lower.contains("cab") || lower.contains("taxi") || lower.contains("ola") -> {
                        val dest = spoken.substringAfter("to ", "Railway Station").trim()
                        logCallback?.invoke("⚡ Action: Launching Uber Cab to $dest")
                        taskExecutor.executeToolCall("open_app_or_booking", JSONObject().put("category", "cab").put("destination", dest))
                        speakOut("Booking Uber ride to $dest.", keepAwakeAfter = false)
                    }
                    lower.contains("bus") || lower.contains("redbus") -> {
                        logCallback?.invoke("⚡ Action: Launching RedBus")
                        taskExecutor.executeToolCall("open_app_or_booking", JSONObject().put("category", "bus"))
                        speakOut("Opening RedBus ticket booking.", keepAwakeAfter = false)
                    }
                    lower.contains("movie") || lower.contains("bookmyshow") || lower.contains("cinema") -> {
                        logCallback?.invoke("⚡ Action: Launching BookMyShow")
                        taskExecutor.executeToolCall("open_app_or_booking", JSONObject().put("category", "movie"))
                        speakOut("Opening BookMyShow movie tickets.", keepAwakeAfter = false)
                    }
                    lower.contains("call ") || lower.contains("dial ") || lower.contains("phone lagao") -> {
                        val target = lower.substringAfter("call ", "").substringAfter("dial ", "").replace("ko", "").trim()
                        logCallback?.invoke("⚡ Action: Calling $target")
                        val res = taskExecutor.executeToolCall("make_phone_call", JSONObject().put("recipient", target))
                        if (res.optString("status") == "ok") {
                            speakOut("Calling $target.", keepAwakeAfter = false)
                        } else {
                            speakOut("Could not find contact $target in your phonebook.", keepAwakeAfter = false)
                        }
                    }
                    lower.contains("youtube") || lower.startsWith("play ") || lower.contains(" play ") -> {
                        val query = lower
                            .replace("open youtube and", "")
                            .replace("play", "")
                            .replace("on youtube", "")
                            .replace("youtube", "")
                            .trim()
                        logCallback?.invoke("⚡ Action: Opening YouTube ${if (query.isNotEmpty()) "for '$query'" else ""}")
                        taskExecutor.executeToolCall("open_app_or_booking", JSONObject().put("category", "youtube").put("destination", query))
                        speakOut(if (query.isNotEmpty()) "Playing $query on YouTube." else "Opening YouTube.", keepAwakeAfter = false)
                    }
                    // ONLY run Google Search when user EXPLICITLY says "google search", "search on google", or "search google"
                    lower.contains("google search") || lower.contains("search on google") || lower.contains("search google") || (lower.contains("google") && lower.contains("search")) -> {
                        val query = lower
                            .replace("google search", "")
                            .replace("search on google", "")
                            .replace("search google", "")
                            .replace("google par", "")
                            .replace("search karo", "")
                            .replace("search", "")
                            .replace("for ", "")
                            .trim()
                            .ifBlank { spoken }
                        logCallback?.invoke("⚡ Action: Explicit Google Search '$query'")
                        taskExecutor.executeToolCall("open_app_or_booking", JSONObject().put("category", "search").put("destination", query))
                        speakOut("Searching Google for $query.", keepAwakeAfter = false)
                    }
                    lower.contains("open ") || lower.contains("launch ") || lower.contains("kholo") -> {
                        val appName = lower
                            .replace("open ", "")
                            .replace("launch ", "")
                            .replace("kholo", "")
                            .replace("app", "")
                            .replace("please", "")
                            .trim()
                        logCallback?.invoke("⚡ Action: Opening installed app '$appName'")
                        val res = taskExecutor.executeToolCall("open_app_or_booking", JSONObject().put("category", "app").put("app_name", appName))
                        if (res.optString("status") == "ok") {
                            speakOut("Opening $appName.", keepAwakeAfter = false)
                        } else {
                            speakOut("I could not find an installed app named $appName on your phone.", keepAwakeAfter = false)
                        }
                    }
                    lower.contains("scroll down") || lower.contains("niche") -> {
                        logCallback?.invoke("⚡ Action: Scroll Down")
                        taskExecutor.executeToolCall("scroll_screen", JSONObject().put("direction", "DOWN"))
                        speakOut("Scrolled down.", keepAwakeAfter = false)
                    }
                    lower.contains("scroll up") || lower.contains("upar") -> {
                        logCallback?.invoke("⚡ Action: Scroll Up")
                        taskExecutor.executeToolCall("scroll_screen", JSONObject().put("direction", "UP"))
                        speakOut("Scrolled up.", keepAwakeAfter = false)
                    }
                    lower.contains("click ") || lower.contains("tap ") || lower.contains("dabao") -> {
                        val label = spoken
                            .substringAfter("click ", spoken)
                            .substringAfter("tap ", spoken)
                            .replace(Regex("^on\s+", RegexOption.IGNORE_CASE), "")
                            .replace("dabao", "")
                            .trim()
                        logCallback?.invoke("⚡ Action: Click '$label'")
                        val res = taskExecutor.executeToolCall("click_element", JSONObject().put("label", label))
                        if (res.optString("status") == "ok") {
                            speakOut("Clicked $label.", keepAwakeAfter = false)
                        } else {
                            speakOut("Could not find button $label on this screen.", keepAwakeAfter = false)
                        }
                    }
                    lower.contains("back") || lower.contains("piche") -> {
                        logCallback?.invoke("⚡ Action: Navigate Back")
                        taskExecutor.executeToolCall("navigate_system", JSONObject().put("action", "BACK"))
                        speakOut("Went back.", keepAwakeAfter = false)
                    }
                    lower.contains("home") -> {
                        logCallback?.invoke("⚡ Action: Navigate Home")
                        taskExecutor.executeToolCall("navigate_system", JSONObject().put("action", "HOME"))
                        speakOut("Went to home screen.", keepAwakeAfter = false)
                    }
                    else -> {
                        // NO unrequested Google Search! Try clicking matching label on screen first, or politely inform user.
                        val clickedOnScreen = AgentAccessibilityService.instance?.clickElementByLabel(spoken.trim()) ?: false
                        if (clickedOnScreen) {
                            logCallback?.invoke("⚡ Action: Clicked matching screen item '$spoken'")
                            speakOut("Clicked $spoken.", keepAwakeAfter = false)
                        } else {
                            logCallback?.invoke("ℹ️ Ignored unknown command (No unrequested Google Search): '$spoken'")
                            speakOut("I heard $spoken. Please tell me an app to open, a person to message on WhatsApp, or a button to click.", keepAwakeAfter = false)
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Command execution error", t)
                enterSleepStandby()
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
            .setContentTitle("Muse AI Voice Agent")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isMicMuted && !isSpeaking && !isListeningActive) {
            startListeningCycle()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isDestroyed = true
        instance = null
        mainHandler.removeCallbacksAndMessages(null)
        try { speechRecognizer?.destroy() } catch (_: Throwable) {}
        try { tts?.shutdown() } catch (_: Throwable) {}
        try { gemini?.stopSession() } catch (_: Throwable) {}
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Throwable) {}
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

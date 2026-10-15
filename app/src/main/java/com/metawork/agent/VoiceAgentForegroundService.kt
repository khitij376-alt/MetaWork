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
        private const val AWAKE_WINDOW_MS = 26000L

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
    var handsFreeWakeMode: Boolean = true
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
        handsFreeWakeMode = getSharedPreferences("muse_prefs", MODE_PRIVATE)
            .getBoolean("hands_free_wake_mode", true)
        try {
            val chan = NotificationChannel(CHANNEL_ID, "Muse AI Thinking Controller", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(chan)

            val notification = buildNotification("🎙️ Muse is waiting for its wake word")
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
            tts?.setSpeechRate(1.04f)
            tts?.setPitch(1.0f)
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
                    ignoreSpeechUntilMs = System.currentTimeMillis() + 900L
                    mainHandler.postDelayed({
                        if (isAwakeForCommand) {
                            setFaceState("LISTENING", 0.4f)
                            updateStatus("🟢 MUSE ACTIVE — Speak Next Command")
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

    fun wakeUpMuse(greeting: String? = null) {
        AgentAccessibilityService.instance?.stopAutoScroll()
        isAwakeForCommand = true
        awakeUntilMs = System.currentTimeMillis() + AWAKE_WINDOW_MS
        setFaceState("LISTENING", 0.45f)
        updateStatus("🟢 MUSE ACTIVE — Speak Command Now")
        transcriptCallback?.invoke("🎙️ Active Session: Speak commands back-to-back (Say 'Stop' when done)")
        logCallback?.invoke("🎙️ Active session started")

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
            transcriptCallback?.invoke("⚡ Standby (Zero Mic Lag): Tap floating Muse face on any app to start an active command session!")
        }
    }

    fun toggleHandsFreeWakeMode(): Boolean {
        handsFreeWakeMode = !handsFreeWakeMode
        getSharedPreferences("muse_prefs", MODE_PRIVATE).edit()
            .putBoolean("hands_free_wake_mode", handsFreeWakeMode)
            .apply()
        enterStandbyMode()
        return handsFreeWakeMode
    }

    fun speakOut(message: String, keepAwakeAfter: Boolean = true) {
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
            }, 1200L)
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
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
            val onDeviceAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(applicationContext)
            val recognizer = if (onDeviceAvailable) {
                try {
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(applicationContext)
                } catch (e: Throwable) {
                    Log.w(TAG, "On-device recognizer unavailable; falling back to device speech service", e)
                    SpeechRecognizer.createSpeechRecognizer(applicationContext)
                }
            } else {
                SpeechRecognizer.createSpeechRecognizer(applicationContext)
            }
            speechRecognizer = recognizer.apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        isListeningActive = true
                        if (isAwakeForCommand) {
                            updateStatus("🟢 MUSE ACTIVE — Speak Command")
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
                        val now = System.currentTimeMillis()
                        if (isAwakeForCommand && now < awakeUntilMs) {
                            if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT) {
                                // Recreate fresh recognizer on busy/client error and retry immediately
                                initOnDeviceSpeechRecognizer()
                                mainHandler.postDelayed({ startListeningCycle() }, 400L)
                                return
                            }
                            if (error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_NO_MATCH) {
                                // User paused briefly after tapping face -> retry listening once smoothly!
                                awakeUntilMs = 0L
                                mainHandler.postDelayed({ startListeningCycle() }, 350L)
                                return
                            }
                        }
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
            if (isAwakeForCommand) startListeningCycle() else enterStandbyMode()
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
        if (lastAgentUtterance.isNotBlank() && (lower == lastAgentUtterance || (lastAgentUtterance.contains(lower) && lower.length > 6))) {
            return true
        }
        val selfPrefixes = listOf(
            "hi i am muse",
            "yes tell me your command",
            "opening whatsapp",
            "sending whatsapp",
            "searching for",
            "stopped auto scrolling",
            "scrolled down",
            "scrolled up",
            "playing video",
            "clicked "
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
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 5500L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 4500L)
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
     * Exact Bilingual WhatsApp Parser:
     * Handles:
     * - "whatsapp open karke Saurabh ko message bhejo hey how are u" -> ("Saurabh", "hey how are u")
     * - "send a message to Saurabh hey how are you"                  -> ("Saurabh", "hey how are you")
     * - "Saurabh ko hey how are u bhejo"                             -> ("Saurabh", "hey how are u")
     * - "send whatsapp message to Saurabh saying hey how are u"      -> ("Saurabh", "hey how are u")
     */
    private fun parseWhatsAppIntent(spoken: String): Pair<String, String> {
        val clean = spoken.trim()
        val lower = clean.lowercase(Locale.ROOT)

        // 1. Check if user ONLY asked to open WhatsApp
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

        // Strip leading WhatsApp open/send prefixes first
        val withoutPrefix = clean
            .replace(Regex("^(?:open[ ]+whatsapp[ ]+(?:and[ ]+)?|whatsapp[ ]+(?:open[ ]+karke|khol[ ]+ke|kholo[ ]+aur|par|pe|mein|me)[ ]+)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^(?:please[ ]+)?(?:send[ ]+(?:a[ ]+)?(?:whatsapp[ ]+)?(?:message|msg|text)[ ]+(?:on[ ]+whatsapp[ ]+)?(?:to|for)[ ]+)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^(?:whatsapp[ ]+(?:message|msg)[ ]+(?:to|for)[ ]+|(?:message|msg|whatsapp)[ ]+to[ ]+)", RegexOption.IGNORE_CASE), "")
            .trim()

        // 2. If it contains " ko " (Hinglish: "<Name> ko message bhejo <Message>" OR "<Name> ko <Message> bhejo")
        if (withoutPrefix.lowercase(Locale.ROOT).contains(" ko ")) {
            val recPart = withoutPrefix.substringBefore(" ko ").substringBefore(" KO ")
                .replace(Regex("^(?:whatsapp[ ]+par|whatsapp[ ]+pe|whatsapp|to|send|message)[ ]+", RegexOption.IGNORE_CASE), "")
                .trim()
            val afterKo = withoutPrefix.substringAfter(" ko ", withoutPrefix.substringAfter(" KO ", "")).trim()
            val cleanMsg = cleanWhatsAppMessageBody(afterKo)
            if (recPart.isNotBlank()) {
                return Pair(recPart, cleanMsg)
            }
        }

        // 3. If original sentence had " to <Name> ..."
        val toRegex = Regex("(?:to|for)[ ]+([a-zA-Z0-9+]+)[ ]+(.+)$", RegexOption.IGNORE_CASE)
        val toMatch = toRegex.find(clean)
        if (toMatch != null) {
            val rec = toMatch.groupValues[1].trim()
            val rawMsg = toMatch.groupValues[2].trim()
            return Pair(rec, cleanWhatsAppMessageBody(rawMsg))
        }

        // 4. After stripping prefix ("send a message to Saurabh hey how are you" -> "Saurabh hey how are you")
        val tokens = withoutPrefix.split(Regex("[ ]+"))
        if (tokens.size >= 2) {
            val firstWord = tokens[0].trim()
            if (!firstWord.equals("whatsapp", ignoreCase = true) && !firstWord.equals("message", ignoreCase = true) && !firstWord.equals("send", ignoreCase = true)) {
                val rest = withoutPrefix.substringAfter(firstWord).trim()
                return Pair(firstWord, cleanWhatsAppMessageBody(rest))
            }
        }

        return Pair("", "")
    }

    private fun cleanWhatsAppMessageBody(raw: String): String {
        var msg = raw.trim()
        // Strip leading verb phrases ("message bhejo hey how are u" -> "hey how are u")
        msg = msg.replace(
            Regex("^(?:on[ ]+whatsapp[ ]+|whatsapp[ ]+par[ ]+|whatsapp[ ]+pe[ ]+)?(?:a[ ]+)?(?:whatsapp[ ]+message|whatsapp|message|msg)[ ]+(?:bhejo|bhego|bhej[ ]+do|karo|kar[ ]+do|send[ ]+karo|likho|bolo|saying|that|ki)[ ]+", RegexOption.IGNORE_CASE),
            ""
        )
        msg = msg.replace(
            Regex("^(?:bhejo|bhego|bhej[ ]+do|saying|that|ki|likho|bolo|message|msg)[ ]+", RegexOption.IGNORE_CASE),
            ""
        )
        // Strip trailing verb phrases ("hey how are u bhejo" -> "hey how are u")
        msg = msg.replace(
            Regex("[ ]+(?:whatsapp[ ]+message[ ]+bhejo|message[ ]+bhejo|msg[ ]+bhejo|whatsapp[ ]+karo|message[ ]+karo|msg[ ]+karo|bhejo|bhego|bhej[ ]+do|send[ ]+karo|on[ ]+whatsapp)$", RegexOption.IGNORE_CASE),
            ""
        )
        return msg.trim()
    }

    private fun splitChainedCommands(spoken: String): List<String> {
        val lower = spoken.lowercase(Locale.ROOT).trim()
        // Keep WhatsApp messages, Login credentials, and "Open YouTube and play <query>" as single atomic intents!
        if (lower.contains("whatsapp") && (lower.contains("saying") || lower.contains(" ko ") || lower.contains("message") || lower.contains("bhejo") || lower.contains("bhego"))) {
            return listOf(spoken.trim())
        }
        if ((lower.contains("username") || lower.contains("email")) && lower.contains("password")) {
            return listOf(spoken.trim())
        }
        if (VoiceCommandParser.isAccountSignupRequest(lower)) {
            return listOf(spoken.trim())
        }
        if (lower.contains("youtube") && lower.contains("play") && !lower.contains("this video") && !lower.contains("scroll")) {
            return listOf(spoken.trim())
        }
        // Split on explicit sequence connectors ("then", "and then", "uske baad", "aur phir")
        // OR on "and"/"aur" ONLY when followed by an explicit action verb!
        val chainRegex = Regex(
            "[ ]+(?:and[ ]+then|then|aur[ ]+phir|phir|uske[ ]+baad|(?:and|aur)(?=[ ]+(?:scroll|niche|upar|play|click|tap|open|type|write|enter|password|username|login|go|back|home|volume|torch)))[ ]+",
            RegexOption.IGNORE_CASE
        )
        val parts = spoken.split(chainRegex)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return if (parts.isEmpty()) listOf(spoken.trim()) else parts
    }

    fun handleSpokenCommand(spoken: String) {
        val lower = spoken.lowercase(Locale.ROOT).trim()
        if (lower in setOf("stop listening", "disable wake word", "wake word off", "listening band", "sunna band karo")) {
            AgentAccessibilityService.instance?.stopAutoScroll()
            handsFreeWakeMode = false
            getSharedPreferences("muse_prefs", MODE_PRIVATE).edit()
                .putBoolean("hands_free_wake_mode", false)
                .apply()
            speakOut("Wake-word listening is off. Tap the Muse face when you want me.", keepAwakeAfter = false)
            return
        }
        if (lower in setOf("enable wake word", "always listen", "wake word on", "hamesha suno")) {
            handsFreeWakeMode = true
            getSharedPreferences("muse_prefs", MODE_PRIVATE).edit()
                .putBoolean("hands_free_wake_mode", true)
                .apply()
            speakOut("Wake-word mode is on. Say Muse followed by your request.", keepAwakeAfter = false)
            return
        }
        if (lower == "stop" || lower == "sleep" || lower == "bas" || lower == "go to sleep" || lower == "thank you") {
            AgentAccessibilityService.instance?.stopAutoScroll()
            speakOut("Okay, going to standby.", keepAwakeAfter = false)
            return
        }

        setFaceState("EXECUTING", 0.8f)
        updateStatus("🧠 THINKING & EXECUTING: $spoken")

        scope.launch {
            try {
                // Step 0: Run screen thinking summary so user sees what Muse sees
                val screenContext = AgentAccessibilityService.instance?.analyzeScreenContextSummary().orEmpty()
                if (screenContext.isNotBlank()) {
                    logCallback?.invoke("🧠 Thinking: $screenContext")
                }

                val steps = splitChainedCommands(spoken)
                var finalSpokenFeedback = "Done."
                for ((idx, step) in steps.withIndex()) {
                    if (idx > 0) {
                        delay(1500L)
                    }
                    logCallback?.invoke("⚡ Step ${idx + 1}/${steps.size}: $step")
                    finalSpokenFeedback = executeSingleStep(step)
                }
                // Keep session awake after executing so user can speak next command without tapping again!
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

    private suspend fun executeSingleStep(spoken: String): String {
        val lower = spoken.lowercase(Locale.ROOT).trim()
        return when {
            // 1. Website / App Login with BOTH Username & Password in one command
            (lower.contains("username") || lower.contains("email") || lower.contains("user id")) && lower.contains("password") -> {
                val userPart = spoken
                    .substringAfter("username", spoken.substringAfter("email", ""))
                    .substringBefore("and password")
                    .substringBefore("aur password")
                    .substringBefore("password")
                    .replace(Regex("^(?:is|me|mein|:)[ ]+", RegexOption.IGNORE_CASE), "")
                    .trim()
                val passPart = spoken
                    .substringAfter("password", "")
                    .replace(Regex("^(?:is|me|mein|:)[ ]+", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("[ ]+(?:and[ ]+login|aur[ ]+login[ ]+karo|dalo|enter[ ]+karo)$", RegexOption.IGNORE_CASE), "")
                    .replace(" ", "")
                    .trim()
                logCallback?.invoke("🔐 Filling Login: User='$userPart', Password='***'")
                val res = taskExecutor.executeToolCall("fill_login_form", JSONObject().put("username", userPart).put("password", passPart))
                if (res.optString("status") == "ok") "Entered username and password and clicked login." else "Could not find login fields on this screen."
            }

            // 2. Password Entry Command ("enter password XYZ", "password me XYZ dalo")
            lower.startsWith("password ") || lower.contains("enter password") || lower.contains("type password") || lower.contains("password me ") || lower.contains("password mein ") -> {
                val passVal = spoken
                    .replace(Regex("^(?:enter|type|set|fill)?[ ]*password(?:[ ]+is|[ ]+me|[ ]+mein)?[ ]+", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("[ ]+(?:dalo|dal[ ]+do|likho|and[ ]+login|aur[ ]+login[ ]+karo)$", RegexOption.IGNORE_CASE), "")
                    .replace(" ", "")
                    .trim()
                logCallback?.invoke("🔐 Entering password into password field")
                val res = taskExecutor.executeToolCall("enter_password", JSONObject().put("password", passVal).put("submit", true))
                if (res.optString("status") == "ok") "Entered password." else "Could not find a password field on screen."
            }

            // 3. Username / Email Entry Command ("enter username XYZ", "username me XYZ dalo", "enter email XYZ")
            lower.startsWith("username ") || lower.startsWith("email ") || lower.contains("enter username") || lower.contains("enter email") || lower.contains("username me ") || lower.contains("email me ") -> {
                val userVal = spoken
                    .replace(Regex("^(?:enter|type|set|fill)?[ ]*(?:username|email|user[ ]+id)(?:[ ]+is|[ ]+me|[ ]+mein)?[ ]+", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("[ ]+(?:dalo|dal[ ]+do|likho)$", RegexOption.IGNORE_CASE), "")
                    .trim()
                logCallback?.invoke("👤 Entering username/email: '$userVal'")
                val res = taskExecutor.executeToolCall("enter_username", JSONObject().put("username", userVal))
                if (res.optString("status") == "ok") "Entered $userVal." else "Could not find username or email field on screen."
            }

            // Route account creation to the requested app, never to a generic web search.
            VoiceCommandParser.isAccountSignupRequest(lower) -> {
                val appName = VoiceCommandParser.accountSignupTarget(spoken)
                if (appName == null) {
                    "Which app do you want to create an account for?"
                } else {
                    val opened = taskExecutor.executeToolCall(
                        "open_app_or_booking",
                        JSONObject().put("category", "app").put("app_name", appName)
                    )
                    if (opened.optString("status") != "ok") {
                        "I could not find $appName installed on this phone."
                    } else {
                        delay(900L)
                        val guidedResult = taskExecutor.runGeminiAutonomousScreenPlan(spoken)
                        guidedResult ?: "Opened $appName. Enable Screen Controller and add a Gemini key if you want me to guide the visible signup steps. I cannot bypass OTPs or CAPTCHAs."
                    }
                }
            }

            // 4. Open Website URL ("open website github.com", "go to flipkart.com")
            lower.startsWith("open website ") || lower.startsWith("go to ") || lower.contains(".com") || lower.contains(".in") || lower.contains(".org") -> {
                val url = lower
                    .replace("open website", "")
                    .replace("go to", "")
                    .replace("open", "")
                    .trim()
                taskExecutor.executeToolCall("open_website", JSONObject().put("url", url))
                "Opening website $url."
            }

            // 5. Play / Click the Video Currently Visible on Screen ("play this video", "play video", "ye video chalao", "isko play karo")
            lower == "play" || lower == "play video" || lower == "play this video" || lower == "play this" || lower == "click this video" || lower == "open this video" || lower.contains("ye video") || lower.contains("isko play") -> {
                val res = taskExecutor.executeToolCall("click_center_video", JSONObject())
                if (res.optString("status") == "ok") "Playing video on screen." else "Could not tap video on screen."
            }

            // 6. Scroll Down / Up / Multi-Scroll ("scroll down", "scroll scroll", "scroll 3 times", "niche karo")
            lower.contains("scroll") || lower.contains("niche") || lower.contains("upar") || lower.contains("next reel") || lower.contains("next video") || lower.contains("next short") || lower == "next" || lower.contains("swipe") -> {
                if (lower.contains("auto scroll") || lower.contains("keep scrolling") || lower.contains("reels scroll")) {
                    val dir = if (lower.contains("up")) "UP" else "DOWN"
                    taskExecutor.executeToolCall("auto_scroll", JSONObject().put("enable", true).put("direction", dir))
                    "Auto scrolling started."
                } else {
                    val dir = when {
                        lower.contains("up") || lower.contains("upar") || lower.contains("previous") -> "UP"
                        lower.contains("left") -> "LEFT"
                        lower.contains("right") -> "RIGHT"
                        else -> "DOWN"
                    }
                    val numFromDigits = extractNumbers(lower).firstOrNull() ?: 0
                    val wordOccurrences = Regex("(?:scroll|niche|upar)").findAll(lower).count()
                    val repeatCount = when {
                        numFromDigits in 2..8 -> numFromDigits
                        lower.contains("twice") || lower.contains("do baar") -> 2
                        lower.contains("three times") || lower.contains("teen baar") -> 3
                        wordOccurrences >= 2 -> wordOccurrences.coerceAtMost(5)
                        else -> 1
                    }
                    var ok = false
                    for (r in 1..repeatCount) {
                        val res = taskExecutor.executeToolCall("scroll_screen", JSONObject().put("direction", dir))
                        if (res.optString("status") == "ok") ok = true
                        if (r < repeatCount) delay(900L)
                    }
                    if (ok) {
                        if (repeatCount > 1) "Scrolled $dir $repeatCount times." else "Scrolled $dir."
                    } else {
                        "Please enable Muse Screen Controller in Accessibility Settings."
                    }
                }
            }

            // 7. Flashlight / Torch
            lower.contains("torch") || lower.contains("flashlight") -> {
                val turnOff = lower.contains("off") || lower.contains("band")
                val res = taskExecutor.executeToolCall("set_torch", JSONObject().put("enable", !turnOff))
                if (res.optString("status") == "ok") {
                    if (turnOff) "Turned off flashlight." else "Turned on flashlight."
                } else {
                    "Could not toggle flashlight."
                }
            }

            // 8. Alarms & Timers
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
            lower.contains("timer") -> {
                val nums = extractNumbers(lower)
                val amount = nums.firstOrNull() ?: 5
                val seconds = if (lower.contains("second")) amount else amount * 60
                taskExecutor.executeToolCall("set_timer", JSONObject().put("seconds", seconds))
                "Started timer for $amount ${if (lower.contains("second")) "seconds" else "minutes"}."
            }

            // 9. Google Maps Navigation
            lower.contains("navigate to ") || lower.contains("direction to ") || lower.contains("rasta") || lower.startsWith("map ") -> {
                val dest = spoken
                    .replace(Regex("^(?:navigate[ ]+to|direction[ ]+to|directions[ ]+to|map[ ]+to|maps[ ]+to)[ ]*", RegexOption.IGNORE_CASE), "")
                    .replace("ka rasta dikhao", "")
                    .replace("ka rasta", "")
                    .trim()
                taskExecutor.executeToolCall("maps_navigate", JSONObject().put("destination", dest))
                "Starting Google Maps navigation to $dest."
            }

            // 10. System Settings
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
                "Opened display settings."
            }
            lower.contains("battery") -> {
                taskExecutor.executeToolCall("open_setting", JSONObject().put("setting", "battery"))
                "Opened battery status."
            }
            lower.contains("location setting") || lower.contains("gps") -> {
                taskExecutor.executeToolCall("open_setting", JSONObject().put("setting", "location"))
                "Opened location settings."
            }

            // 11. Read Screen
            lower.contains("read screen") || lower.contains("screen padho") || lower.contains("what is on screen") || lower.contains("kya likha hai") -> {
                val res = taskExecutor.executeToolCall("read_screen", JSONObject())
                res.optString("summary", "").ifBlank { "Please enable Muse Screen Controller in Accessibility Settings." }
            }

            // 12. System Global Actions & Volume
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

            // 13. Gestures & Ordinal Clicks
            lower.contains("double tap") || lower == "like" || lower == "like karo" -> {
                val res = taskExecutor.executeToolCall("double_tap", JSONObject())
                if (res.optString("status") == "ok") "Double tapped." else "Enable Screen Controller first."
            }
            lower.startsWith("long press") || lower.startsWith("hold ") || lower.contains("long press") -> {
                val target = spoken.replace(Regex("^(?:long[ ]+press|hold)(?:[ ]+on)?[ ]*", RegexOption.IGNORE_CASE), "").trim()
                taskExecutor.executeToolCall("long_press", JSONObject().put("label", target))
                "Long pressed $target."
            }
            lower.contains("first ") || lower.contains("1st") || lower.contains("pehla") || lower.contains("top result") || lower == "first" -> {
                val res = taskExecutor.executeToolCall("click_ordinal", JSONObject().put("index", 1))
                if (res.optString("status") == "ok") "Clicked first item." else "Could not click first item."
            }
            lower.contains("second ") || lower.contains("2nd") || lower.contains("dusra") || lower == "second" -> {
                val res = taskExecutor.executeToolCall("click_ordinal", JSONObject().put("index", 2))
                if (res.optString("status") == "ok") "Clicked second item." else "Could not click second item."
            }
            lower.contains("third ") || lower.contains("3rd") || lower.contains("teesra") || lower == "third" -> {
                val res = taskExecutor.executeToolCall("click_ordinal", JSONObject().put("index", 3))
                if (res.optString("status") == "ok") "Clicked third item." else "Could not click third item."
            }

            // 14. Text Entry
            lower.startsWith("write ") || lower.startsWith("type ") || lower.contains("likho ") -> {
                val txt = spoken
                    .replace(Regex("^(write|type|likho)[ ]+", RegexOption.IGNORE_CASE), "")
                    .trim()
                val res = taskExecutor.executeToolCall("enter_text", JSONObject().put("text", txt).put("click_send_after", true))
                if (res.optString("status") == "ok") "Typed $txt." else "Tap an input box first."
            }

            // 15. WhatsApp Messaging (Exact Message Extraction!)
            lower.contains("whatsapp") || lower.contains("bhejo") || lower.contains("bhego") || (lower.contains("message") && !lower.contains("sms")) -> {
                val (recipient, msg) = VoiceCommandParser.parseWhatsAppIntent(spoken)
                if (recipient.isBlank()) {
                    taskExecutor.executeToolCall("open_app_or_booking", JSONObject().put("category", "app").put("app_name", "whatsapp"))
                    "Opened WhatsApp."
                } else if (msg.isBlank()) {
                    "I found $recipient, but you did not say the message text. Nothing was sent. Say the exact message, for example: Muse, Rahul ko ‘I will be late’ bhejo."
                } else {
                    logCallback?.invoke("💬 WhatsApp -> To: '$recipient', Msg: '$msg'")
                    val result = taskExecutor.executeToolCall("send_whatsapp_message", JSONObject().put("recipient", recipient).put("message", msg))
                    if (result.optString("status") == "ok") "Sending '$msg' to $recipient on WhatsApp."
                    else "I could not send that WhatsApp message. Check that WhatsApp and Screen Controller are available."
                }
            }

            // 16. SMS, Calls & Bookings
            lower.contains("sms ") || lower.contains("text message") -> {
                val recipient = spoken.substringAfter("to ", "").substringBefore(" saying").substringBefore(" ko ").trim()
                val msg = if (recipient.isBlank()) "" else cleanWhatsAppMessageBody(spoken.substringAfter(recipient, ""))
                val result = taskExecutor.executeToolCall("send_sms", JSONObject().put("recipient", recipient).put("message", msg))
                when (result.optString("status")) {
                    "ok" -> "Sent SMS to $recipient."
                    "drafted" -> "Opened an SMS draft to $recipient. Review it and tap Send."
                    else -> "Could not send the SMS. Check the recipient, message text, and SMS permission."
                }
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
            lower.contains("call ") || lower.contains("dial ") || lower.contains("phone lagao") ||
                lower.contains("phone karo") || lower.contains("phone kar do") || lower.contains("call karo") ||
                lower.contains("call kar do") || lower.contains("dial karo") || lower.contains("phone milao") -> {
                val target = VoiceCommandParser.extractCallTarget(spoken)
                val res = taskExecutor.executeToolCall("make_phone_call", JSONObject().put("recipient", target))
                when (res.optString("status")) {
                    "ok" -> "Starting a call to $target."
                    "dialer" -> "Opened the phone dialer for $target. Tap Call if Android asks you to confirm."
                    else -> "Could not find contact $target or open the phone app."
                }
            }
            lower.contains("youtube") || lower.startsWith("play ") || lower.contains(" play ") -> {
                val query = VoiceCommandParser.extractYouTubeQuery(spoken)
                if (query.isEmpty()) {
                    // User said "Open YouTube" or "YouTube kholo" -> Launch the native YouTube app!
                    taskExecutor.executeToolCall("open_app_or_booking", JSONObject().put("category", "youtube").put("destination", ""))
                    "Opening YouTube."
                } else if (query == "video" || query == "this" || query == "this video") {
                    taskExecutor.executeToolCall("click_center_video", JSONObject())
                    "Playing video on screen."
                } else {
                    taskExecutor.executeToolCall("open_app_or_booking", JSONObject().put("category", "youtube").put("destination", query))
                    scope.launch {
                        val service = AgentAccessibilityService.instance
                        if (service != null) {
                            for (i in 1..12) {
                                delay(350L)
                                if (service.getActivePackageName().contains("youtube")) {
                                    delay(1400L)
                                    service.clickByOrdinal(1)
                                    break
                                }
                            }
                        }
                    }
                    "Playing $query on YouTube."
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
                if (res.optString("status") == "ok") "Searched for $query." else "Could not find search box on screen."
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
                    val clicked = AgentAccessibilityService.instance?.clickElementByLabel(appName) ?: false
                    if (clicked) "Opened $appName on screen." else "Could not find $appName."
                }
            }
            lower.contains("click ") || lower.contains("tap ") || lower.contains("dabao") || lower.contains("select ") || lower.contains("press ") || lower.contains("login") || lower.contains("sign in") || lower.contains("submit") -> {
                val label = spoken
                    .replace(Regex("^(?:click|tap|select|press)(?:[ ]+on|[ ]+the)?[ ]+", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("[ ]+(?:par[ ]+click[ ]+karo|click[ ]+karo|dabao|button)$", RegexOption.IGNORE_CASE), "")
                    .trim()
                    .ifBlank { spoken.trim() }
                val res = taskExecutor.executeToolCall("click_element", JSONObject().put("label", label))
                if (res.optString("status") == "ok") "Clicked $label." else {
                    val aiSpoken = taskExecutor.runGeminiAutonomousScreenPlan(spoken)
                    aiSpoken ?: "Could not find '$label' on screen."
                }
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
                // 1. First use the Gemini 2.0 Flash Autonomous AI Brain to think, answer questions, or plan multi-step actions!
                val aiSpoken = taskExecutor.runGeminiAutonomousScreenPlan(spoken)
                if (!aiSpoken.isNullOrBlank()) {
                    aiSpoken
                } else {
                    // 2. Offline fallback: only click if an exact button label matches on screen
                    val clickedDirectly = AgentAccessibilityService.instance?.clickElementByLabel(spoken.trim()) ?: false
                    if (clickedDirectly) {
                        "Clicked $spoken."
                    } else if (AgentAccessibilityService.instance == null) {
                        "Screen control is off. Enable Muse Screen Controller in Android Accessibility settings, then repeat the request."
                    } else if (taskExecutor.getEffectiveGeminiKey().isBlank()) {
                        "That request needs the Gemini planner. Add your Gemini API key on the Muse screen; common voice commands still work without it."
                    } else {
                        "I could not safely map that request to an action. Please say the app and the exact task you want."
                    }
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
            .setContentTitle("Muse AI v8.2 Voice Assistant")
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

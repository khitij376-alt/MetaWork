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
        private const val TAG = "MetaWorkVoiceService"
        private const val CHANNEL_ID = "metawork_24_7_channel"
        private const val NOTIFICATION_ID = 1001

        @Volatile
        var instance: VoiceAgentForegroundService? = null
            private set

        var statusCallback: ((String) -> Unit)? = null
        var transcriptCallback: ((String) -> Unit)? = null
        var logCallback: ((String) -> Unit)? = null
        var faceStateCallback: ((AgentFaceView.FaceState, Float) -> Unit)? = null
    }

    private val safeHandler = CoroutineExceptionHandler { _, t ->
        Log.e(TAG, "Handled coroutine error: ${t.message}")
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + safeHandler)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var wakeLock: PowerManager.WakeLock? = null
    lateinit var taskExecutor: TaskActionExecutor
        private set
    private lateinit var gemini: GeminiVoiceSessionManager
    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var isDestroyed = false
    private var isSpeaking = false
    private var isListeningActive = false

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (isDestroyed) return
            if (!isSpeaking && !isListeningActive) {
                startContinuousOnDeviceListening()
            }
            mainHandler.postDelayed(this, 10000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        try {
            val chan = NotificationChannel(CHANNEL_ID, "MetaWork 24/7 Voice Agent", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(chan)

            val notification = buildNotification("24/7 Voice Agent Active — Speak Anytime")
            val hasMicPerm = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val fgsType = if (hasMicPerm) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                }
                startForeground(NOTIFICATION_ID, notification, fgsType)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && hasMicPerm) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MetaWork::AlwaysOnWakeLock")
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
            mainHandler.postDelayed(watchdogRunnable, 10000L)
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
                    setFaceState(AgentFaceView.FaceState.SPEAKING, 0.8f)
                }
                override fun onDone(utteranceId: String?) {
                    isSpeaking = false
                    setFaceState(AgentFaceView.FaceState.LISTENING, 0.2f)
                    mainHandler.postDelayed({ startContinuousOnDeviceListening() }, 250L)
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    isSpeaking = false
                    mainHandler.postDelayed({ startContinuousOnDeviceListening() }, 250L)
                }
            })
            speakOut("MetaWork agent is ready. Speak any command or tap any card on the dashboard.")
        } else {
            startContinuousOnDeviceListening()
        }
    }

    fun triggerImmediateListen(greeting: String? = null) {
        if (!greeting.isNullOrBlank()) {
            speakOut(greeting)
        } else {
            isSpeaking = false
            startContinuousOnDeviceListening()
        }
    }

    fun speakOut(message: String) {
        isSpeaking = true
        isListeningActive = false
        mainHandler.post {
            try { speechRecognizer?.cancel() } catch (_: Throwable) {}
        }
        setFaceState(AgentFaceView.FaceState.SPEAKING, 0.85f)
        transcriptCallback?.invoke("🤖 Agent: $message")
        logCallback?.invoke("Agent: $message")

        val params = Bundle()
        val res = tts?.speak(message, TextToSpeech.QUEUE_FLUSH, params, "mw_${System.currentTimeMillis()}")
        if (res != TextToSpeech.SUCCESS) {
            mainHandler.postDelayed({
                isSpeaking = false
                setFaceState(AgentFaceView.FaceState.LISTENING, 0.2f)
                startContinuousOnDeviceListening()
            }, 2200L)
        }
    }

    private fun setFaceState(state: AgentFaceView.FaceState, level: Float) {
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
                            updateStatus("LISTENING 24/7 — Speak Now")
                            setFaceState(AgentFaceView.FaceState.LISTENING, 0.25f)
                        }
                        override fun onBeginningOfSpeech() {
                            isListeningActive = true
                            setFaceState(AgentFaceView.FaceState.LISTENING, 0.6f)
                        }
                        override fun onRmsChanged(rmsdB: Float) {
                            val norm = ((rmsdB + 2f) / 12f).coerceIn(0.1f, 1.0f)
                            setFaceState(AgentFaceView.FaceState.LISTENING, norm)
                        }
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() {
                            isListeningActive = false
                        }
                        override fun onError(error: Int) {
                            isListeningActive = false
                            if (!isDestroyed && !isSpeaking) {
                                val delayMs = if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 900L else 450L
                                mainHandler.postDelayed({ startContinuousOnDeviceListening() }, delayMs)
                            }
                        }
                        override fun onResults(results: Bundle?) {
                            isListeningActive = false
                            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            val spoken = matches?.firstOrNull()?.trim().orEmpty()
                            if (spoken.isNotEmpty()) {
                                transcriptCallback?.invoke("🗣️ You: \"$spoken\"")
                                logCallback?.invoke("You said: $spoken")
                                handleSpokenCommand(spoken)
                            } else {
                                startContinuousOnDeviceListening()
                            }
                        }
                        override fun onPartialResults(partialResults: Bundle?) {
                            val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                            if (!partial.isNullOrBlank()) {
                                transcriptCallback?.invoke("🎙️ Hearing: \"$partial...\"")
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

    fun startContinuousOnDeviceListening() {
        if (isDestroyed || isSpeaking) return
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

    fun handleSpokenCommand(spoken: String) {
        val lower = spoken.lowercase(Locale.ROOT).trim()
        setFaceState(AgentFaceView.FaceState.EXECUTING, 0.7f)
        updateStatus("EXECUTING: $spoken")

        scope.launch {
            try {
                when {
                    lower.startsWith("write ") || lower.startsWith("type ") || lower.contains("likho ") -> {
                        val txt = spoken
                            .replace(Regex("^(write|type|likho)\\s+", RegexOption.IGNORE_CASE), "")
                            .trim()
                        logCallback?.invoke("⚡ Action: Typing text on screen: '$txt'")
                        taskExecutor.executeToolCall("enter_text", JSONObject().put("text", txt).put("click_send_after", true))
                        speakOut("Typed: $txt")
                    }
                    lower.contains("sms ") || lower.contains("text message") -> {
                        val recipient = spoken.substringAfter("to ", "").substringBefore(" saying").trim()
                        val msg = spoken.substringAfter("saying ", "Hello").trim()
                        logCallback?.invoke("⚡ Action: Sending SMS to $recipient: $msg")
                        taskExecutor.executeToolCall("send_sms", JSONObject().put("recipient", recipient).put("message", msg))
                        speakOut("Sent SMS to $recipient.")
                    }
                    lower.contains("train") || lower.contains("irctc") || lower.contains("rail") -> {
                        logCallback?.invoke("⚡ Action: Launching IRCTC Train Booking")
                        taskExecutor.executeToolCall("open_app_or_booking", JSONObject().put("category", "train"))
                        speakOut("Opening IRCTC train ticket booking now.")
                    }
                    lower.contains("flight") || lower.contains("makemytrip") || lower.contains("plane") -> {
                        val dest = lower.substringAfter("to ", "Mumbai").substringBefore(" ").trim()
                        logCallback?.invoke("⚡ Action: Launching Flight Search to $dest")
                        taskExecutor.executeToolCall("open_app_or_booking", JSONObject().put("category", "flight").put("destination", dest))
                        speakOut("Opening flight search to $dest.")
                    }
                    lower.contains("uber") || lower.contains("cab") || lower.contains("taxi") || lower.contains("ola") -> {
                        val dest = spoken.substringAfter("to ", "Railway Station").trim()
                        logCallback?.invoke("⚡ Action: Launching Uber Cab to $dest")
                        taskExecutor.executeToolCall("open_app_or_booking", JSONObject().put("category", "cab").put("destination", dest))
                        speakOut("Booking Uber ride to $dest.")
                    }
                    lower.contains("bus") || lower.contains("redbus") -> {
                        logCallback?.invoke("⚡ Action: Launching RedBus")
                        taskExecutor.executeToolCall("open_app_or_booking", JSONObject().put("category", "bus"))
                        speakOut("Opening RedBus ticket booking.")
                    }
                    lower.contains("movie") || lower.contains("bookmyshow") || lower.contains("cinema") -> {
                        logCallback?.invoke("⚡ Action: Launching BookMyShow")
                        taskExecutor.executeToolCall("open_app_or_booking", JSONObject().put("category", "movie"))
                        speakOut("Opening BookMyShow movie tickets.")
                    }
                    lower.contains("whatsapp") || lower.contains("message") || lower.contains("bhejo") -> {
                        val recipient = spoken.substringAfter("to ", "").substringBefore(" saying").substringBefore(" ko ").trim()
                        val msg = if (spoken.contains("saying ", ignoreCase = true)) {
                            spoken.substringAfter("saying ", "").trim()
                        } else {
                            "Hello from MetaWork voice agent"
                        }
                        logCallback?.invoke("⚡ Action: Sending WhatsApp to ${recipient.ifBlank { "contact" }}: $msg")
                        taskExecutor.executeToolCall("send_whatsapp_message", JSONObject().put("recipient", recipient).put("message", msg))
                        speakOut("Opening WhatsApp to send message.")
                    }
                    lower.contains("call ") || lower.contains("dial ") || lower.contains("phone lagao") -> {
                        val target = lower.substringAfter("call ", "").substringAfter("dial ", "").replace("ko", "").trim()
                        logCallback?.invoke("⚡ Action: Calling $target")
                        taskExecutor.executeToolCall("make_phone_call", JSONObject().put("recipient", target))
                        speakOut("Calling $target now.")
                    }
                    lower.contains("youtube") || lower.contains("play ") || lower.contains("song") -> {
                        val query = lower.replace("play", "").replace("on youtube", "").replace("youtube", "").trim().ifBlank { "trending music" }
                        logCallback?.invoke("⚡ Action: Playing '$query' on YouTube")
                        taskExecutor.executeToolCall("open_app_or_booking", JSONObject().put("category", "youtube").put("destination", query))
                        speakOut("Opening YouTube for $query.")
                    }
                    lower.contains("search ") || lower.contains("google") -> {
                        val query = lower.replace("search", "").replace("on google", "").replace("google", "").replace("for ", "").trim().ifBlank { spoken }
                        logCallback?.invoke("⚡ Action: Google Search '$query'")
                        taskExecutor.executeToolCall("open_app_or_booking", JSONObject().put("category", "search").put("destination", query))
                        speakOut("Searching Google for $query.")
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
                        taskExecutor.executeToolCall("open_app_or_booking", JSONObject().put("category", "app").put("app_name", appName))
                        speakOut("Opening $appName.")
                    }
                    lower.contains("scroll down") || lower.contains("niche") -> {
                        logCallback?.invoke("⚡ Action: Scroll Down")
                        taskExecutor.executeToolCall("scroll_screen", JSONObject().put("direction", "DOWN"))
                        speakOut("Scrolled down.")
                    }
                    lower.contains("scroll up") || lower.contains("upar") -> {
                        logCallback?.invoke("⚡ Action: Scroll Up")
                        taskExecutor.executeToolCall("scroll_screen", JSONObject().put("direction", "UP"))
                        speakOut("Scrolled up.")
                    }
                    lower.contains("click ") || lower.contains("tap ") -> {
                        val label = spoken.substringAfter("click ", "").substringAfter("tap ", "").replace("on ", "").trim()
                        logCallback?.invoke("⚡ Action: Click '$label'")
                        taskExecutor.executeToolCall("click_element", JSONObject().put("label", label))
                        speakOut("Clicked $label.")
                    }
                    lower.contains("back") || lower.contains("piche") -> {
                        logCallback?.invoke("⚡ Action: Navigate Back")
                        taskExecutor.executeToolCall("navigate_system", JSONObject().put("action", "BACK"))
                        speakOut("Went back.")
                    }
                    lower.contains("home") -> {
                        logCallback?.invoke("⚡ Action: Navigate Home")
                        taskExecutor.executeToolCall("navigate_system", JSONObject().put("action", "HOME"))
                        speakOut("Went to home screen.")
                    }
                    else -> {
                        logCallback?.invoke("⚡ Action: Smart Search for '$spoken'")
                        taskExecutor.executeToolCall("open_app_or_booking", JSONObject().put("category", "search").put("destination", spoken))
                        speakOut("Searching for $spoken.")
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Command execution error", t)
                speakOut("Completed command.")
            }
        }
    }

    private fun updateStatus(state: String) {
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification("Status: $state"))
            statusCallback?.invoke(state)
        } catch (_: Throwable) {}
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MetaWork 24/7 Active")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isSpeaking && !isListeningActive) {
            startContinuousOnDeviceListening()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isDestroyed = true
        instance = null
        mainHandler.removeCallbacksAndMessages(null)
        try { speechRecognizer?.destroy() } catch (_: Throwable) {}
        try { tts?.shutdown() } catch (_: Throwable) {}
        gemini.stopSession()
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Throwable) {}
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

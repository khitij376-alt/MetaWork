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
        var statusCallback: ((String) -> Unit)? = null
        var transcriptCallback: ((String) -> Unit)? = null
    }

    private val safeHandler = CoroutineExceptionHandler { _, t ->
        Log.e(TAG, "Handled service coroutine error: ${t.message}")
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + safeHandler)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var taskExecutor: TaskActionExecutor
    private lateinit var gemini: GeminiVoiceSessionManager
    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var isDestroyed = false
    private var isSpeaking = false

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (isDestroyed) return
            if (!isSpeaking && (BuildConfig.GEMINI_API_KEY.isBlank() || BuildConfig.GEMINI_API_KEY == "YOUR_GEMINI_API_KEY")) {
                startContinuousOnDeviceListening()
            }
            mainHandler.postDelayed(this, 15000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            val chan = NotificationChannel(CHANNEL_ID, "MetaWork 24/7 Voice Agent", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(chan)

            val notification = buildNotification("24/7 Voice-Only Agent Active — Speak Anytime")
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

            if (BuildConfig.GEMINI_API_KEY.isNotBlank() && BuildConfig.GEMINI_API_KEY != "YOUR_GEMINI_API_KEY") {
                gemini.startRealtimeVoiceSession(scope)
            } else {
                initOnDeviceSpeechRecognizer()
                mainHandler.postDelayed(watchdogRunnable, 15000L)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error in onCreate", t)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("en", "IN")
            speakOut("MetaWork 24 by 7 voice agent is active. Tell me what to book or who to message.")
        }
    }

    private fun speakOut(message: String) {
        isSpeaking = true
        transcriptCallback?.invoke("Agent: $message")
        tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "metawork_utt")
        mainHandler.postDelayed({
            isSpeaking = false
            startContinuousOnDeviceListening()
        }, (message.length * 65L).coerceIn(2000L, 7000L))
    }

    private fun initOnDeviceSpeechRecognizer() {
        mainHandler.post {
            try {
                if (!SpeechRecognizer.isRecognitionAvailable(applicationContext)) {
                    updateStatus("VOICE_ACTIVE_24_7")
                    return@post
                }
                speechRecognizer?.destroy()
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(applicationContext).apply {
                    setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {
                            updateStatus("LISTENING_24_7 (Speak Now)")
                        }
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() {}
                        override fun onError(error: Int) {
                            if (!isDestroyed && !isSpeaking) {
                                mainHandler.postDelayed({ startContinuousOnDeviceListening() }, 400L)
                            }
                        }
                        override fun onResults(results: Bundle?) {
                            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            val spoken = matches?.firstOrNull()?.trim().orEmpty()
                            if (spoken.isNotEmpty()) {
                                transcriptCallback?.invoke("You: \"$spoken\"")
                                handleSpokenCommand(spoken)
                            } else {
                                startContinuousOnDeviceListening()
                            }
                        }
                        override fun onPartialResults(partialResults: Bundle?) {
                            val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                            if (!partial.isNullOrBlank()) {
                                transcriptCallback?.invoke("Listening: \"$partial...\"")
                            }
                        }
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                }
                startContinuousOnDeviceListening()
            } catch (t: Throwable) {
                Log.e(TAG, "SpeechRecognizer init error", t)
            }
        }
    }

    private fun startContinuousOnDeviceListening() {
        if (isDestroyed || isSpeaking) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            updateStatus("WAITING_FOR_MIC_PERMISSION")
            return
        }
        mainHandler.post {
            try {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
                }
                speechRecognizer?.startListening(intent)
            } catch (t: Throwable) {
                Log.w(TAG, "Restarting recognizer instance", t)
                initOnDeviceSpeechRecognizer()
            }
        }
    }

    private fun handleSpokenCommand(spoken: String) {
        val lower = spoken.lowercase(Locale.ROOT)
        scope.launch {
            try {
                when {
                    lower.contains("train") || lower.contains("irctc") -> {
                        speakOut("Opening IRCTC train booking for you now.")
                        taskExecutor.executeToolCall("open_app_or_booking", JSONObject().put("category", "train"))
                    }
                    lower.contains("flight") || lower.contains("makemytrip") -> {
                        speakOut("Opening MakeMyTrip flights for you now.")
                        taskExecutor.executeToolCall("open_app_or_booking", JSONObject().put("category", "flight"))
                    }
                    lower.contains("uber") || lower.contains("cab") || lower.contains("taxi") -> {
                        val dest = spoken.substringAfter("to ", "Patna Junction")
                        speakOut("Opening Uber ride to $dest.")
                        taskExecutor.executeToolCall("open_app_or_booking", JSONObject().put("category", "cab").put("destination", dest))
                    }
                    lower.contains("bus") || lower.contains("redbus") -> {
                        speakOut("Opening RedBus ticket booking.")
                        taskExecutor.executeToolCall("open_app_or_booking", JSONObject().put("category", "bus"))
                    }
                    lower.contains("movie") || lower.contains("bookmyshow") -> {
                        speakOut("Opening BookMyShow tickets.")
                        taskExecutor.executeToolCall("open_app_or_booking", JSONObject().put("category", "movie"))
                    }
                    lower.contains("whatsapp") || lower.contains("message") -> {
                        val recipient = spoken.substringAfter("to ", "").substringBefore(" saying").trim()
                        val msg = spoken.substringAfter("saying ", spoken).trim()
                        speakOut("Sending WhatsApp message to ${recipient.ifBlank { "contact" }}.")
                        taskExecutor.executeToolCall("send_whatsapp_message", JSONObject().put("recipient", recipient).put("message", msg))
                    }
                    lower.contains("call") || lower.contains("dial") -> {
                        val target = spoken.substringAfter("call ", "").substringAfter("dial ", "").trim()
                        speakOut("Calling $target now.")
                        taskExecutor.executeToolCall("make_phone_call", JSONObject().put("recipient", target))
                    }
                    lower.contains("scroll down") -> {
                        taskExecutor.executeToolCall("scroll_screen", JSONObject().put("direction", "DOWN"))
                        speakOut("Scrolled down.")
                    }
                    lower.contains("scroll up") -> {
                        taskExecutor.executeToolCall("scroll_screen", JSONObject().put("direction", "UP"))
                        speakOut("Scrolled up.")
                    }
                    lower.contains("click ") || lower.contains("tap ") -> {
                        val label = spoken.substringAfter("click ", "").substringAfter("tap ", "").replace("on ", "").trim()
                        taskExecutor.executeToolCall("click_element", JSONObject().put("label", label))
                        speakOut("Clicked $label.")
                    }
                    lower.contains("go back") -> {
                        taskExecutor.executeToolCall("navigate_system", JSONObject().put("action", "BACK"))
                        speakOut("Went back.")
                    }
                    else -> {
                        speakOut("Heard: $spoken. Say book a train, book a flight, book an Uber, send WhatsApp, or click any button.")
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Command execution error", t)
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
        startContinuousOnDeviceListening()
        return START_STICKY
    }

    override fun onDestroy() {
        isDestroyed = true
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

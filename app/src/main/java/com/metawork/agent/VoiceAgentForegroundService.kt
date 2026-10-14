package com.metawork.agent

import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineManager
import android.app.*
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class VoiceAgentForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null
    private var porcupine: PorcupineManager? = null
    private lateinit var gemini: GeminiVoiceSessionManager

    override fun onCreate() {
        super.onCreate()
        val chan = NotificationChannel("metawork_24_7", "MetaWork 24/7 Voice Agent", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(chan)
        startForeground(1001, NotificationCompat.Builder(this, "metawork_24_7").setContentTitle("MetaWork 24/7 Active").setContentText("Voice-only mode listening...").setSmallIcon(android.R.drawable.ic_btn_speak_now).setOngoing(true).build())
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MetaWork::WakeLock").apply { acquire() }
        gemini = GeminiVoiceSessionManager(applicationContext, BuildConfig.GEMINI_API_KEY, TaskActionExecutor(applicationContext)) {}
        try {
            porcupine = PorcupineManager.Builder().setAccessKey(BuildConfig.PORCUPINE_ACCESS_KEY).setKeyword(Porcupine.BuiltInKeyword.JARVIS).build(applicationContext) { gemini.startRealtimeVoiceSession(scope) }
            porcupine?.start()
        } catch (_: Exception) {
            gemini.startRealtimeVoiceSession(scope)
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
}
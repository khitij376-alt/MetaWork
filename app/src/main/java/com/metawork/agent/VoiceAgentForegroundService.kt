package com.metawork.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class VoiceAgentForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var gemini: GeminiVoiceSessionManager

    override fun onCreate() {
        super.onCreate()
        val chan = NotificationChannel("metawork_24_7", "MetaWork 24/7 Voice Agent", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(chan)
        val notification: Notification = NotificationCompat.Builder(this, "metawork_24_7")
            .setContentTitle("MetaWork 24/7 Active")
            .setContentText("Voice-only mode listening 24/7...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
        startForeground(1001, notification)
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MetaWork::WakeLock").apply { acquire() }
        gemini = GeminiVoiceSessionManager(applicationContext, BuildConfig.GEMINI_API_KEY, TaskActionExecutor(applicationContext)) {}
        gemini.startRealtimeVoiceSession(scope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        gemini.stopSession()
        wakeLock?.let { if (it.isHeld) it.release() }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
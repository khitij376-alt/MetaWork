package com.metawork.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class VoiceAgentForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "metawork_24_7_channel"
        private const val NOTIFICATION_ID = 1001
        var statusCallback: ((String) -> Unit)? = null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var gemini: GeminiVoiceSessionManager

    override fun onCreate() {
        super.onCreate()
        val chan = NotificationChannel(CHANNEL_ID, "MetaWork 24/7 Voice Agent", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(chan)

        val notification = buildNotification("Voice-only mode listening 24/7...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MetaWork::AlwaysOnWakeLock")
            .apply { acquire() }

        gemini = GeminiVoiceSessionManager(
            applicationContext,
            BuildConfig.GEMINI_API_KEY,
            TaskActionExecutor(applicationContext)
        ) { state ->
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification("Status: $state"))
            statusCallback?.invoke(state)
        }
        gemini.startRealtimeVoiceSession(scope)
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MetaWork 24/7 Active")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        gemini.stopSession()
        wakeLock?.let { if (it.isHeld) it.release() }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

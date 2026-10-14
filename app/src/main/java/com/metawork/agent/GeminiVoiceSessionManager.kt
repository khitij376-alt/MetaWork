package com.metawork.agent

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject

class GeminiVoiceSessionManager(
    private val context: Context,
    private val apiKey: String,
    private val taskExecutor: TaskActionExecutor,
    private val onStateChanged: (String) -> Unit
) {
    private val client = OkHttpClient()
    private var webSocket: WebSocket? = null
    private var audioJob: Job? = null

    fun startRealtimeVoiceSession(scope: CoroutineScope) {
        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent?key=$apiKey"
        webSocket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                onStateChanged("LISTENING_24_7")
                ws.send(JSONObject().put("setup", JSONObject().put("model", "models/gemini-2.0-flash-exp")).toString())
                streamMic(scope, ws)
            }
            override fun onMessage(ws: WebSocket, text: String) {
                val json = JSONObject(text)
                val calls = json.optJSONObject("toolCall")?.optJSONArray("functionCalls") ?: return
                val responses = JSONArray()
                for (i in 0 until calls.length()) {
                    val c = calls.getJSONObject(i)
                    val out = taskExecutor.executeToolCall(c.getString("name"), c.optJSONObject("args") ?: JSONObject())
                    responses.put(JSONObject().put("id", c.optString("id")).put("name", c.getString("name")).put("response", out))
                }
                ws.send(JSONObject().put("toolResponse", JSONObject().put("functionResponses", responses)).toString())
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun streamMic(scope: CoroutineScope, ws: WebSocket) {
        val minBuf = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2)
        rec.startRecording()
        audioJob = scope.launch(Dispatchers.IO) {
            val buf = ByteArray(3200)
            while (isActive) {
                val r = rec.read(buf, 0, buf.size)
                if (r > 0) {
                    val b64 = Base64.encodeToString(buf, 0, r, Base64.NO_WRAP)
                    ws.send(JSONObject().put("realtimeInput", JSONObject().put("mediaChunks", JSONArray().put(JSONObject().put("mimeType", "audio/pcm;rate=16000").put("data", b64)))).toString())
                }
            }
        }
    }

    fun stopSession() { audioJob?.cancel(); webSocket?.close(1000, "Done"); webSocket = null }
}
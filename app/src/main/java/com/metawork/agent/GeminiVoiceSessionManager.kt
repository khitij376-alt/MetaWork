package com.metawork.agent

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
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
import java.util.concurrent.TimeUnit

class GeminiVoiceSessionManager(
    private val context: Context,
    private val apiKey: String,
    private val taskExecutor: TaskActionExecutor,
    private val onStateChanged: (String) -> Unit
) {
    companion object {
        private const val TAG = "GeminiVoiceSession"
        private const val INPUT_SAMPLE_RATE = 16000
        private const val OUTPUT_SAMPLE_RATE = 24000
        private const val MODEL_NAME = "models/gemini-2.0-flash-exp"
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var audioRecordJob: Job? = null
    private var audioTrack: AudioTrack? = null

    fun startRealtimeVoiceSession(scope: CoroutineScope) {
        if (apiKey.isBlank() || webSocket != null) return
        val wsUrl = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent?key=$apiKey"
        val request = Request.Builder().url(wsUrl).build()

        initAudioSpeakerOutput()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "Connected to Gemini Multimodal Live API")
                onStateChanged("🟢 MUSE LIVE — Speak Command")
                sendSetupMessage(webSocket)
                startMicrophoneStreaming(scope, webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleServerPayload(webSocket, text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Gemini Live WebSocket error: ${t.message}", t)
                stopSession()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                stopSession()
            }
        })
    }

    private fun sendSetupMessage(ws: WebSocket) {
        val setupJson = JSONObject().apply {
            put("setup", JSONObject().apply {
                put("model", MODEL_NAME)
                put("generationConfig", JSONObject().apply {
                    put("responseModalities", JSONArray().put("AUDIO"))
                })
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().apply {
                        put(
                            "text",
                            "You are Muse, a wake-word activated autonomous voice agent on Android. " +
                                "Only execute actions when the user asks you after saying Muse. " +
                                "Never launch Google Search unless the user explicitly asks to search on Google."
                        )
                    }))
                })
            })
        }
        ws.send(setupJson.toString())
    }

    @SuppressLint("MissingPermission")
    private fun startMicrophoneStreaming(scope: CoroutineScope, ws: WebSocket) {
        val minBuf = AudioRecord.getMinBufferSize(
            INPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            INPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf * 2
        )

        recorder.startRecording()
        audioRecordJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(3200)
            try {
                while (isActive) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val b64 = Base64.encodeToString(buffer, 0, read, Base64.NO_WRAP)
                        val chunkMsg = JSONObject().apply {
                            put("realtimeInput", JSONObject().apply {
                                put("mediaChunks", JSONArray().put(JSONObject().apply {
                                    put("mimeType", "audio/pcm;rate=16000")
                                    put("data", b64)
                                }))
                            })
                        }
                        ws.send(chunkMsg.toString())
                    }
                }
            } finally {
                try { recorder.stop() } catch (_: Throwable) {}
                try { recorder.release() } catch (_: Throwable) {}
            }
        }
    }

    private fun initAudioSpeakerOutput() {
        val minBuf = AudioTrack.getMinBufferSize(
            OUTPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(OUTPUT_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .apply { play() }
    }

    private fun handleServerPayload(ws: WebSocket, rawJson: String) {
        val root = JSONObject(rawJson)
        val serverContent = root.optJSONObject("serverContent")
        val modelTurn = serverContent?.optJSONObject("modelTurn")
        val parts = modelTurn?.optJSONArray("parts")
        if (parts != null) {
            for (i in 0 until parts.length()) {
                val inlineData = parts.getJSONObject(i).optJSONObject("inlineData")
                if (inlineData != null && inlineData.optString("mimeType").startsWith("audio/pcm")) {
                    val pcmBytes = Base64.decode(inlineData.getString("data"), Base64.DEFAULT)
                    audioTrack?.write(pcmBytes, 0, pcmBytes.size)
                }
            }
        }

        val toolCall = root.optJSONObject("toolCall")
        val functionCalls = toolCall?.optJSONArray("functionCalls")
        if (functionCalls != null) {
            val responses = JSONArray()
            for (i in 0 until functionCalls.length()) {
                val call = functionCalls.getJSONObject(i)
                val callId = call.optString("id")
                val fnName = call.getString("name")
                val args = call.optJSONObject("args") ?: JSONObject()
                val output = taskExecutor.executeToolCall(fnName, args)
                responses.put(JSONObject().apply {
                    put("id", callId)
                    put("name", fnName)
                    put("response", output)
                })
            }
            ws.send(JSONObject().put("toolResponse", JSONObject().put("functionResponses", responses)).toString())
        }
    }

    fun stopSession() {
        audioRecordJob?.cancel()
        audioRecordJob = null
        try { webSocket?.close(1000, "Session ended") } catch (_: Throwable) {}
        webSocket = null
        try { audioTrack?.stop() } catch (_: Throwable) {}
        try { audioTrack?.release() } catch (_: Throwable) {}
        audioTrack = null
    }
}

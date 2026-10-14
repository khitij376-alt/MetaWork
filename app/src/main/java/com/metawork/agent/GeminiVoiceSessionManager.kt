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
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
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
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var webSocket: WebSocket? = null
    private var audioRecordJob: Job? = null
    private var reconnectJob: Job? = null
    private var audioTrack: AudioTrack? = null

    private val safeHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Recovered from background coroutine error: ${throwable.message}")
    }

    fun startRealtimeVoiceSession(scope: CoroutineScope) {
        if (apiKey.isBlank() || apiKey == "YOUR_GEMINI_API_KEY") {
            onStateChanged("ON_DEVICE_VOICE_24_7 (Listening...)")
            return
        }
        if (webSocket != null) return

        try {
            initAudioSpeakerOutput()
            val wsUrl = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent?key=$apiKey"
            val request = Request.Builder().url(wsUrl).build()

            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    onStateChanged("GEMINI_LIVE_24_7 (Speak Anytime)")
                    sendSetupMessage(ws)
                    startMicrophoneStreaming(scope, ws)
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    handleServerPayload(ws, text)
                }

                override fun onMessage(ws: WebSocket, bytes: ByteString) {
                    handleServerPayload(ws, bytes.utf8())
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    Log.w(TAG, "WebSocket disconnected (${t.message}), scheduling auto-reconnect...")
                    cleanupSocketOnly()
                    scheduleReconnect(scope)
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    Log.i(TAG, "WebSocket closed ($code), scheduling auto-reconnect...")
                    cleanupSocketOnly()
                    scheduleReconnect(scope)
                }
            })
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start WebSocket session", t)
            scheduleReconnect(scope)
        }
    }

    private fun scheduleReconnect(scope: CoroutineScope) {
        reconnectJob?.cancel()
        reconnectJob = scope.launch(Dispatchers.IO + safeHandler) {
            onStateChanged("ON_DEVICE_VOICE_24_7 (Reconnecting Cloud...)")
            delay(5000)
            if (isActive && webSocket == null) {
                startRealtimeVoiceSession(scope)
            }
        }
    }

    private fun sendSetupMessage(ws: WebSocket) {
        try {
            val setup = JSONObject().put("setup", JSONObject().apply {
                put("model", "models/gemini-2.0-flash-exp")
                put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("AUDIO")))
                put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text",
                    "You are MetaWork, an always-on 24/7 autonomous voice agent running natively on the user's phone. " +
                    "The user has NO keyboard and NO visual prompt UI. " +
                    "Use open_app_or_booking, read_screen, click_element, enter_text, scroll_screen, navigate_system, send_whatsapp_message, send_sms, and make_phone_call to execute tasks. " +
                    "Always confirm final payment actions verbally with the user before tapping Pay."
                ))))
                put("tools", JSONArray().put(JSONObject().put("functionDeclarations", buildToolDeclarations())))
            })
            ws.send(setup.toString())
        } catch (t: Throwable) {
            Log.e(TAG, "Error sending setup message", t)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startMicrophoneStreaming(scope: CoroutineScope, ws: WebSocket) {
        audioRecordJob?.cancel()
        audioRecordJob = scope.launch(Dispatchers.IO + safeHandler) {
            var recorder: AudioRecord? = null
            try {
                val minBuf = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (minBuf <= 0) return@launch

                recorder = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    16000,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 2
                )
                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    Log.w(TAG, "AudioRecord not initialized yet; skipping raw stream")
                    recorder.release()
                    return@launch
                }

                recorder.startRecording()
                val buffer = ByteArray(3200)
                while (isActive && webSocket != null) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val b64 = Base64.encodeToString(buffer, 0, read, Base64.NO_WRAP)
                        val msg = JSONObject().put("realtimeInput", JSONObject().put("mediaChunks", JSONArray().put(
                            JSONObject().put("mimeType", "audio/pcm;rate=16000").put("data", b64)
                        )))
                        ws.send(msg.toString())
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Mic stream error", t)
            } finally {
                try { recorder?.stop() } catch (_: Throwable) {}
                try { recorder?.release() } catch (_: Throwable) {}
            }
        }
    }

    private fun initAudioSpeakerOutput() {
        try {
            if (audioTrack != null) return
            val minBuf = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuf <= 0) return
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
                        .setSampleRate(24000)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBuf * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
                .apply { play() }
        } catch (t: Throwable) {
            Log.e(TAG, "AudioTrack init error", t)
        }
    }

    private fun handleServerPayload(ws: WebSocket, rawJson: String) {
        try {
            val root = JSONObject(rawJson)
            val parts = root.optJSONObject("serverContent")?.optJSONObject("modelTurn")?.optJSONArray("parts")
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val inlineData = parts.getJSONObject(i).optJSONObject("inlineData")
                    if (inlineData != null && inlineData.optString("mimeType").startsWith("audio/pcm")) {
                        val pcm = Base64.decode(inlineData.getString("data"), Base64.DEFAULT)
                        audioTrack?.write(pcm, 0, pcm.size)
                    }
                }
            }
            val calls = root.optJSONObject("toolCall")?.optJSONArray("functionCalls")
            if (calls != null) {
                val responses = JSONArray()
                for (i in 0 until calls.length()) {
                    val call = calls.getJSONObject(i)
                    val fn = call.getString("name")
                    onStateChanged("EXECUTING: $fn")
                    val res = taskExecutor.executeToolCall(fn, call.optJSONObject("args") ?: JSONObject())
                    responses.put(JSONObject().put("id", call.optString("id")).put("name", fn).put("response", res))
                }
                ws.send(JSONObject().put("toolResponse", JSONObject().put("functionResponses", responses)).toString())
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Payload parse error", t)
        }
    }

    private fun buildToolDeclarations(): JSONArray {
        return JSONArray().apply {
            put(toolSchema("read_screen", "Reads all visible text, buttons, prices, and input fields on the phone screen.", JSONObject()))
            put(toolSchema("click_element", "Clicks a button or UI element by visible label or coordinates.", JSONObject().apply {
                put("label", JSONObject().put("type", "STRING"))
                put("x", JSONObject().put("type", "NUMBER"))
                put("y", JSONObject().put("type", "NUMBER"))
            }))
            put(toolSchema("enter_text", "Enters text directly into a form field without using a keyboard.", JSONObject().apply {
                put("field_hint", JSONObject().put("type", "STRING"))
                put("text", JSONObject().put("type", "STRING"))
                put("click_send_after", JSONObject().put("type", "BOOLEAN"))
            }))
            put(toolSchema("scroll_screen", "Scrolls the screen UP, DOWN, LEFT, or RIGHT.", JSONObject().apply {
                put("direction", JSONObject().put("type", "STRING"))
            }))
            put(toolSchema("navigate_system", "Performs BACK, HOME, RECENTS, or NOTIFICATIONS navigation.", JSONObject().apply {
                put("action", JSONObject().put("type", "STRING"))
            }))
            put(toolSchema("open_app_or_booking", "Opens a booking workflow (flight, train, cab, bus, movie) or app.", JSONObject().apply {
                put("category", JSONObject().put("type", "STRING"))
                put("origin", JSONObject().put("type", "STRING"))
                put("destination", JSONObject().put("type", "STRING"))
                put("date", JSONObject().put("type", "STRING"))
                put("app_name", JSONObject().put("type", "STRING"))
            }))
            put(toolSchema("send_whatsapp_message", "Sends a WhatsApp message hands-free.", JSONObject().apply {
                put("recipient", JSONObject().put("type", "STRING"))
                put("message", JSONObject().put("type", "STRING"))
            }))
            put(toolSchema("send_sms", "Sends a direct SMS message.", JSONObject().apply {
                put("recipient", JSONObject().put("type", "STRING"))
                put("message", JSONObject().put("type", "STRING"))
            }))
            put(toolSchema("make_phone_call", "Places a direct phone call.", JSONObject().apply {
                put("recipient", JSONObject().put("type", "STRING"))
            }))
        }
    }

    private fun toolSchema(name: String, description: String, props: JSONObject): JSONObject {
        return JSONObject().apply {
            put("name", name)
            put("description", description)
            if (props.length() > 0) {
                put("parameters", JSONObject().put("type", "OBJECT").put("properties", props))
            }
        }
    }

    private fun cleanupSocketOnly() {
        audioRecordJob?.cancel()
        audioRecordJob = null
        try { webSocket?.close(1000, "Reconnect") } catch (_: Throwable) {}
        webSocket = null
    }

    fun stopSession() {
        reconnectJob?.cancel()
        cleanupSocketOnly()
        try { audioTrack?.stop() } catch (_: Throwable) {}
        try { audioTrack?.release() } catch (_: Throwable) {}
        audioTrack = null
    }
}

package com.metawork.agent

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
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
import java.util.concurrent.TimeUnit

class GeminiVoiceSessionManager(
    private val context: Context,
    private val apiKey: String,
    private val taskExecutor: TaskActionExecutor,
    private val onStateChanged: (String) -> Unit
) {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var audioRecordJob: Job? = null
    private var audioTrack: AudioTrack? = null

    fun startRealtimeVoiceSession(scope: CoroutineScope) {
        if (webSocket != null) return
        val wsUrl = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent?key=$apiKey"
        initAudioSpeakerOutput()
        webSocket = client.newWebSocket(Request.Builder().url(wsUrl).build(), object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                onStateChanged("LISTENING_24_7")
                sendSetupMessage(ws)
                startMicrophoneStreaming(scope, ws)
            }
            override fun onMessage(ws: WebSocket, text: String) = handleServerPayload(ws, text)
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                onStateChanged("RECONNECTING")
                stopSession()
            }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                onStateChanged("STANDBY")
                stopSession()
            }
        })
    }

    private fun sendSetupMessage(ws: WebSocket) {
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
    }

    @SuppressLint("MissingPermission")
    private fun startMicrophoneStreaming(scope: CoroutineScope, ws: WebSocket) {
        val minBuf = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2)
        recorder.startRecording()
        audioRecordJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(3200)
            try {
                while (isActive) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val b64 = Base64.encodeToString(buffer, 0, read, Base64.NO_WRAP)
                        val msg = JSONObject().put("realtimeInput", JSONObject().put("mediaChunks", JSONArray().put(
                            JSONObject().put("mimeType", "audio/pcm;rate=16000").put("data", b64)
                        )))
                        ws.send(msg.toString())
                    }
                }
            } finally {
                recorder.stop()
                recorder.release()
            }
        }
    }

    private fun initAudioSpeakerOutput() {
        val minBuf = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
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
    }

    private fun handleServerPayload(ws: WebSocket, rawJson: String) {
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

    fun stopSession() {
        audioRecordJob?.cancel()
        audioRecordJob = null
        webSocket?.close(1000, "Ended")
        webSocket = null
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
    }
}

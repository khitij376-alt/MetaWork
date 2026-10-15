package com.metawork.agent

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.telephony.SmsManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class TaskActionExecutor(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun getEffectiveGeminiKey(): String {
        val prefs = context.getSharedPreferences("muse_prefs", Context.MODE_PRIVATE)
        val saved = prefs.getString("gemini_api_key", "")?.trim().orEmpty()
        return if (saved.isNotEmpty()) saved else BuildConfig.GEMINI_API_KEY.trim()
    }

    fun saveGeminiKey(key: String) {
        context.getSharedPreferences("muse_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("gemini_api_key", key.trim())
            .apply()
    }

    private fun startActivitySafely(intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val launcherContext: Context = AgentAccessibilityService.instance
            ?: MainActivity.activeInstance
            ?: context
        return try {
            launcherContext.startActivity(intent)
            true
        } catch (_: Throwable) {
            try {
                context.startActivity(intent)
                true
            } catch (_: Throwable) {
                false
            }
        }
    }

    fun executeToolCall(toolName: String, args: JSONObject): JSONObject {
        val result = JSONObject()
        try {
            when (toolName) {
                "read_screen" -> {
                    val service = AgentAccessibilityService.instance
                    result.put("status", if (service != null) "ok" else "error")
                    result.put("screen_elements", service?.dumpScreenTreeJson() ?: "[]")
                    result.put("summary", service?.readVisibleScreenSummary() ?: "")
                }
                "click_element" -> {
                    val label = args.optString("label", "")
                    val x = args.optDouble("x", -1.0)
                    val y = args.optDouble("y", -1.0)
                    val service = AgentAccessibilityService.instance
                    val ok = when {
                        service == null -> false
                        label.isNotBlank() -> service.clickElementByLabel(label)
                        x >= 0 && y >= 0 -> service.tapCoordinates(x.toFloat(), y.toFloat())
                        else -> false
                    }
                    result.put("status", if (ok) "ok" else "failed")
                }
                "click_center_video" -> {
                    val ok = AgentAccessibilityService.instance?.clickMainContentOrCenter() ?: false
                    result.put("status", if (ok) "ok" else "failed")
                }
                "click_ordinal" -> {
                    val index = args.optInt("index", 1)
                    val ok = AgentAccessibilityService.instance?.clickByOrdinal(index) ?: false
                    result.put("status", if (ok) "ok" else "failed")
                }
                "double_tap" -> {
                    val ok = AgentAccessibilityService.instance?.doubleTapCenter() ?: false
                    result.put("status", if (ok) "ok" else "failed")
                }
                "long_press" -> {
                    val label = args.optString("label", "")
                    val ok = AgentAccessibilityService.instance?.longPressElementByLabel(label) ?: false
                    result.put("status", if (ok) "ok" else "failed")
                }
                "enter_text" -> {
                    val fieldHint = args.optString("field_hint", "")
                    val text = args.optString("text", "")
                    val submitAfter = args.optBoolean("click_send_after", false)
                    val service = AgentAccessibilityService.instance
                    val typed = service?.inputTextIntoField(fieldHint, text, submitAfter) ?: false
                    if (typed && submitAfter) {
                        mainHandler.postDelayed({
                            if (service != null) {
                                if (!service.clickWhatsAppSendButton()) {
                                    if (!service.clickElementByLabel("Send")) {
                                        service.clickElementByLabel("Search")
                                    }
                                }
                            }
                        }, 550L)
                    }
                    result.put("status", if (typed) "ok" else "failed")
                }
                "enter_password" -> {
                    val pass = args.optString("password", "")
                    val submit = args.optBoolean("submit", true)
                    val ok = AgentAccessibilityService.instance?.inputPasswordField(pass, submit) ?: false
                    result.put("status", if (ok) "ok" else "failed")
                }
                "enter_username" -> {
                    val user = args.optString("username", "")
                    val ok = AgentAccessibilityService.instance?.inputUsernameOrEmailField(user) ?: false
                    result.put("status", if (ok) "ok" else "failed")
                }
                "fill_login_form" -> {
                    val user = args.optString("username", "")
                    val pass = args.optString("password", "")
                    val ok = AgentAccessibilityService.instance?.fillLoginFormOnScreen(user, pass) ?: false
                    result.put("status", if (ok) "ok" else "failed")
                }
                "open_website" -> {
                    var url = args.optString("url", "").trim()
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        url = "https://$url"
                    }
                    val ok = startActivitySafely(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    result.put("status", if (ok) "ok" else "failed")
                }
                "in_app_search" -> {
                    val query = args.optString("query", "").trim()
                    val ok = searchInsideActiveApp(query)
                    result.put("status", if (ok) "ok" else "failed")
                }
                "scroll_screen" -> {
                    val dir = args.optString("direction", "DOWN")
                    val ok = AgentAccessibilityService.instance?.scrollScreen(dir) ?: false
                    result.put("status", if (ok) "ok" else "failed")
                }
                "auto_scroll" -> {
                    val enable = args.optBoolean("enable", true)
                    val dir = args.optString("direction", "DOWN")
                    val service = AgentAccessibilityService.instance
                    if (service != null) {
                        if (enable) service.startAutoScroll(dir) else service.stopAutoScroll()
                        result.put("status", "ok")
                    } else {
                        result.put("status", "failed")
                    }
                }
                "navigate_system" -> {
                    val action = args.optString("action", "BACK")
                    val ok = AgentAccessibilityService.instance?.navigateGlobal(action) ?: false
                    result.put("status", if (ok) "ok" else "failed")
                }
                "adjust_volume" -> {
                    val dir = args.optString("direction", "UP")
                    adjustDeviceVolume(dir)
                    result.put("status", "ok")
                }
                "set_torch" -> {
                    val enable = args.optBoolean("enable", true)
                    val ok = setTorchLight(enable)
                    result.put("status", if (ok) "ok" else "failed")
                }
                "set_alarm" -> {
                    val hour = args.optInt("hour", 7)
                    val minute = args.optInt("minute", 0)
                    val ok = setSystemAlarm(hour, minute)
                    result.put("status", if (ok) "ok" else "failed")
                }
                "set_timer" -> {
                    val seconds = args.optInt("seconds", 60)
                    val ok = setSystemTimer(seconds)
                    result.put("status", if (ok) "ok" else "failed")
                }
                "open_setting" -> {
                    val setting = args.optString("setting", "main")
                    val ok = openSystemSetting(setting)
                    result.put("status", if (ok) "ok" else "failed")
                }
                "maps_navigate" -> {
                    val dest = args.optString("destination", "")
                    val ok = launchMapsNavigation(dest)
                    result.put("status", if (ok) "ok" else "failed")
                }
                "open_app_or_booking" -> {
                    val ok = launchBookingOrApp(
                        args.optString("category", "app"),
                        args.optString("origin", ""),
                        args.optString("destination", ""),
                        args.optString("app_name", "")
                    )
                    result.put("status", if (ok) "ok" else "not_found")
                }
                "send_whatsapp_message" -> {
                    val recipient = args.optString("recipient", "").trim()
                    val message = args.optString("message", "").trim()
                    val ok = recipient.isNotBlank() && message.isNotBlank() && sendWhatsAppAutonomous(recipient, message)
                    result.put("status", if (ok) "ok" else "failed")
                }
                "send_sms" -> {
                    val recipient = args.optString("recipient", "")
                    val message = args.optString("message", "")
                    val phone = resolvePhoneNumber(recipient) ?: recipient
                    if (phone.filter { it.isDigit() }.length >= 7 && message.isNotBlank()) {
                        try {
                            context.getSystemService(SmsManager::class.java)
                                .sendTextMessage(phone, null, message, null, null)
                            result.put("status", "ok")
                        } catch (_: Throwable) {
                            val smsIntent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$phone")).apply {
                                putExtra("sms_body", message)
                            }
                            result.put("status", if (startActivitySafely(smsIntent)) "drafted" else "failed")
                        }
                    } else {
                        result.put("status", "failed")
                    }
                }
                "make_phone_call" -> {
                    val recipient = args.optString("recipient", "")
                    val phone = resolvePhoneNumber(recipient) ?: recipient.filter { it.isDigit() || it == '+' }
                    if (phone.isNotBlank()) {
                        val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$phone"))
                        val status = when {
                            startActivitySafely(callIntent) -> "ok"
                            startActivitySafely(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))) -> "dialer"
                            else -> "failed"
                        }
                        result.put("status", status)
                    } else {
                        result.put("status", "contact_not_found")
                    }
                }
                else -> {
                    result.put("status", "error")
                    result.put("message", "Unknown tool: $toolName")
                }
            }
        } catch (e: Exception) {
            result.put("status", "error")
            result.put("message", e.message ?: "Execution exception")
        }
        return result
    }

    private fun setTorchLight(enable: Boolean): Boolean {
        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cm.cameraIdList.firstOrNull() ?: return false
            cm.setTorchMode(cameraId, enable)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun setSystemAlarm(hour: Int, minute: Int): Boolean {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour.coerceIn(0, 23))
            putExtra(AlarmClock.EXTRA_MINUTES, minute.coerceIn(0, 59))
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }
        return startActivitySafely(intent)
    }

    private fun setSystemTimer(seconds: Int): Boolean {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds.coerceAtLeast(5))
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }
        return startActivitySafely(intent)
    }

    private fun openSystemSetting(setting: String): Boolean {
        val action = when (setting.lowercase()) {
            "wifi", "wi-fi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "display", "brightness" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound", "volume" -> Settings.ACTION_SOUND_SETTINGS
            "battery" -> Intent.ACTION_POWER_USAGE_SUMMARY
            "location", "gps" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "airplane" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        return startActivitySafely(Intent(action))
    }

    private fun launchMapsNavigation(destination: String): Boolean {
        val encoded = URLEncoder.encode(destination.ifBlank { "Railway Station" }, "UTF-8")
        val navIntent = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$encoded")).apply {
            setPackage("com.google.android.apps.maps")
        }
        if (startActivitySafely(navIntent)) return true
        return startActivitySafely(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=$encoded")))
    }

    private fun searchInsideActiveApp(query: String): Boolean {
        val service = AgentAccessibilityService.instance ?: return false
        if (query.isBlank()) return false

        val typedDirectly = service.inputTextIntoField("Search", query, submitImeAfter = true)
        if (typedDirectly) {
            mainHandler.postDelayed({
                service.pressImeEnterOnFocused()
            }, 700L)
            return true
        }

        val clickedSearchIcon = service.clickUniversalSearchTrigger()
        if (clickedSearchIcon) {
            mainHandler.postDelayed({
                service.inputTextIntoField("", query, submitImeAfter = true)
                mainHandler.postDelayed({
                    if (!service.pressImeEnterOnFocused()) {
                        service.clickElementByLabel("Search")
                    }
                }, 650L)
            }, 800L)
            return true
        }
        return false
    }

    private fun adjustDeviceVolume(direction: String) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            when (direction.uppercase()) {
                "UP" -> {
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                }
                "DOWN" -> {
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                }
                "MUTE" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                "UNMUTE" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
            }
        } catch (_: Throwable) {}
    }

    /**
     * Multi-Step Autonomous Gemini ReAct Thinking Loop (up to 6 steps):
     * Inspects screen UI tree (including password/editable fields), thinks about the goal, and executes actions.
     */
    fun runGeminiAutonomousScreenPlan(userGoal: String, passedKey: String = ""): String? {
        val apiKey = passedKey.ifBlank { getEffectiveGeminiKey() }
        if (apiKey.isBlank()) return null
        val service = AgentAccessibilityService.instance ?: return null

        var lastSpoken = "Completed task."
        val history = mutableListOf<String>()

        for (stepIdx in 1..10) {
            try {
                val uiTree = service.dumpScreenTreeJson()
                val prompt = "You are Muse, an intelligent, warm, autonomous Android AI Agent (like a smart human assistant). " +
                    "User request: '" + userGoal + "'. " +
                    "Previous steps taken: " + history.joinToString("; ") + ". " +
                    "Current visible screen UI elements JSON: " + uiTree + ". " +
                    "Plan one safe, observable step at a time. Understand English and Hindi/Hinglish. " +
                    "If the request names an app (for example Instagram account signup), open/use that app; NEVER send an account-creation request to Google Search. " +
                    "Use Google Search only when the user explicitly asks to search Google. Use open_url only for a site the user explicitly named. " +
                    "Never invent a person's details, password, OTP, or message text; never bypass CAPTCHA, OTP, age checks, or consent screens. " +
                    "Do not type passwords/OTPs or claim a signup/message/call succeeded unless the UI/tool confirms it; ask the user to complete private verification when needed. " +
                    "Only send messages or place calls when the user's request contains a clear recipient and an explicit action. " +
                    "For a general question, set action='done' and put the answer in 'spoken'. " +
                    "For a WhatsApp send request with recipient and exact message, use send_whatsapp; for an explicit call request use call_contact; for YouTube use youtube_play. " +
                    "Otherwise select a screen/system action that advances the stated goal; if blocked or uncertain, set action='done' and clearly explain what the user must do. " +
                    "Return ONLY valid JSON with fields: " +
                    "thought (1 short sentence of reasoning), " +
                    "action ('open_app'|'open_url'|'send_whatsapp'|'send_sms'|'call_contact'|'youtube_play'|'open_setting'|'read_screen'|'click'|'click_ordinal'|'double_tap'|'long_press'|'type'|'scroll'|'auto_scroll'|'tap_xy'|'navigate'|'done'), " +
                    "target (app name, URL, recipient, button label, ordinal '1'/'2'/'3', field hint, or scroll direction UP/DOWN/LEFT/RIGHT), " +
                    "value (exact message or non-sensitive text to type), " +
                    "x (number if tap_xy), y (number if tap_xy), " +
                    "done (boolean), " +
                    "spoken (natural, warm, concise Hinglish/English voice reply)."

                val bodyJson = JSONObject().apply {
                    put("contents", JSONArray().put(JSONObject().apply {
                        put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                    }))
                    put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
                }

                val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$apiKey"
                val req = Request.Builder()
                    .url(url)
                    .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val plan = httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return "Gemini could not plan the next step. Check the API key and network, then try again."
                    val raw = resp.body?.string().orEmpty()
                    val root = JSONObject(raw)
                    val textPart = root.optJSONArray("candidates")
                        ?.optJSONObject(0)
                        ?.optJSONObject("content")
                        ?.optJSONArray("parts")
                        ?.optJSONObject(0)
                        ?.optString("text")
                        .orEmpty()
                    if (textPart.isBlank()) null else JSONObject(textPart)
                } ?: return "Gemini returned no usable plan. Please repeat the request."

                val thought = plan.optString("thought", "")
                val act = plan.optString("action", "done")
                val target = plan.optString("target", "")
                val value = plan.optString("value", "")
                val isDone = plan.optBoolean("done", false)
                lastSpoken = plan.optString("spoken", lastSpoken)

                if (thought.isNotBlank()) {
                    VoiceAgentForegroundService.logCallback?.invoke("🧠 Thought $stepIdx: $thought")
                }
                VoiceAgentForegroundService.logCallback?.invoke("⚡ AI Action $stepIdx: $act ($target)")
                history.add("$act:$target")

                if (act == "done") return lastSpoken

                val actionSucceeded = when (act) {
                    "open_app" -> launchBookingOrApp("app", "", target, target)
                    "open_url" -> executeToolCall("open_website", JSONObject().put("url", target)).optString("status") == "ok"
                    "send_whatsapp" -> target.isNotBlank() && value.isNotBlank() && sendWhatsAppAutonomous(target, value)
                    "send_sms" -> target.isNotBlank() && value.isNotBlank() &&
                        executeToolCall("send_sms", JSONObject().put("recipient", target).put("message", value)).optString("status") == "ok"
                    "call_contact" -> {
                        val callStatus = if (target.isBlank()) "failed" else
                            executeToolCall("make_phone_call", JSONObject().put("recipient", target)).optString("status")
                        if (callStatus == "dialer") lastSpoken = "Opened the phone dialer for $target. Tap Call if Android asks you to confirm."
                        callStatus == "ok" || callStatus == "dialer"
                    }
                    "youtube_play" -> {
                        val launched = target.isNotBlank() && launchBookingOrApp("youtube", "", target, "youtube")
                        if (target.isNotBlank()) {
                            mainHandler.postDelayed({
                                val activeService = AgentAccessibilityService.instance
                                if (activeService?.getActivePackageName()?.contains("youtube") == true) {
                                    activeService.clickByOrdinal(1)
                                }
                            }, 3000L)
                        }
                        launched
                    }
                    "open_setting" -> executeToolCall("open_setting", JSONObject().put("setting", target)).optString("status") == "ok"
                    "read_screen" -> service.readVisibleScreenSummary().isNotBlank()
                    "click" -> {
                        if (service.clickElementByLabel(target)) {
                            true
                        } else {
                            val px = plan.optDouble("x", -1.0)
                            val py = plan.optDouble("y", -1.0)
                            px > 0 && py > 0 && service.tapCoordinates(px.toFloat(), py.toFloat())
                        }
                    }
                    "click_ordinal" -> service.clickByOrdinal(target.toIntOrNull() ?: 1)
                    "type" -> service.inputTextIntoField(target, value, submitImeAfter = true)
                    "scroll" -> service.scrollScreen(target.ifBlank { "DOWN" })
                    "tap_xy" -> service.tapCoordinates(plan.optDouble("x", 540.0).toFloat(), plan.optDouble("y", 960.0).toFloat())
                    "navigate" -> service.navigateGlobal(target.ifBlank { "BACK" })
                    "double_tap" -> service.doubleTapCenter()
                    "long_press" -> service.longPressElementByLabel(target)
                    "auto_scroll" -> {
                        val enable = !target.equals("stop", ignoreCase = true)
                        if (enable) service.startAutoScroll(target.ifBlank { "DOWN" }) else service.stopAutoScroll()
                        true
                    }
                    else -> false
                }

                if (!actionSucceeded) {
                    history.add("FAILED:$act:$target")
                    VoiceAgentForegroundService.logCallback?.invoke("⚠️ Action did not complete: $act ($target)")
                    if (stepIdx == 10) return "I could not complete that step. Please check the screen and try a more specific instruction."
                } else if (isDone) {
                    return lastSpoken
                }
                Thread.sleep(1400L)
            } catch (_: Throwable) {
                return "I stopped because I could not verify the next step. Please check the current screen."
            }
        }
        return "I reached the step limit before I could confirm the task was complete. Please check the screen."
    }

    private fun getWhatsAppPackage(): String? {
        val pm = context.packageManager
        val candidates = listOf("com.whatsapp", "com.whatsapp.w4b")
        for (pkg in candidates) {
            if (pm.getLaunchIntentForPackage(pkg) != null) return pkg
        }
        return null
    }

    private fun formatWhatsAppPhone(rawPhone: String): String {
        val digits = rawPhone.filter { it.isDigit() }
        return when {
            digits.length == 10 -> "91$digits"
            digits.length == 11 && digits.startsWith("0") -> "91" + digits.substring(1)
            else -> digits
        }
    }

    private fun sendWhatsAppAutonomous(recipient: String, message: String): Boolean {
        if (recipient.isBlank() || message.isBlank()) return false
        val waPkg = getWhatsAppPackage()
        val msgText = message.trim()

        if (recipient.isBlank()) {
            if (waPkg != null) {
                val launch = context.packageManager.getLaunchIntentForPackage(waPkg)
                if (launch != null) return startActivitySafely(launch)
            }
            return false
        }

        val rawPhone = resolvePhoneNumber(recipient) ?: recipient.filter { it.isDigit() }
        val formattedPhone = formatWhatsAppPhone(rawPhone)

        if (formattedPhone.length >= 10) {
            val encodedMsg = URLEncoder.encode(msgText, "UTF-8")
            val deepUri = Uri.parse("whatsapp://send?phone=$formattedPhone&text=$encodedMsg")
            val directIntent = Intent(Intent.ACTION_VIEW, deepUri).apply {
                if (waPkg != null) setPackage(waPkg)
            }
            val launched = startActivitySafely(directIntent) || startActivitySafely(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=$formattedPhone&text=$encodedMsg"))
            )
            if (launched) {
                val alreadyClickedSend = AtomicBoolean(false)
                for (delayMs in listOf(1400L, 2400L, 3500L)) {
                    mainHandler.postDelayed({
                        if (!alreadyClickedSend.get()) {
                            val clicked = AgentAccessibilityService.instance?.clickWhatsAppSendButton() ?: false
                            if (clicked) {
                                alreadyClickedSend.set(true)
                                VoiceAgentForegroundService.logCallback?.invoke("✅ Sent '$msgText' to $recipient")
                            }
                        }
                    }, delayMs)
                }
                return true
            }
        }

        if (waPkg != null) {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(waPkg)
            if (launchIntent != null && startActivitySafely(launchIntent)) {
                VoiceAgentForegroundService.logCallback?.invoke("🔍 Opening WhatsApp & verifying window for '$recipient'...")
                Thread {
                    runWhatsAppUiSearchFlow(recipient, msgText)
                }.start()
                return true
            }
        }
        return false
    }

    private fun runWhatsAppUiSearchFlow(recipient: String, msgText: String) {
        try {
            val service = AgentAccessibilityService.instance ?: return
            var waVisible = false
            for (i in 1..15) {
                Thread.sleep(300L)
                if (service.getActivePackageName().contains("whatsapp")) {
                    waVisible = true
                    break
                }
            }
            if (!waVisible) return
            Thread.sleep(400L)

            service.clickUniversalSearchTrigger()
            Thread.sleep(750L)
            service.inputTextIntoField("Search", recipient, submitImeAfter = false)

            var clickedContact = false
            for (i in 1..8) {
                Thread.sleep(350L)
                if (service.clickContactRowInList(recipient)) {
                    clickedContact = true
                    break
                }
            }
            if (!clickedContact) return
            Thread.sleep(750L)

            val typed = service.inputTextIntoField("Message", msgText, submitImeAfter = false)
            if (typed) {
                Thread.sleep(550L)
                service.clickWhatsAppSendButton()
                VoiceAgentForegroundService.logCallback?.invoke("✅ Sent '$msgText' to $recipient")
            }
        } catch (_: Throwable) {}
    }

    private fun launchBookingOrApp(category: String, origin: String, destination: String, appName: String): Boolean {
        val encodedDest = URLEncoder.encode(destination.ifBlank { "New Delhi" }, "UTF-8")

        when (category.lowercase()) {
            "cab", "ride", "uber" -> {
                val uberIntent = Intent(Intent.ACTION_VIEW, Uri.parse("uber://?action=setPickup&pickup=my_location&dropoff[formatted_address]=$encodedDest"))
                if (startActivitySafely(uberIntent)) return true
                return startActivitySafely(Intent(Intent.ACTION_VIEW, Uri.parse("https://m.uber.com/looking")))
            }
            "train", "irctc" -> {
                val irctcLaunch = context.packageManager.getLaunchIntentForPackage("cris.org.in.prs.ima")
                if (irctcLaunch != null && startActivitySafely(irctcLaunch)) return true
                return startActivitySafely(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.irctc.co.in/nget/train-search")))
            }
            "flight", "makemytrip" -> {
                val mmtLaunch = context.packageManager.getLaunchIntentForPackage("com.makemytrip")
                if (mmtLaunch != null && startActivitySafely(mmtLaunch)) return true
                return startActivitySafely(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.makemytrip.com/flights/")))
            }
            "bus", "redbus" -> {
                val rbLaunch = context.packageManager.getLaunchIntentForPackage("in.redbus.android")
                if (rbLaunch != null && startActivitySafely(rbLaunch)) return true
                return startActivitySafely(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.redbus.in/")))
            }
            "movie", "bookmyshow" -> {
                val bmsLaunch = context.packageManager.getLaunchIntentForPackage("com.bt.bms")
                if (bmsLaunch != null && startActivitySafely(bmsLaunch)) return true
                return startActivitySafely(Intent(Intent.ACTION_VIEW, Uri.parse("https://in.bookmyshow.com/")))
            }
            "youtube" -> {
                val ytQuery = destination.trim()
                if (ytQuery.isBlank()) {
                    val ytLaunch = context.packageManager.getLaunchIntentForPackage("com.google.android.youtube")
                    if (ytLaunch != null && startActivitySafely(ytLaunch)) return true
                }
                val resultsUri = Uri.parse(
                    "https://www.youtube.com/results?search_query=" + URLEncoder.encode(ytQuery.ifBlank { "music" }, "UTF-8")
                )
                val nativeYouTubeIntent = Intent(Intent.ACTION_VIEW, resultsUri).setPackage("com.google.android.youtube")
                if (startActivitySafely(nativeYouTubeIntent)) return true
                return startActivitySafely(Intent(Intent.ACTION_VIEW, resultsUri))
            }
            "search", "google" -> {
                val searchIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=$encodedDest"))
                return startActivitySafely(searchIntent)
            }
            else -> {
                val query = appName.ifBlank { destination }.lowercase().trim()
                if (query.isBlank()) return false

                if (query == "settings" || query == "setting") {
                    return startActivitySafely(Intent(Settings.ACTION_SETTINGS))
                }
                if (query == "camera" || query == "photo") {
                    return startActivitySafely(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
                }

                val knownPackages = mapOf(
                    "whatsapp" to "com.whatsapp",
                    "youtube" to "com.google.android.youtube",
                    "instagram" to "com.instagram.android",
                    "facebook" to "com.facebook.katana",
                    "chrome" to "com.android.chrome",
                    "gmail" to "com.google.android.gm",
                    "maps" to "com.google.android.apps.maps",
                    "telegram" to "org.telegram.messenger",
                    "snapchat" to "com.snapchat.android",
                    "spotify" to "com.spotify.music",
                    "paytm" to "net.one97.paytm",
                    "phonepe" to "com.phonepe.app",
                    "gpay" to "com.google.android.apps.nbu.paisa.user",
                    "google pay" to "com.google.android.apps.nbu.paisa.user",
                    "flipkart" to "com.flipkart.android",
                    "amazon" to "in.amazon.mShop.android.shopping",
                    "meesho" to "com.meesho.supply",
                    "myntra" to "com.myntra.android",
                    "zomato" to "com.application.zomato",
                    "swiggy" to "in.swiggy.android",
                    "blinkit" to "com.grofers.customerapp",
                    "zepto" to "com.zeptoconsumerapp",
                    "ola" to "com.olacabs.customer",
                    "rapido" to "com.rapido.passenger",
                    "calculator" to "com.google.android.calculator",
                    "calendar" to "com.google.android.calendar",
                    "clock" to "com.google.android.deskclock",
                    "contacts" to "com.google.android.contacts",
                    "files" to "com.google.android.apps.nbu.files",
                    "play store" to "com.android.vending"
                )
                for ((alias, pkg) in knownPackages) {
                    if (query.contains(alias)) {
                        val launch = context.packageManager.getLaunchIntentForPackage(pkg)
                        if (launch != null && startActivitySafely(launch)) return true
                    }
                }

                val pm = context.packageManager
                val apps = pm.getInstalledApplications(0)
                for (app in apps) {
                    val label = pm.getApplicationLabel(app).toString().lowercase()
                    if (label == query || label.contains(query) || app.packageName.lowercase().contains(query)) {
                        val launch = pm.getLaunchIntentForPackage(app.packageName)
                        if (launch != null && startActivitySafely(launch)) return true
                    }
                }
                return false
            }
        }
    }

    @SuppressLint("Range")
    private fun resolvePhoneNumber(nameOrNumber: String): String? {
        val cleanQuery = nameOrNumber.trim()
        if (cleanQuery.isBlank()) return null
        if (cleanQuery.any { it.isDigit() } && cleanQuery.filter { it.isDigit() }.length >= 10) {
            return cleanQuery
        }
        return try {
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
                arrayOf("%$cleanQuery%"),
                null
            ) ?: return null
            cursor.use {
                if (it.moveToFirst()) it.getString(it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)) else null
            }
        } catch (_: Throwable) {
            null
        }
    }
}

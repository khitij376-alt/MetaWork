package com.metawork.agent

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
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
                    val ok = sendWhatsAppAutonomous(recipient, message)
                    result.put("status", if (ok) "ok" else "failed")
                }
                "send_sms" -> {
                    val recipient = args.optString("recipient", "")
                    val message = args.optString("message", "")
                    val phone = resolvePhoneNumber(recipient) ?: recipient
                    if (phone.any { it.isDigit() }) {
                        try {
                            context.getSystemService(SmsManager::class.java)
                                .sendTextMessage(phone, null, message, null, null)
                        } catch (_: Throwable) {
                            val smsIntent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$phone")).apply {
                                putExtra("sms_body", message)
                            }
                            startActivitySafely(smsIntent)
                        }
                    }
                    result.put("status", "ok")
                }
                "make_phone_call" -> {
                    val recipient = args.optString("recipient", "")
                    val phone = resolvePhoneNumber(recipient) ?: recipient.filter { it.isDigit() || it == '+' }
                    if (phone.isNotBlank()) {
                        val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$phone"))
                        if (!startActivitySafely(callIntent)) {
                            startActivitySafely(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")))
                        }
                        result.put("status", "ok")
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

    private fun searchInsideActiveApp(query: String): Boolean {
        val service = AgentAccessibilityService.instance ?: return false
        if (query.isBlank()) return false

        // Step 1: Try typing directly if an editable search field is already focused/visible
        val typedDirectly = service.inputTextIntoField("Search", query, submitImeAfter = true)
        if (typedDirectly) {
            mainHandler.postDelayed({
                service.pressImeEnterOnFocused()
                service.clickElementByLabel(query)
            }, 900L)
            return true
        }

        // Step 2: Tap the app's search icon/bar, then type and submit
        val clickedSearchIcon = service.clickUniversalSearchTrigger()
        if (clickedSearchIcon) {
            mainHandler.postDelayed({
                service.inputTextIntoField("", query, submitImeAfter = true)
                mainHandler.postDelayed({
                    if (!service.pressImeEnterOnFocused()) {
                        service.clickElementByLabel("Search")
                    }
                }, 700L)
            }, 850L)
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
     * Uses Gemini 2.0 Flash to inspect the live Accessibility UI tree and execute custom screen goals.
     */
    fun runGeminiAutonomousScreenPlan(userGoal: String, apiKey: String): String? {
        if (apiKey.isBlank()) return null
        val service = AgentAccessibilityService.instance ?: return null
        return try {
            val uiTree = service.dumpScreenTreeJson()
            val prompt = "You are Muse, an Android Accessibility Screen Controller. " +
                "User command: '" + userGoal + "'. " +
                "Current visible screen UI elements JSON: " + uiTree + ". " +
                "Return ONLY a valid JSON object with fields: " +
                "action ('click'|'type'|'scroll'|'tap_xy'|'navigate'|'none'), " +
                "target (label or text or direction), " +
                "value (text to type if action is type), " +
                "x (number if tap_xy), y (number if tap_xy), " +
                "spoken (short 1-sentence confirmation)."

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

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val raw = resp.body?.string().orEmpty()
                val root = JSONObject(raw)
                val textPart = root.optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?.optJSONObject(0)
                    ?.optString("text")
                    .orEmpty()
                if (textPart.isBlank()) return null

                val plan = JSONObject(textPart)
                val act = plan.optString("action", "none")
                val target = plan.optString("target", "")
                val value = plan.optString("value", "")
                val spoken = plan.optString("spoken", "Done.")

                when (act) {
                    "click" -> service.clickElementByLabel(target)
                    "type" -> service.inputTextIntoField(target, value, submitImeAfter = true)
                    "scroll" -> service.scrollScreen(target.ifBlank { "DOWN" })
                    "tap_xy" -> service.tapCoordinates(plan.optDouble("x", 500.0).toFloat(), plan.optDouble("y", 500.0).toFloat())
                    "navigate" -> service.navigateGlobal(target.ifBlank { "BACK" })
                    else -> return null
                }
                spoken
            }
        } catch (_: Throwable) {
            null
        }
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
        val waPkg = getWhatsAppPackage()
        val msgText = message.ifBlank { "Hello" }

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
                                VoiceAgentForegroundService.logCallback?.invoke("✅ WhatsApp message sent to $recipient")
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
                VoiceAgentForegroundService.logCallback?.invoke("🔍 Searching '$recipient' inside WhatsApp UI...")
                mainHandler.postDelayed({
                    AgentAccessibilityService.instance?.clickUniversalSearchTrigger()
                }, 1200L)
                mainHandler.postDelayed({
                    AgentAccessibilityService.instance?.inputTextIntoField("", recipient, submitImeAfter = false)
                }, 2100L)
                mainHandler.postDelayed({
                    AgentAccessibilityService.instance?.clickContactRowInList(recipient)
                }, 3300L)
                mainHandler.postDelayed({
                    val service = AgentAccessibilityService.instance
                    if (service != null) {
                        val typed = service.inputTextIntoField("Message", msgText, false) || service.inputTextIntoField("", msgText, false)
                        if (typed) {
                            mainHandler.postDelayed({
                                service.clickWhatsAppSendButton()
                                VoiceAgentForegroundService.logCallback?.invoke("✅ WhatsApp message sent to $recipient")
                            }, 750L)
                        }
                    }
                }, 4500L)
                return true
            }
        }
        return false
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
                val ytIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + URLEncoder.encode(ytQuery.ifBlank { "music" }, "UTF-8")))
                return startActivitySafely(ytIntent)
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

                val knownPackages = mapOf(
                    "whatsapp" to "com.whatsapp",
                    "youtube" to "com.google.android.youtube",
                    "instagram" to "com.instagram.android",
                    "chrome" to "com.android.chrome",
                    "gmail" to "com.google.android.gm",
                    "maps" to "com.google.android.apps.maps",
                    "telegram" to "org.telegram.messenger",
                    "spotify" to "com.spotify.music",
                    "paytm" to "net.one97.paytm",
                    "phonepe" to "com.phonepe.app",
                    "gpay" to "com.google.android.apps.nbu.paisa.user",
                    "google pay" to "com.google.android.apps.nbu.paisa.user",
                    "flipkart" to "com.flipkart.android",
                    "amazon" to "in.amazon.mShop.android.shopping",
                    "zomato" to "com.application.zomato",
                    "swiggy" to "in.swiggy.android",
                    "camera" to "com.android.camera",
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

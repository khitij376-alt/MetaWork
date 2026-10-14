package com.metawork.agent

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.telephony.SmsManager
import org.json.JSONObject
import java.net.URLEncoder

class TaskActionExecutor(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun startActivitySafely(intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
                "enter_text" -> {
                    val fieldHint = args.optString("field_hint", "")
                    val text = args.optString("text", "")
                    val submitAfter = args.optBoolean("click_send_after", false)
                    val service = AgentAccessibilityService.instance
                    val typed = service?.inputTextIntoField(fieldHint, text) ?: false
                    if (typed && submitAfter) {
                        mainHandler.postDelayed({
                            service?.clickElementByLabel("Send") ?: service?.clickElementByLabel("Search")
                        }, 450L)
                    }
                    result.put("status", if (typed) "ok" else "failed")
                }
                "scroll_screen" -> {
                    val ok = AgentAccessibilityService.instance?.scrollScreen(args.optString("direction", "DOWN")) ?: false
                    result.put("status", if (ok) "ok" else "failed")
                }
                "navigate_system" -> {
                    val ok = AgentAccessibilityService.instance?.navigateGlobal(args.optString("action", "BACK")) ?: false
                    result.put("status", if (ok) "ok" else "failed")
                }
                "open_app_or_booking" -> {
                    val ok = launchBookingOrApp(
                        args.optString("category", "app"),
                        args.optString("origin", ""),
                        args.optString("destination", ""),
                        args.optString("app_name", "")
                    )
                    result.put("status", if (ok) "ok" else "failed")
                }
                "send_whatsapp_message" -> {
                    val recipient = args.optString("recipient", "").trim()
                    val message = args.optString("message", "Hello").trim()
                    val phone = resolvePhoneNumber(recipient) ?: recipient.filter { it.isDigit() || it == '+' }
                    val encodedMsg = URLEncoder.encode(message, "UTF-8")

                    val uri = if (phone.length >= 8) {
                        Uri.parse("https://api.whatsapp.com/send?phone=$phone&text=$encodedMsg")
                    } else {
                        Uri.parse("https://api.whatsapp.com/send?text=$encodedMsg")
                    }
                    val waIntent = Intent(Intent.ACTION_VIEW, uri)
                    if (!startActivitySafely(waIntent)) {
                        startActivitySafely(Intent(Intent.ACTION_VIEW, Uri.parse("https://web.whatsapp.com")))
                    }
                    mainHandler.postDelayed({
                        AgentAccessibilityService.instance?.clickElementByLabel("Send")
                    }, 1800L)
                    result.put("status", "ok")
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
                    val targetPhone = phone.ifBlank { "112" }
                    val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$targetPhone"))
                    if (!startActivitySafely(callIntent)) {
                        startActivitySafely(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$targetPhone")))
                    }
                    result.put("status", "ok")
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

    private fun launchBookingOrApp(category: String, origin: String, destination: String, appName: String): Boolean {
        val encodedDest = URLEncoder.encode(destination.ifBlank { "New Delhi" }, "UTF-8")
        val encodedOrig = URLEncoder.encode(origin.ifBlank { "Bhagalpur" }, "UTF-8")

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
                return startActivitySafely(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/travel/flights?q=Flights+from+$encodedOrig+to+$encodedDest")))
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
                val ytIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$encodedDest"))
                return startActivitySafely(ytIntent)
            }
            "search", "google" -> {
                val searchIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=$encodedDest"))
                return startActivitySafely(searchIntent)
            }
            else -> {
                val query = appName.ifBlank { destination }.lowercase()
                val pm = context.packageManager
                val apps = pm.getInstalledApplications(0)
                for (app in apps) {
                    val label = pm.getApplicationLabel(app).toString().lowercase()
                    if (label.contains(query) || app.packageName.lowercase().contains(query)) {
                        val launch = pm.getLaunchIntentForPackage(app.packageName)
                        if (launch != null && startActivitySafely(launch)) return true
                    }
                }
                return startActivitySafely(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + URLEncoder.encode(query, "UTF-8"))))
            }
        }
    }

    @SuppressLint("Range")
    private fun resolvePhoneNumber(nameOrNumber: String): String? {
        if (nameOrNumber.isBlank()) return null
        if (nameOrNumber.any { it.isDigit() } && nameOrNumber.length >= 8) return nameOrNumber
        return try {
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
                arrayOf("%$nameOrNumber%"),
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

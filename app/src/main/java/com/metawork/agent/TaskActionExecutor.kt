package com.metawork.agent

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.SmsManager
import org.json.JSONObject
import java.net.URLEncoder

class TaskActionExecutor(private val context: Context) {

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
                        Thread.sleep(350)
                        service?.clickElementByLabel("Send") ?: service?.clickElementByLabel("Search")
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
                        args.optString("destination", ""),
                        args.optString("app_name", "")
                    )
                    result.put("status", if (ok) "ok" else "failed")
                }
                "send_whatsapp_message" -> {
                    val recipient = args.optString("recipient", "")
                    val message = args.optString("message", "")
                    val phone = resolvePhoneNumber(recipient) ?: recipient.filter { it.isDigit() || it == '+' }
                    val uri = Uri.parse("https://api.whatsapp.com/send?phone=$phone&text=" + URLEncoder.encode(message, "UTF-8"))
                    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                        setPackage("com.whatsapp")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    Thread.sleep(1500)
                    AgentAccessibilityService.instance?.clickElementByLabel("Send")
                    result.put("status", "ok")
                }
                "send_sms" -> {
                    val recipient = args.optString("recipient", "")
                    val phone = resolvePhoneNumber(recipient) ?: recipient
                    context.getSystemService(SmsManager::class.java)
                        .sendTextMessage(phone, null, args.optString("message", ""), null, null)
                    result.put("status", "ok")
                }
                "make_phone_call" -> {
                    val recipient = args.optString("recipient", "")
                    val phone = resolvePhoneNumber(recipient) ?: recipient
                    context.startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$phone")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
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

    private fun launchBookingOrApp(category: String, destination: String, appName: String): Boolean {
        val encodedDest = URLEncoder.encode(destination, "UTF-8")
        val intent = when (category.lowercase()) {
            "cab", "ride", "uber" -> Intent(Intent.ACTION_VIEW, Uri.parse("uber://?action=setPickup&pickup=my_location&dropoff[formatted_address]=$encodedDest"))
            "train", "irctc" -> context.packageManager.getLaunchIntentForPackage("cris.org.in.prs.ima") ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://www.irctc.co.in/nget/train-search"))
            "flight", "makemytrip" -> context.packageManager.getLaunchIntentForPackage("com.makemytrip") ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://www.makemytrip.com/flights/"))
            "bus", "redbus" -> context.packageManager.getLaunchIntentForPackage("in.redbus.android") ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://www.redbus.in/"))
            "movie", "bookmyshow" -> context.packageManager.getLaunchIntentForPackage("com.bt.bms") ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://in.bookmyshow.com/"))
            else -> context.packageManager.getInstalledApplications(0).firstOrNull {
                context.packageManager.getApplicationLabel(it).toString().contains(appName, ignoreCase = true)
            }?.let { context.packageManager.getLaunchIntentForPackage(it.packageName) }
        } ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return true
    }

    @SuppressLint("Range")
    private fun resolvePhoneNumber(nameOrNumber: String): String? {
        if (nameOrNumber.any { it.isDigit() } && nameOrNumber.length >= 8) return nameOrNumber
        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
            arrayOf("%$nameOrNumber%"),
            null
        ) ?: return null
        cursor.use {
            if (it.moveToFirst()) return it.getString(it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER))
        }
        return null
    }
}

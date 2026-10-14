package com.metawork.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telephony.SmsManager
import org.json.JSONObject
import java.net.URLEncoder

class TaskActionExecutor(private val context: Context) {
    fun executeToolCall(toolName: String, args: JSONObject): JSONObject {
        val result = JSONObject()
        try {
            when (toolName) {
                "read_screen" -> result.put("screen_elements", AgentAccessibilityService.instance?.dumpScreenTreeJson() ?: "[]")
                "click_element" -> result.put("status", AgentAccessibilityService.instance?.clickElementByLabel(args.optString("label")) ?: false)
                "enter_text" -> result.put("status", AgentAccessibilityService.instance?.inputTextIntoField(args.optString("field_hint"), args.optString("text")) ?: false)
                "open_app_or_booking" -> {
                    val dest = URLEncoder.encode(args.optString("destination", ""), "UTF-8")
                    val uri = when (args.optString("category").lowercase()) {
                        "cab", "uber" -> Uri.parse("uber://?action=setPickup&pickup=my_location&dropoff[formatted_address]=$dest")
                        "train", "irctc" -> Uri.parse("https://www.irctc.co.in/nget/train-search")
                        "flight" -> Uri.parse("https://www.makemytrip.com/flights/")
                        else -> Uri.parse("https://www.google.com")
                    }
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    result.put("status", "ok")
                }
                "send_whatsapp_message" -> {
                    val phone = args.optString("recipient").filter { it.isDigit() || it == '+' }
                    val msg = URLEncoder.encode(args.optString("message"), "UTF-8")
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=$phone&text=$msg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    result.put("status", "ok")
                }
                "send_sms" -> {
                    context.getSystemService(SmsManager::class.java).sendTextMessage(args.optString("recipient"), null, args.optString("message"), null, null)
                    result.put("status", "ok")
                }
            }
        } catch (e: Exception) {
            result.put("error", e.message)
        }
        return result
    }
}
package com.metawork.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

class AgentAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var instance: AgentAccessibilityService? = null
            private set
    }
    override fun onServiceConnected() { super.onServiceConnected(); instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() { instance = null; super.onDestroy() }

    fun dumpScreenTreeJson(): String {
        val root = rootInActiveWindow ?: return "[]"
        val out = JSONArray()
        traverse(root, out, 0)
        return out.toString()
    }

    private fun traverse(node: AccessibilityNodeInfo?, out: JSONArray, depth: Int) {
        if (node == null || depth > 25) return
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        if ((node.isClickable || node.isEditable || text.isNotEmpty() || desc.isNotEmpty()) && node.isVisibleToUser) {
            val b = Rect(); node.getBoundsInScreen(b)
            out.put(JSONObject().put("text", text).put("desc", desc).put("clickable", node.isClickable).put("editable", node.isEditable).put("centerX", b.centerX()).put("centerY", b.centerY()))
        }
        for (i in 0 until node.childCount) traverse(node.getChild(i), out, depth + 1)
    }

    fun clickElementByLabel(query: String): Boolean {
        val target = findNode(rootInActiveWindow, query) ?: return false
        if (target.isClickable) return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val b = Rect(); target.getBoundsInScreen(b)
        return tapCoordinates(b.centerX().toFloat(), b.centerY().toFloat())
    }

    fun inputTextIntoField(hint: String, value: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findNode(root, hint, true) ?: root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun tapCoordinates(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 80)).build(), null, null)
    }

    private fun findNode(node: AccessibilityNodeInfo?, q: String, editOnly: Boolean = false): AccessibilityNodeInfo? {
        if (node == null) return null
        val match = node.text?.toString()?.contains(q, true) == true || node.contentDescription?.toString()?.contains(q, true) == true
        if (match && (!editOnly || node.isEditable)) return node
        for (i in 0 until node.childCount) findNode(node.getChild(i), q, editOnly)?.let { return it }
        return null
    }
}
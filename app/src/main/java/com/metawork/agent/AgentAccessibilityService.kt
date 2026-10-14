package com.metawork.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

class AgentAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "MuseAccessibility"
        @Volatile
        var instance: AgentAccessibilityService? = null
            private set
    }

    private var windowManager: WindowManager? = null
    private var floatingFaceView: AgentFaceView? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Muse Accessibility Service connected.")
        showFloatingAgentFaceOverlay()
    }

    private fun showFloatingAgentFaceOverlay() {
        if (floatingFaceView != null) return
        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            val sizePx = (resources.displayMetrics.density * 66).toInt()
            val params = WindowManager.LayoutParams(
                sizePx,
                sizePx,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = 20
                y = 260
            }

            val face = AgentFaceView(this, isMiniOverlay = true)
            var initialX = 0
            var initialY = 0
            var touchX = 0f
            var touchY = 0f

            face.setOnTouchListener { _: View, event: MotionEvent ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        touchX = event.rawX
                        touchY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX - (event.rawX - touchX).toInt()
                        params.y = initialY + (event.rawY - touchY).toInt()
                        try { windowManager?.updateViewLayout(face, params) } catch (_: Throwable) {}
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (abs(event.rawX - touchX) < 18 && abs(event.rawY - touchY) < 18) {
                            VoiceAgentForegroundService.instance?.wakeUpMuse("Yes? Tell me your command.")
                        }
                        true
                    }
                    else -> false
                }
            }

            windowManager?.addView(face, params)
            floatingFaceView = face
        } catch (t: Throwable) {
            Log.e(TAG, "Overlay attach error", t)
        }
    }

    fun updateFloatingFaceState(state: String) {
        floatingFaceView?.faceMode = state
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        try {
            floatingFaceView?.let { windowManager?.removeView(it) }
        } catch (_: Throwable) {}
        floatingFaceView = null
        instance = null
        super.onDestroy()
    }

    fun dumpScreenTreeJson(): String {
        val root = rootInActiveWindow ?: return "[]"
        val elements = JSONArray()
        traverseNode(root, elements, 0)
        return elements.toString()
    }

    private fun traverseNode(node: AccessibilityNodeInfo?, out: JSONArray, depth: Int) {
        if (node == null || depth > 25) return
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val viewId = node.viewIdResourceName?.substringAfterLast("/") ?: ""
        val isInteractive = node.isClickable || node.isEditable || node.isScrollable || text.isNotEmpty() || desc.isNotEmpty()

        if (isInteractive && node.isVisibleToUser) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            out.put(JSONObject().apply {
                put("text", text)
                put("desc", desc)
                put("id", viewId)
                put("clickable", node.isClickable)
                put("editable", node.isEditable)
                put("centerX", bounds.centerX())
                put("centerY", bounds.centerY())
            })
        }
        for (i in 0 until node.childCount) {
            traverseNode(node.getChild(i), out, depth + 1)
        }
    }

    fun clickElementByLabel(query: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = findMatchingNode(root, query, requireEditable = false) ?: return false
        return clickNodeOrParent(target)
    }

    fun clickNodeByViewId(viewIdSubstring: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = findNodeByViewId(root, viewIdSubstring.lowercase()) ?: return false
        return clickNodeOrParent(target)
    }

    fun clickWhatsAppSendButton(): Boolean {
        val root = rootInActiveWindow ?: return false
        val byId = findNodeByViewId(root, "com.whatsapp:id/send")
            ?: findNodeByViewId(root, "com.whatsapp.w4b:id/send")
            ?: findNodeByViewId(root, "/send")
        if (byId != null && clickNodeOrParent(byId)) return true

        val byDesc = findExactContentDesc(root, "send") ?: findExactContentDesc(root, "भेजें")
        if (byDesc != null && clickNodeOrParent(byDesc)) return true
        return false
    }

    fun clickWhatsAppSearchButton(): Boolean {
        val root = rootInActiveWindow ?: return false
        val byId = findNodeByViewId(root, "menuitem_search")
            ?: findNodeByViewId(root, "search_bar")
            ?: findNodeByViewId(root, "search")
        if (byId != null && clickNodeOrParent(byId)) return true
        return clickElementByLabel("Search") || clickElementByLabel("खोजें")
    }

    fun clickContactRowInList(contactName: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = findContactRowNode(root, contactName.lowercase().trim()) ?: return false
        return clickNodeOrParent(target)
    }

    private fun clickNodeOrParent(target: AccessibilityNodeInfo): Boolean {
        if (target.isClickable && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }
        var parent = target.parent
        while (parent != null) {
            if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            parent = parent.parent
        }
        val rect = Rect()
        target.getBoundsInScreen(rect)
        if (rect.centerX() > 0 && rect.centerY() > 0) {
            return tapCoordinates(rect.centerX().toFloat(), rect.centerY().toFloat())
        }
        return false
    }

    fun inputTextIntoField(targetFieldQuery: String, value: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = if (targetFieldQuery.isNotBlank()) {
            findMatchingNode(root, targetFieldQuery, requireEditable = true)
                ?: root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: findFirstEditableNode(root)
        } else {
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findFirstEditableNode(root)
        } ?: return false

        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun tapCoordinates(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 80L)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun scrollScreen(direction: String): Boolean {
        val metrics = resources.displayMetrics
        val cx = metrics.widthPixels / 2f
        val cy = metrics.heightPixels / 2f
        val path = Path()
        when (direction.uppercase()) {
            "DOWN" -> { path.moveTo(cx, cy * 1.4f); path.lineTo(cx, cy * 0.5f) }
            "UP" -> { path.moveTo(cx, cy * 0.5f); path.lineTo(cx, cy * 1.4f) }
            "LEFT" -> { path.moveTo(cx * 1.5f, cy); path.lineTo(cx * 0.4f, cy) }
            "RIGHT" -> { path.moveTo(cx * 0.4f, cy); path.lineTo(cx * 1.5f, cy) }
            else -> return false
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 320L)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun navigateGlobal(actionName: String): Boolean {
        return when (actionName.uppercase()) {
            "BACK" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "HOME" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "RECENTS" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "NOTIFICATIONS" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            else -> false
        }
    }

    private fun findNodeByViewId(node: AccessibilityNodeInfo?, idQuery: String): AccessibilityNodeInfo? {
        if (node == null) return null
        val id = node.viewIdResourceName?.lowercase().orEmpty()
        if (id.contains(idQuery) && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) {
            findNodeByViewId(node.getChild(i), idQuery)?.let { return it }
        }
        return null
    }

    private fun findExactContentDesc(node: AccessibilityNodeInfo?, descQuery: String): AccessibilityNodeInfo? {
        if (node == null) return null
        val desc = node.contentDescription?.toString()?.trim()?.lowercase().orEmpty()
        if (desc == descQuery && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) {
            findExactContentDesc(node.getChild(i), descQuery)?.let { return it }
        }
        return null
    }

    private fun findContactRowNode(node: AccessibilityNodeInfo?, contactQuery: String): AccessibilityNodeInfo? {
        if (node == null) return null
        val text = node.text?.toString()?.lowercase().orEmpty()
        val id = node.viewIdResourceName?.lowercase().orEmpty()
        if (!node.isEditable && node.isVisibleToUser && text.contains(contactQuery) && !id.contains("search_src_text")) {
            return node
        }
        for (i in 0 until node.childCount) {
            findContactRowNode(node.getChild(i), contactQuery)?.let { return it }
        }
        return null
    }

    private fun findMatchingNode(node: AccessibilityNodeInfo?, query: String, requireEditable: Boolean = false): AccessibilityNodeInfo? {
        if (node == null) return null
        val q = query.lowercase()
        val matches = node.text?.toString()?.lowercase()?.contains(q) == true ||
            node.contentDescription?.toString()?.lowercase()?.contains(q) == true ||
            node.viewIdResourceName?.lowercase()?.contains(q) == true
        if (matches && node.isVisibleToUser && (!requireEditable || node.isEditable)) return node
        for (i in 0 until node.childCount) {
            findMatchingNode(node.getChild(i), query, requireEditable)?.let { return it }
        }
        return null
    }

    private fun findFirstEditableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) {
            findFirstEditableNode(node.getChild(i))?.let { return it }
        }
        return null
    }
}

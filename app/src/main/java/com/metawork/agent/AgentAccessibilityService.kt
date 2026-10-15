package com.metawork.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
    private var overlayParams: WindowManager.LayoutParams? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var autoScrollActive = false
    private var autoScrollDirection = "DOWN"

    private val autoScrollRunnable = object : Runnable {
        override fun run() {
            if (!autoScrollActive) return
            scrollScreen(autoScrollDirection)
            mainHandler.postDelayed(this, 3200L)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Muse Accessibility Service connected.")
        mainHandler.post { showFloatingAgentFaceOverlay() }
    }

    private fun <T> runOnMainSync(timeoutMs: Long = 2200L, block: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return try { block() } catch (_: Throwable) { null }
        }
        var result: T? = null
        val latch = CountDownLatch(1)
        mainHandler.post {
            try {
                result = block()
            } catch (t: Throwable) {
                Log.e(TAG, "Main thread action error", t)
            } finally {
                latch.countDown()
            }
        }
        try { latch.await(timeoutMs, TimeUnit.MILLISECONDS) } catch (_: Throwable) {}
        return result
    }

    private fun showFloatingAgentFaceOverlay() {
        if (floatingFaceView != null) return
        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            val sizePx = (resources.displayMetrics.density * 64).toInt()
            val params = WindowManager.LayoutParams(
                sizePx,
                sizePx,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = 18
                y = 280
            }
            overlayParams = params

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
                        if (abs(event.rawX - touchX) < 20 && abs(event.rawY - touchY) < 20) {
                            if (autoScrollActive) {
                                stopAutoScroll()
                                VoiceAgentForegroundService.instance?.speakOut("Stopped auto scrolling.", keepAwakeAfter = false)
                            } else {
                                VoiceAgentForegroundService.instance?.wakeUpMuse(null)
                            }
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
        mainHandler.post { floatingFaceView?.faceMode = state }
    }

    fun startAutoScroll(direction: String = "DOWN") {
        autoScrollDirection = direction.uppercase()
        autoScrollActive = true
        mainHandler.removeCallbacks(autoScrollRunnable)
        mainHandler.post(autoScrollRunnable)
    }

    fun stopAutoScroll() {
        autoScrollActive = false
        mainHandler.removeCallbacks(autoScrollRunnable)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        stopAutoScroll()
    }

    override fun onDestroy() {
        stopAutoScroll()
        try {
            floatingFaceView?.let { windowManager?.removeView(it) }
        } catch (_: Throwable) {}
        floatingFaceView = null
        instance = null
        super.onDestroy()
    }

    /**
     * Always retrieves the active underlying application window (ignoring our floating overlay window).
     */
    private fun getTargetAppRoot(): AccessibilityNodeInfo? {
        try {
            val winList = windows
            if (winList != null && winList.isNotEmpty()) {
                for (w in winList) {
                    if (w.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                        val r = w.root
                        if (r != null && r.packageName?.toString() != packageName) {
                            return r
                        }
                    }
                }
                for (w in winList) {
                    if (w.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                        val r = w.root
                        if (r != null) return r
                    }
                }
                for (w in winList) {
                    val r = w.root
                    if (r != null) return r
                }
            }
        } catch (_: Throwable) {}
        return rootInActiveWindow
    }

    fun dumpScreenTreeJson(): String {
        return runOnMainSync {
            val root = getTargetAppRoot() ?: return@runOnMainSync "[]"
            val elements = JSONArray()
            traverseNode(root, elements, 0)
            elements.toString()
        } ?: "[]"
    }

    fun readVisibleScreenSummary(): String {
        return runOnMainSync {
            val root = getTargetAppRoot() ?: return@runOnMainSync "Screen is not accessible right now."
            val texts = mutableListOf<String>()
            collectVisibleTexts(root, texts, 0)
            val distinct = texts.distinct().take(12)
            if (distinct.isEmpty()) "No readable text found on this screen." else distinct.joinToString(", ")
        } ?: "Screen is not accessible."
    }

    private fun collectVisibleTexts(node: AccessibilityNodeInfo?, out: MutableList<String>, depth: Int) {
        if (node == null || depth > 22 || out.size >= 25) return
        if (node.isVisibleToUser) {
            val t = node.text?.toString()?.trim().orEmpty()
            val d = node.contentDescription?.toString()?.trim().orEmpty()
            if (t.length in 2..80) out.add(t)
            else if (d.length in 2..80) out.add(d)
        }
        for (i in 0 until node.childCount) {
            collectVisibleTexts(node.getChild(i), out, depth + 1)
        }
    }

    private fun traverseNode(node: AccessibilityNodeInfo?, out: JSONArray, depth: Int) {
        if (node == null || depth > 25 || out.length() >= 85) return
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val viewId = node.viewIdResourceName?.substringAfterLast("/") ?: ""
        val isInteractive = node.isClickable || node.isEditable || node.isScrollable || text.isNotEmpty() || desc.isNotEmpty()

        if (isInteractive && node.isVisibleToUser) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.width() > 5 && bounds.height() > 5) {
                out.put(JSONObject().apply {
                    put("text", text)
                    put("desc", desc)
                    put("id", viewId)
                    put("clickable", node.isClickable)
                    put("editable", node.isEditable)
                    put("scrollable", node.isScrollable)
                    put("centerX", bounds.centerX())
                    put("centerY", bounds.centerY())
                })
            }
        }
        for (i in 0 until node.childCount) {
            traverseNode(node.getChild(i), out, depth + 1)
        }
    }

    fun clickElementByLabel(query: String): Boolean {
        val clean = query.trim()
        if (clean.isBlank()) return false
        return runOnMainSync {
            val root = getTargetAppRoot() ?: return@runOnMainSync false
            // 1. Try exact match first, then substring match
            val target = findBestMatchingNode(root, clean, requireEditable = false) ?: return@runOnMainSync false
            clickNodeGuaranteed(target)
        } ?: false
    }

    fun clickByOrdinal(indexOneBased: Int): Boolean {
        return runOnMainSync {
            val root = getTargetAppRoot() ?: return@runOnMainSync false
            val candidates = mutableListOf<AccessibilityNodeInfo>()
            collectClickableContentNodes(root, candidates, 0)
            if (candidates.isEmpty()) return@runOnMainSync false
            val idx = (indexOneBased - 1).coerceIn(0, candidates.size - 1)
            clickNodeGuaranteed(candidates[idx])
        } ?: false
    }

    private fun collectClickableContentNodes(node: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>, depth: Int) {
        if (node == null || depth > 22 || out.size >= 30) return
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val hasLabel = !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()
        if (node.isVisibleToUser && bounds.centerY() > 210 && bounds.height() > 28 && (node.isClickable || hasLabel)) {
            if (hasLabel) {
                out.add(node)
            }
        }
        for (i in 0 until node.childCount) {
            collectClickableContentNodes(node.getChild(i), out, depth + 1)
        }
    }

    fun longPressElementByLabel(query: String): Boolean {
        return runOnMainSync {
            val root = getTargetAppRoot()
            val target = if (root != null && query.isNotBlank()) findBestMatchingNode(root, query, false) else null
            if (target != null) {
                if (target.isLongClickable && target.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
                    return@runOnMainSync true
                }
                val rect = Rect()
                target.getBoundsInScreen(rect)
                return@runOnMainSync dispatchGestureOnMain(buildLongPressGesture(rect.centerX().toFloat(), rect.centerY().toFloat()))
            }
            val m = resources.displayMetrics
            dispatchGestureOnMain(buildLongPressGesture(m.widthPixels / 2f, m.heightPixels / 2f))
        } ?: false
    }

    fun doubleTapCenter(): Boolean {
        return runOnMainSync {
            val m = resources.displayMetrics
            val cx = m.widthPixels / 2f
            val cy = m.heightPixels / 2f
            val path1 = Path().apply { moveTo(cx, cy) }
            val path2 = Path().apply { moveTo(cx, cy) }
            val stroke1 = GestureDescription.StrokeDescription(path1, 0L, 45L)
            val stroke2 = GestureDescription.StrokeDescription(path2, 110L, 45L)
            val gesture = GestureDescription.Builder().addStroke(stroke1).addStroke(stroke2).build()
            dispatchGestureOnMain(gesture)
        } ?: false
    }

    private fun buildLongPressGesture(x: Float, y: Float): GestureDescription {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 650L)
        return GestureDescription.Builder().addStroke(stroke).build()
    }

    fun clickWhatsAppSendButton(): Boolean {
        return runOnMainSync {
            val root = getTargetAppRoot() ?: return@runOnMainSync false
            val byId = findNodeByViewId(root, "com.whatsapp:id/send")
                ?: findNodeByViewId(root, "com.whatsapp.w4b:id/send")
                ?: findNodeByViewId(root, "/send")
            if (byId != null && clickNodeGuaranteed(byId)) return@runOnMainSync true

            val byDesc = findExactContentDesc(root, "send") ?: findExactContentDesc(root, "भेजें")
            if (byDesc != null && clickNodeGuaranteed(byDesc)) return@runOnMainSync true
            false
        } ?: false
    }

    fun clickUniversalSearchTrigger(): Boolean {
        return runOnMainSync {
            val root = getTargetAppRoot() ?: return@runOnMainSync false
            val searchIds = listOf(
                "menuitem_search",
                "search_box",
                "search_bar",
                "search_src_text",
                "search_edit_text",
                "search_button",
                "action_search",
                "search"
            )
            for (id in searchIds) {
                val node = findNodeByViewId(root, id)
                if (node != null && clickNodeGuaranteed(node)) return@runOnMainSync true
            }
            val byLabel = findBestMatchingNode(root, "Search", false) ?: findBestMatchingNode(root, "खोजें", false)
            if (byLabel != null && clickNodeGuaranteed(byLabel)) return@runOnMainSync true
            false
        } ?: false
    }

    fun clickContactRowInList(contactName: String): Boolean {
        return runOnMainSync {
            val root = getTargetAppRoot() ?: return@runOnMainSync false
            val target = findContactRowNode(root, contactName.lowercase().trim()) ?: return@runOnMainSync false
            clickNodeGuaranteed(target)
        } ?: false
    }

    /**
     * Guaranteed Click Engine:
     * 1. Tries ACTION_CLICK on the node itself
     * 2. Walks up parent hierarchy and tries ACTION_CLICK on clickable parent
     * 3. Always falls back to hardware coordinate tap (dispatchGesture) at the node's screen center!
     */
    private fun clickNodeGuaranteed(target: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        target.getBoundsInScreen(rect)

        if (target.isClickable && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }
        var parent = target.parent
        var hops = 0
        while (parent != null && hops < 8) {
            if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            parent = parent.parent
            hops++
        }
        val m = resources.displayMetrics
        val tx = rect.centerX().toFloat().coerceIn(10f, m.widthPixels - 10f)
        val ty = rect.centerY().toFloat().coerceIn(10f, m.heightPixels - 10f)
        if (rect.width() > 0 && rect.height() > 0) {
            return dispatchTapAt(tx, ty)
        }
        return false
    }

    fun inputTextIntoField(targetFieldQuery: String, value: String, submitImeAfter: Boolean = false): Boolean {
        return runOnMainSync {
            val root = getTargetAppRoot() ?: return@runOnMainSync false
            val node = if (targetFieldQuery.isNotBlank()) {
                findBestMatchingNode(root, targetFieldQuery, requireEditable = true)
                    ?: root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    ?: findFirstEditableNode(root)
            } else {
                root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findFirstEditableNode(root)
            } ?: return@runOnMainSync false

            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
            }
            val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (ok && submitImeAfter) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                }
            }
            ok
        } ?: false
    }

    fun pressImeEnterOnFocused(): Boolean {
        return runOnMainSync {
            val root = getTargetAppRoot() ?: return@runOnMainSync false
            val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findFirstEditableNode(root) ?: return@runOnMainSync false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            } else {
                false
            }
        } ?: false
    }

    fun tapCoordinates(x: Float, y: Float): Boolean {
        return runOnMainSync { dispatchTapAt(x, y) } ?: false
    }

    private fun dispatchTapAt(x: Float, y: Float): Boolean {
        val m = resources.displayMetrics
        val safeX = x.coerceIn(10f, m.widthPixels - 10f)
        val safeY = y.coerceIn(10f, m.heightPixels - 10f)
        val path = Path().apply { moveTo(safeX, safeY) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 65L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGestureOnMain(gesture)
    }

    private fun dispatchGestureOnMain(gesture: GestureDescription): Boolean {
        return try {
            // Temporarily make overlay untouchable during gesture so it never intercepts touches
            val face = floatingFaceView
            val params = overlayParams
            if (face != null && params != null) {
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                try { windowManager?.updateViewLayout(face, params) } catch (_: Throwable) {}
            }
            val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    restoreOverlayTouch()
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    restoreOverlayTouch()
                }
            }, mainHandler)
            if (!dispatched) restoreOverlayTouch()
            dispatched
        } catch (t: Throwable) {
            restoreOverlayTouch()
            false
        }
    }

    private fun restoreOverlayTouch() {
        mainHandler.post {
            val face = floatingFaceView
            val params = overlayParams
            if (face != null && params != null) {
                params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                try { windowManager?.updateViewLayout(face, params) } catch (_: Throwable) {}
            }
        }
    }

    /**
     * Guaranteed Dual Scroll Engine:
     * 1. Dispatches a smooth hardware swipe gesture on the Main Thread
     * 2. Also triggers ACTION_SCROLL_FORWARD / ACTION_SCROLL_BACKWARD on scrollable container if needed
     */
    fun scrollScreen(direction: String): Boolean {
        return runOnMainSync {
            val dir = direction.uppercase()
            val metrics = resources.displayMetrics
            val w = metrics.widthPixels.toFloat()
            val h = metrics.heightPixels.toFloat()
            val cx = w * 0.5f
            val path = Path()
            when (dir) {
                "DOWN", "NEXT" -> {
                    path.moveTo(cx, h * 0.78f)
                    path.lineTo(cx, h * 0.20f)
                }
                "UP", "PREVIOUS" -> {
                    path.moveTo(cx, h * 0.22f)
                    path.lineTo(cx, h * 0.78f)
                }
                "LEFT" -> {
                    path.moveTo(w * 0.85f, h * 0.5f)
                    path.lineTo(w * 0.15f, h * 0.5f)
                }
                "RIGHT" -> {
                    path.moveTo(w * 0.15f, h * 0.5f)
                    path.lineTo(w * 0.85f, h * 0.5f)
                }
                else -> return@runOnMainSync false
            }
            val stroke = GestureDescription.StrokeDescription(path, 0L, 300L)
            val gestureOk = dispatchGestureOnMain(GestureDescription.Builder().addStroke(stroke).build())

            // Also trigger accessibility scroll action as fallback
            if (!gestureOk) {
                val root = getTargetAppRoot()
                val scrollable = findFirstScrollableNode(root)
                if (scrollable != null) {
                    val action = if (dir == "UP" || dir == "PREVIOUS" || dir == "RIGHT") {
                        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    } else {
                        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    }
                    return@runOnMainSync scrollable.performAction(action)
                }
            }
            gestureOk
        } ?: false
    }

    private fun findFirstScrollableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isScrollable && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) {
            findFirstScrollableNode(node.getChild(i))?.let { return it }
        }
        return null
    }

    fun navigateGlobal(actionName: String): Boolean {
        return runOnMainSync {
            when (actionName.uppercase()) {
                "BACK" -> performGlobalAction(GLOBAL_ACTION_BACK)
                "HOME" -> performGlobalAction(GLOBAL_ACTION_HOME)
                "RECENTS" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                "NOTIFICATIONS" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
                "QUICK_SETTINGS" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
                "SCREENSHOT" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT) else false
                "LOCK_SCREEN" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) else false
                else -> false
            }
        } ?: false
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

    private fun findBestMatchingNode(node: AccessibilityNodeInfo?, query: String, requireEditable: Boolean = false): AccessibilityNodeInfo? {
        val q = query.lowercase().trim()
        if (q.isEmpty()) return null
        // Pass 1: Exact text or contentDescription match
        findNodeRecursive(node, q, requireEditable, exactOnly = true)?.let { return it }
        // Pass 2: Substring match
        return findNodeRecursive(node, q, requireEditable, exactOnly = false)
    }

    private fun findNodeRecursive(
        node: AccessibilityNodeInfo?,
        q: String,
        requireEditable: Boolean,
        exactOnly: Boolean
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        val t = node.text?.toString()?.trim()?.lowercase().orEmpty()
        val d = node.contentDescription?.toString()?.trim()?.lowercase().orEmpty()
        val id = node.viewIdResourceName?.substringAfterLast("/")?.lowercase().orEmpty()

        val matches = if (exactOnly) {
            t == q || d == q || id == q
        } else {
            (t.isNotEmpty() && (t.contains(q) || q.contains(t) && t.length >= 3)) ||
                (d.isNotEmpty() && (d.contains(q) || q.contains(d) && d.length >= 3)) ||
                (id.isNotEmpty() && id.contains(q))
        }
        if (matches && node.isVisibleToUser && (!requireEditable || node.isEditable)) {
            return node
        }
        for (i in 0 until node.childCount) {
            findNodeRecursive(node.getChild(i), q, requireEditable, exactOnly)?.let { return it }
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

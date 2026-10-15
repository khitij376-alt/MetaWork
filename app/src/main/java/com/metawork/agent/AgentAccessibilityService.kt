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

    private fun <T> runOnMainSync(timeoutMs: Long = 2500L, block: () -> T): T? {
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

    /**
     * Analyzes the current screen ("Thinking" step) to report active app, editable fields, and key buttons.
     */
    fun analyzeScreenContextSummary(): String {
        return runOnMainSync {
            val root = getTargetAppRoot() ?: return@runOnMainSync "No active screen window."
            val pkg = root.packageName?.toString()?.substringAfterLast(".") ?: "app"
            val buttons = mutableListOf<String>()
            val inputs = mutableListOf<String>()
            var hasPassword = false
            scanScreenFeatures(root, buttons, inputs, 0) { hasPassword = true }
            val btnPreview = buttons.distinct().take(6).joinToString(", ")
            "App=$pkg | Inputs=${inputs.size} (Password=$hasPassword) | Buttons=[$btnPreview]"
        } ?: "Screen unavailable"
    }

    private fun scanScreenFeatures(
        node: AccessibilityNodeInfo?,
        buttons: MutableList<String>,
        inputs: MutableList<String>,
        depth: Int,
        onPasswordFound: () -> Unit
    ) {
        if (node == null || depth > 24) return
        if (node.isVisibleToUser) {
            val label = (node.text?.toString() ?: node.contentDescription?.toString() ?: "").trim()
            if (node.isPassword) onPasswordFound()
            if (node.isEditable) {
                inputs.add(label.ifBlank { "input" })
            } else if ((node.isClickable || label.isNotEmpty()) && label.length in 2..35) {
                buttons.add(label)
            }
        }
        for (i in 0 until node.childCount) {
            scanScreenFeatures(node.getChild(i), buttons, inputs, depth + 1, onPasswordFound)
        }
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
        if (node == null || depth > 26 || out.length() >= 90) return
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) node.hintText?.toString()?.trim().orEmpty() else ""
        val viewId = node.viewIdResourceName?.substringAfterLast("/") ?: ""
        val isInteractive = node.isClickable || node.isEditable || node.isScrollable || node.isPassword || text.isNotEmpty() || desc.isNotEmpty() || hint.isNotEmpty()

        if (isInteractive && node.isVisibleToUser) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.width() > 5 && bounds.height() > 5) {
                out.put(JSONObject().apply {
                    put("text", text)
                    put("desc", desc)
                    put("hint", hint)
                    put("id", viewId)
                    put("clickable", node.isClickable)
                    put("editable", node.isEditable)
                    put("password", node.isPassword)
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

    /**
     * Smart Click Engine with Semantic Synonym Expansion + Spatial Regions:
     * Understands "login"/"sign in", "play this video", "top right", "center", "bottom", or any button label.
     */
    fun clickElementByLabel(query: String): Boolean {
        val clean = query.trim().lowercase()
        if (clean.isBlank()) return false

        // Check spatial click requests first ("center", "top right", "bottom right", "top left", "bottom left")
        val m = resources.displayMetrics
        val w = m.widthPixels.toFloat()
        val h = m.heightPixels.toFloat()
        when {
            clean == "center" || clean == "middle" || clean == "beech me" || clean == "this video" || clean == "video" -> {
                return clickMainContentOrCenter()
            }
            clean.contains("top right") || clean.contains("upar right") -> return tapCoordinates(w * 0.90f, h * 0.09f)
            clean.contains("top left") || clean.contains("upar left") -> return tapCoordinates(w * 0.10f, h * 0.09f)
            clean.contains("bottom right") || clean.contains("niche right") -> return tapCoordinates(w * 0.88f, h * 0.92f)
            clean.contains("bottom left") || clean.contains("niche left") -> return tapCoordinates(w * 0.12f, h * 0.92f)
        }

        // Expand semantic synonyms (e.g. if user says "login", also search "sign in", "log in", "submit", "continue")
        val candidateQueries = buildSemanticCandidates(clean)

        return runOnMainSync {
            val root = getTargetAppRoot() ?: return@runOnMainSync false
            for (cand in candidateQueries) {
                val exact = findNodeRecursive(root, cand, requireEditable = false, exactOnly = true)
                if (exact != null && clickNodeGuaranteed(exact)) {
                    VoiceAgentForegroundService.logCallback?.invoke("🎯 Clicked exact match: '$cand'")
                    return@runOnMainSync true
                }
            }
            for (cand in candidateQueries) {
                val partial = findNodeRecursive(root, cand, requireEditable = false, exactOnly = false)
                if (partial != null && clickNodeGuaranteed(partial)) {
                    VoiceAgentForegroundService.logCallback?.invoke("🎯 Clicked match: '$cand'")
                    return@runOnMainSync true
                }
            }
            false
        } ?: false
    }

    private fun buildSemanticCandidates(q: String): List<String> {
        val list = mutableListOf(q)
        when {
            q == "login" || q == "log in" || q == "sign in" || q == "signin" -> {
                list.addAll(listOf("log in", "login", "sign in", "continue", "submit", "next", "proceed"))
            }
            q == "submit" || q == "continue" || q == "next" -> {
                list.addAll(listOf("submit", "continue", "next", "proceed", "done", "confirm", "ok"))
            }
            q == "search" || q == "khojo" -> {
                list.addAll(listOf("search", "find", "go"))
            }
            q == "send" || q == "bhejo" -> {
                list.addAll(listOf("send", "भेजें", "post", "submit"))
            }
        }
        return list.distinct()
    }

    /**
     * Clicks the main visible video/article card in the middle of the screen (used for "play this video" / "open this").
     */
    private fun isHomeLauncherPackage(pkg: String): Boolean {
        val p = pkg.lowercase()
        return p.contains("launcher") || p.contains("miui.home") || p.contains("oneui.home") || p.contains("trebuchet") || p.contains("lawnchair") || p.contains("oppo.launcher") || p.contains("vivo.launcher") || p.contains("realme")
    }

    fun getActivePackageName(): String {
        return runOnMainSync {
            getTargetAppRoot()?.packageName?.toString()?.lowercase().orEmpty()
        } ?: ""
    }

    fun isHomeLauncherActive(): Boolean {
        val pkg = getActivePackageName()
        return isHomeLauncherPackage(pkg)
    }

    fun clickMainContentOrCenter(): Boolean {
        return runOnMainSync {
            val root = getTargetAppRoot()
            val pkg = root?.packageName?.toString().orEmpty()
            // Never fire blind center/widget taps on the phone's Home Screen Launcher (prevents tapping Weather widget!)
            if (isHomeLauncherPackage(pkg)) {
                VoiceAgentForegroundService.logCallback?.invoke("🛡️ Guard: Ignored blind video click on Home Screen Launcher")
                return@runOnMainSync false
            }
            val m = resources.displayMetrics
            val w = m.widthPixels
            val h = m.heightPixels
            if (root != null) {
                val candidates = mutableListOf<AccessibilityNodeInfo>()
                collectClickableContentNodes(root, candidates, 0)
                // Pick the content item closest to the vertical center of the screen (between 22% and 75% height)
                var centerCandidate: AccessibilityNodeInfo? = null
                var bestDist = Int.MAX_VALUE
                val targetY = (h * 0.45f).toInt()
                for (cand in candidates) {
                    val r = Rect()
                    cand.getBoundsInScreen(r)
                    val dist = abs(r.centerY() - targetY)
                    if (dist < bestDist) {
                        bestDist = dist
                        centerCandidate = cand
                    }
                }
                if (centerCandidate != null) {
                    val r = Rect()
                    centerCandidate.getBoundsInScreen(r)
                    if (r.centerY() in (h / 6)..(h * 5 / 6)) {
                        VoiceAgentForegroundService.logCallback?.invoke("🎬 Tapping visible video/item at (${r.centerX()}, ${r.centerY()})")
                        return@runOnMainSync clickNodeGuaranteed(centerCandidate)
                    }
                }
            }
            // Fallback: tap center of screen directly
            dispatchTapAt(w * 0.5f, h * 0.42f)
        } ?: false
    }

    fun clickByOrdinal(indexOneBased: Int): Boolean {
        return runOnMainSync {
            val root = getTargetAppRoot() ?: return@runOnMainSync false
            val pkg = root.packageName?.toString().orEmpty()
            if (isHomeLauncherPackage(pkg)) {
                VoiceAgentForegroundService.logCallback?.invoke("🛡️ Guard: Blocked ordinal click on Home Screen")
                return@runOnMainSync false
            }
            val candidates = mutableListOf<AccessibilityNodeInfo>()
            collectClickableContentNodes(root, candidates, 0)
            if (candidates.isEmpty()) {
                val m = resources.displayMetrics
                return@runOnMainSync dispatchTapAt(m.widthPixels * 0.5f, m.heightPixels * 0.38f)
            }
            val idx = (indexOneBased - 1).coerceIn(0, candidates.size - 1)
            clickNodeGuaranteed(candidates[idx])
        } ?: false
    }

    private fun collectClickableContentNodes(node: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>, depth: Int) {
        if (node == null || depth > 24 || out.size >= 35) return
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val hasLabel = !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()
        val id = node.viewIdResourceName?.lowercase().orEmpty()
        val isVideoOrCard = id.contains("video") || id.contains("thumbnail") || id.contains("player") || id.contains("card") || id.contains("item") || id.contains("row")
        if (node.isVisibleToUser && bounds.centerY() > 200 && bounds.height() > 32 && (node.isClickable || hasLabel || isVideoOrCard)) {
            if (hasLabel || isVideoOrCard) {
                out.add(node)
            }
        }
        for (i in 0 until node.childCount) {
            collectClickableContentNodes(node.getChild(i), out, depth + 1)
        }
    }

    /**
     * Enters text into a Password field on any app or website (detects node.isPassword or hint/text "password"/"pin").
     */
    fun inputPasswordField(passwordValue: String, submitLoginAfter: Boolean = false): Boolean {
        return runOnMainSync {
            val root = getTargetAppRoot() ?: return@runOnMainSync false
            val editables = mutableListOf<AccessibilityNodeInfo>()
            collectAllEditableNodes(root, editables, 0)

            val passNode = editables.firstOrNull { it.isPassword }
                ?: findBestMatchingNode(root, "password", requireEditable = true)
                ?: findBestMatchingNode(root, "pin", requireEditable = true)
                ?: findBestMatchingNode(root, "passcode", requireEditable = true)
                ?: if (editables.size >= 2) editables[1] else editables.firstOrNull()

            if (passNode == null) return@runOnMainSync false

            val ok = setNodeTextGuaranteed(passNode, passwordValue, submitImeAfter = submitLoginAfter)
            if (ok && submitLoginAfter) {
                mainHandler.postDelayed({
                    clickElementByLabel("login")
                }, 650L)
            }
            ok
        } ?: false
    }

    /**
     * Enters text into a Username / Email / Phone field on any app or website.
     */
    fun inputUsernameOrEmailField(usernameValue: String): Boolean {
        val normalized = normalizeSpokenEmailOrUsername(usernameValue)
        return runOnMainSync {
            val root = getTargetAppRoot() ?: return@runOnMainSync false
            val editables = mutableListOf<AccessibilityNodeInfo>()
            collectAllEditableNodes(root, editables, 0)

            val userNode = findBestMatchingNode(root, "email", requireEditable = true)
                ?: findBestMatchingNode(root, "user", requireEditable = true)
                ?: findBestMatchingNode(root, "phone", requireEditable = true)
                ?: findBestMatchingNode(root, "mobile", requireEditable = true)
                ?: findBestMatchingNode(root, "login", requireEditable = true)
                ?: editables.firstOrNull { !it.isPassword }
                ?: editables.firstOrNull()

            if (userNode == null) return@runOnMainSync false
            setNodeTextGuaranteed(userNode, normalized, submitImeAfter = false)
        } ?: false
    }

    /**
     * Complete Website / App Login Automation: fills both Username/Email and Password, then clicks Login/Sign In!
     */
    fun fillLoginFormOnScreen(username: String, password: String): Boolean {
        val userOk = if (username.isNotBlank()) inputUsernameOrEmailField(username) else true
        mainHandler.postDelayed({
            if (password.isNotBlank()) {
                inputPasswordField(password, submitLoginAfter = true)
            } else {
                clickElementByLabel("login")
            }
        }, 700L)
        return userOk
    }

    private fun normalizeSpokenEmailOrUsername(raw: String): String {
        var s = raw.trim()
        if (s.lowercase().contains("at the rate") || s.lowercase().contains("gmail") || s.lowercase().contains(" dot ")) {
            s = s.replace(Regex("[ ]*at the rate[ ]*", RegexOption.IGNORE_CASE), "@")
                .replace(Regex("[ ]*at[ ]+gmail", RegexOption.IGNORE_CASE), "@gmail")
                .replace(Regex("[ ]*dot[ ]*", RegexOption.IGNORE_CASE), ".")
                .replace(" ", "")
        }
        return s
    }

    private fun collectAllEditableNodes(node: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>, depth: Int) {
        if (node == null || depth > 25) return
        val cls = node.className?.toString()?.lowercase().orEmpty()
        if (node.isVisibleToUser && (node.isEditable || node.isPassword || cls.contains("edittext"))) {
            out.add(node)
        }
        for (i in 0 until node.childCount) {
            collectAllEditableNodes(node.getChild(i), out, depth + 1)
        }
    }

    private fun setNodeTextGuaranteed(node: AccessibilityNodeInfo, value: String, submitImeAfter: Boolean): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (rect.width() > 0 && rect.height() > 0) {
            dispatchTapAt(rect.centerX().toFloat(), rect.centerY().toFloat())
        }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (ok && submitImeAfter && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
        return ok
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
            val editables = mutableListOf<AccessibilityNodeInfo>()
            collectAllEditableNodes(root, editables, 0)

            val node = if (targetFieldQuery.equals("Message", ignoreCase = true)) {
                findNodeByViewId(root, "com.whatsapp:id/entry")
                    ?: findNodeByViewId(root, "com.whatsapp.w4b:id/entry")
                    ?: findBestMatchingNode(root, "Message", requireEditable = true)
                    ?: editables.lastOrNull()
            } else if (targetFieldQuery.isNotBlank()) {
                findBestMatchingNode(root, targetFieldQuery, requireEditable = true)
                    ?: root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    ?: editables.firstOrNull()
            } else {
                root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: editables.firstOrNull()
            } ?: return@runOnMainSync false

            setNodeTextGuaranteed(node, value, submitImeAfter)
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
        findNodeRecursive(node, q, requireEditable, exactOnly = true)?.let { return it }
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
        val h = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) node.hintText?.toString()?.trim()?.lowercase().orEmpty() else ""
        val id = node.viewIdResourceName?.substringAfterLast("/")?.lowercase().orEmpty()
        val cls = node.className?.toString()?.lowercase().orEmpty()
        val isEdit = node.isEditable || node.isPassword || cls.contains("edittext")

        val matches = if (exactOnly) {
            t == q || d == q || h == q || id == q
        } else {
            q.length >= 2 && (
                (t.isNotEmpty() && t.contains(q)) ||
                (d.isNotEmpty() && d.contains(q)) ||
                (h.isNotEmpty() && h.contains(q)) ||
                (id.isNotEmpty() && id == q)
            )
        }
        if (matches && node.isVisibleToUser && (!requireEditable || isEdit)) {
            return node
        }
        for (i in 0 until node.childCount) {
            findNodeRecursive(node.getChild(i), q, requireEditable, exactOnly)?.let { return it }
        }
        return null
    }

    private fun findFirstEditableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        val cls = node.className?.toString()?.lowercase().orEmpty()
        if ((node.isEditable || cls.contains("edittext")) && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) {
            findFirstEditableNode(node.getChild(i))?.let { return it }
        }
        return null
    }
}

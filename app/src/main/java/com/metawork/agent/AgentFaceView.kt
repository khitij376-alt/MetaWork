package com.metawork.agent

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Animated Face for "Muse" Voice Agent (used on Main Dashboard & Floating Screen Overlay).
 * States:
 * - "SLEEP"     -> Indigo/Slate resting face with sleeping eyes (- -) waiting for "Muse" wake word
 * - "MUTED"     -> Dim Gray face when microphone is muted (Tap face to wake)
 * - "LISTENING" -> Glowing Cyan awake face actively listening for user command
 * - "SPEAKING"  -> Emerald Green animated waveform mouth while Muse speaks
 * - "EXECUTING" -> Amber Gold radar rings while Muse clicks/types inside apps
 */
class AgentFaceView(context: Context, private val isMiniOverlay: Boolean = false) : View(context) {

    var faceMode: String = "SLEEP"
        set(value) {
            field = value
            postInvalidateOnAnimation()
        }

    var audioLevel: Float = 0.2f
        set(value) {
            field = value.coerceIn(0.05f, 1.0f)
            postInvalidateOnAnimation()
        }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#0F172A")
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = if (isMiniOverlay) 5f else 8f
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = if (isMiniOverlay) 8f else 18f
    }
    private val featurePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val arcRect = RectF()
    private val eyeRect = RectF()
    private val mouthBarRect = RectF()
    private var tick = 0f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        tick += 0.07f

        val cx = width / 2f
        val cy = height / 2f
        val radius = (minOf(width, height) / 2f) * 0.84f

        val primaryColor = when (faceMode) {
            "SPEAKING" -> Color.parseColor("#10B981")
            "EXECUTING" -> Color.parseColor("#F59E0B")
            "LISTENING" -> Color.parseColor("#38BDF8")
            "MUTED" -> Color.parseColor("#475569")
            else -> Color.parseColor("#818CF8") // SLEEP (Waiting for "Muse")
        }

        val isSleepingOrMuted = (faceMode == "SLEEP" || faceMode == "MUTED")
        val pulse = if (isSleepingOrMuted) 1f + 0.015f * sin(tick) else 1f + 0.045f * sin(tick * 2.2f)

        glowPaint.color = primaryColor
        glowPaint.alpha = if (isSleepingOrMuted) 30 else 65
        canvas.drawCircle(cx, cy, radius * pulse, glowPaint)
        canvas.drawCircle(cx, cy, radius, bgPaint)

        ringPaint.color = primaryColor
        ringPaint.alpha = if (isSleepingOrMuted) 130 else 230
        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius)
        val speed = if (isSleepingOrMuted) 6f else 26f
        val startAngle = (tick * speed) % 360f
        canvas.drawArc(arcRect, startAngle, 110f, false, ringPaint)
        canvas.drawArc(arcRect, startAngle + 180f, 110f, false, ringPaint)

        featurePaint.color = primaryColor
        val isBlinking = isSleepingOrMuted || (sin(tick * 0.45f) > 0.96f)
        val eyeOffsetX = radius * 0.36f
        val eyeCenterY = cy - radius * 0.16f
        val eyeW = radius * 0.22f
        val eyeH = if (isBlinking) radius * 0.035f else radius * 0.16f

        eyeRect.set(cx - eyeOffsetX - eyeW / 2f, eyeCenterY - eyeH / 2f, cx - eyeOffsetX + eyeW / 2f, eyeCenterY + eyeH / 2f)
        canvas.drawRoundRect(eyeRect, eyeW / 2f, eyeW / 2f, featurePaint)

        eyeRect.set(cx + eyeOffsetX - eyeW / 2f, eyeCenterY - eyeH / 2f, cx + eyeOffsetX + eyeW / 2f, eyeCenterY + eyeH / 2f)
        canvas.drawRoundRect(eyeRect, eyeW / 2f, eyeW / 2f, featurePaint)

        if (!isBlinking && !isMiniOverlay) {
            featurePaint.color = Color.WHITE
            featurePaint.alpha = 210
            val lookShift = sin(tick * 0.7f) * (eyeW * 0.12f)
            canvas.drawCircle(cx - eyeOffsetX + lookShift, eyeCenterY - eyeH * 0.15f, eyeW * 0.14f, featurePaint)
            canvas.drawCircle(cx + eyeOffsetX + lookShift, eyeCenterY - eyeH * 0.15f, eyeW * 0.14f, featurePaint)
            featurePaint.color = primaryColor
            featurePaint.alpha = 255
        }

        val mouthY = cy + radius * 0.34f
        val barCount = if (isMiniOverlay) 3 else 5
        val totalMouthW = radius * 0.72f
        val barW = totalMouthW / (barCount * 1.8f)
        val gap = barW * 0.8f
        val startX = cx - ((barCount * barW + (barCount - 1) * gap) / 2f)

        val activeWave = when (faceMode) {
            "SPEAKING" -> 0.35f + 0.65f * abs(sin(tick * 4.5f))
            "EXECUTING" -> 0.45f * abs(cos(tick * 5.0f))
            "LISTENING" -> 0.22f + (audioLevel * 0.75f) * abs(sin(tick * 3.2f))
            else -> 0.08f
        }

        for (i in 0 until barCount) {
            val phase = sin(tick * 4f + i * 1.1f)
            val centerBoost = if (i == barCount / 2) 1.35f else 0.85f
            val barH = (radius * 0.28f * activeWave * centerBoost * (0.5f + 0.5f * abs(phase))).coerceAtLeast(radius * 0.045f)
            val left = startX + i * (barW + gap)
            mouthBarRect.set(left, mouthY - barH / 2f, left + barW, mouthY + barH / 2f)
            canvas.drawRoundRect(mouthBarRect, barW / 2f, barW / 2f, featurePaint)
        }

        postInvalidateDelayed(36L)
    }
}

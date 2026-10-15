package com.metawork.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.Base64
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class AgentFaceView(context: Context, private val isMiniOverlay: Boolean = false) : View(context) {

    companion object {
        private const val BATMAN_AVATAR_B64 = "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"
        @Volatile
        private var cachedBatmanBitmap: Bitmap? = null

        private fun getBatmanBitmap(): Bitmap? {
            cachedBatmanBitmap?.let { return it }
            return try {
                val bytes = Base64.decode(BATMAN_AVATAR_B64, Base64.DEFAULT)
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                cachedBatmanBitmap = bmp
                bmp
            } catch (_: Throwable) {
                null
            }
        }
    }

    var faceMode: String = "STANDBY"
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
        color = Color.parseColor("#05070C")
    }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = if (isMiniOverlay) 5f else 8f
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = if (isMiniOverlay) 9f else 18f
    }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val clipPath = Path()
    private val srcRect = Rect()
    private val dstRect = RectF()
    private val arcRect = RectF()
    private val barRect = RectF()
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
            "WAKE_WORD" -> Color.parseColor("#A855F7")
            else -> Color.parseColor("#E2E8F0") // Dark Knight Silver-White Glow in Standby
        }

        val isResting = (faceMode == "STANDBY" || faceMode == "SLEEP" || faceMode == "MUTED")
        val pulse = if (isResting) 1f + 0.015f * sin(tick) else 1f + 0.055f * sin(tick * 2.2f)

        glowPaint.color = primaryColor
        glowPaint.alpha = if (isResting) 45 else 95
        canvas.drawCircle(cx, cy, radius * pulse, glowPaint)
        canvas.drawCircle(cx, cy, radius, bgPaint)

        // Draw circular clipped Batman Logo artwork
        val bmp = getBatmanBitmap()
        if (bmp != null) {
            val saveCount = canvas.save()
            clipPath.reset()
            clipPath.addCircle(cx, cy, radius * 0.96f, Path.Direction.CW)
            canvas.clipPath(clipPath)
            srcRect.set(0, 0, bmp.width, bmp.height)
            dstRect.set(cx - radius, cy - radius, cx + radius, cy + radius)
            canvas.drawBitmap(bmp, srcRect, dstRect, bitmapPaint)
            canvas.restoreToCount(saveCount)
        }

        // Rotating tactical HUD ring around the Batman logo
        ringPaint.color = primaryColor
        ringPaint.alpha = if (isResting) 165 else 245
        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius)
        val speed = if (isResting) 5f else 28f
        val startAngle = (tick * speed) % 360f
        canvas.drawArc(arcRect, startAngle, 115f, false, ringPaint)
        canvas.drawArc(arcRect, startAngle + 180f, 115f, false, ringPaint)

        // Voice equalizer bars at bottom of avatar when actively listening/speaking/executing
        if (!isResting) {
            wavePaint.color = primaryColor
            wavePaint.alpha = 230
            val mouthY = cy + radius * 0.58f
            val barCount = if (isMiniOverlay) 3 else 5
            val totalW = radius * 0.68f
            val barW = totalW / (barCount * 1.8f)
            val gap = barW * 0.8f
            val startX = cx - ((barCount * barW + (barCount - 1) * gap) / 2f)

            val activeWave = when (faceMode) {
                "SPEAKING" -> 0.42f + 0.58f * abs(sin(tick * 4.5f))
                "EXECUTING" -> 0.50f * abs(cos(tick * 5.2f))
                "LISTENING" -> 0.30f + (audioLevel * 0.70f) * abs(sin(tick * 3.4f))
                else -> 0.18f
            }

            for (i in 0 until barCount) {
                val phase = sin(tick * 4f + i * 1.1f)
                val centerBoost = if (i == barCount / 2) 1.35f else 0.85f
                val barH = (radius * 0.24f * activeWave * centerBoost * (0.5f + 0.5f * abs(phase))).coerceAtLeast(radius * 0.045f)
                val left = startX + i * (barW + gap)
                barRect.set(left, mouthY - barH / 2f, left + barW, mouthY + barH / 2f)
                canvas.drawRoundRect(barRect, barW / 2f, barW / 2f, wavePaint)
            }
        }

        postInvalidateDelayed(36L)
    }
}

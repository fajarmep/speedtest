package com.fajar.speedtest

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

class SpeedGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val maxPoints = 40
    private val points = ArrayList<Float>()
    private var peakValue = 10f

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = ContextCompat.getColor(context, R.color.primary)
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(40, 237, 57, 105) // Translucent primary #ED3969
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = ContextCompat.getColor(context, R.color.border_stroke)
    }

    private val path = Path()
    private val fillPath = Path()

    fun addPoint(speedMbps: Float) {
        if (points.size >= maxPoints) {
            points.removeAt(0)
        }
        points.add(speedMbps)
        if (speedMbps > peakValue) {
            peakValue = speedMbps * 1.15f
        }
        postInvalidate()
    }

    fun clear() {
        points.clear()
        peakValue = 10f
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // Draw horizontal grid lines (0%, 50%, 100%)
        canvas.drawLine(0f, 0f, w, 0f, gridPaint)
        canvas.drawLine(0f, h / 2f, w, h / 2f, gridPaint)
        canvas.drawLine(0f, h - 1f, w, h - 1f, gridPaint)

        if (points.size < 2) return

        path.reset()
        fillPath.reset()

        val stepX = w / (maxPoints - 1)
        val startX = (maxPoints - points.size) * stepX

        val firstY = h - ((points[0] / peakValue) * (h - 8f)) - 4f
        path.moveTo(startX, firstY)
        fillPath.moveTo(startX, h)
        fillPath.lineTo(startX, firstY)

        for (i in 1 until points.size) {
            val px = startX + (i * stepX)
            val py = h - ((points[i] / peakValue) * (h - 8f)) - 4f
            path.lineTo(px, py)
            fillPath.lineTo(px, py)
        }

        val lastX = startX + ((points.size - 1) * stepX)
        fillPath.lineTo(lastX, h)
        fillPath.close()

        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(path, linePaint)
    }
}

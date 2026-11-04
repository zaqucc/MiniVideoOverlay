package com.personal.minivideooverlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class RangeTrimTimelineView(context: Context) : View(context) {
    var onRangeChanged: ((Long, Long) -> Unit)? = null
    private var durationMs = 0L
    private var startMs = 0L
    private var endMs = 0L
    private var thumbnails: List<Bitmap> = emptyList()
    private var draggingStart = false
    private var draggingEnd = false
    private val density = resources.displayMetrics.density
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF26364B.toInt() }
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB8000000.toInt() }
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x224CC9F0
        style = Paint.Style.FILL
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4CC9F0.toInt() }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        strokeWidth = 2f * density
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4CC9F0.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFB8C7D8.toInt()
        textSize = 10f * density
    }
    private val frameRect = RectF()
    private val handleWidth get() = 12f * density
    private val trackTop get() = 12f * density
    private val trackBottom get() = height - 20f * density

    fun setVideoDuration(duration: Long) {
        durationMs = duration.coerceAtLeast(0L)
        if (endMs <= 0L || endMs > durationMs) endMs = durationMs
        startMs = startMs.coerceIn(0L, endMs)
        invalidate()
    }

    fun setRange(start: Long, end: Long) {
        startMs = start.coerceIn(0L, durationMs)
        endMs = end.coerceIn(startMs, durationMs)
        invalidate()
    }

    fun setThumbnails(frames: List<Bitmap>) {
        thumbnails = frames
        invalidate()
    }

    fun releaseThumbnails() {
        thumbnails.forEach { if (!it.isRecycled) it.recycle() }
        thumbnails = emptyList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = paddingLeft.toFloat() + handleWidth / 2f
        val right = width - paddingRight.toFloat() - handleWidth / 2f
        val top = trackTop
        val bottom = max(top + 1f, trackBottom)
        val track = RectF(left, top, right, bottom)
        canvas.drawRoundRect(track, 7f * density, 7f * density, trackPaint)

        if (thumbnails.isNotEmpty() && durationMs > 0L) {
            val cellWidth = track.width() / thumbnails.size
            thumbnails.forEachIndexed { index, bitmap ->
                if (!bitmap.isRecycled) {
                    frameRect.set(left + index * cellWidth, top, left + (index + 1) * cellWidth + 1f, bottom)
                    canvas.drawBitmap(bitmap, null, frameRect, null)
                }
            }
        } else {
            for (i in 0..8) {
                val x = left + track.width() * i / 8f
                canvas.drawLine(x, top + 4f * density, x, bottom - 4f * density, trackPaint)
            }
        }

        val startX = xFor(startMs, left, right)
        val endX = xFor(endMs, left, right)
        canvas.drawRect(left, top, startX, bottom, shadePaint)
        canvas.drawRect(endX, top, right, bottom, shadePaint)
        canvas.drawRect(startX, top, endX, bottom, selectionPaint)
        canvas.drawRect(startX, top, endX, bottom, borderPaint)
        canvas.drawRoundRect(RectF(startX - handleWidth / 2f, top - 3f * density, startX + handleWidth / 2f, bottom + 3f * density), 4f * density, 4f * density, handlePaint)
        canvas.drawRoundRect(RectF(endX - handleWidth / 2f, top - 3f * density, endX + handleWidth / 2f, bottom + 3f * density), 4f * density, 4f * density, handlePaint)
        canvas.drawLine(startX, top, startX, bottom, linePaint)
        canvas.drawLine(endX, top, endX, bottom, linePaint)
        canvas.drawText("GESER PEGANGAN UNTUK MEMOTONG", left, height - 4f * density, textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (durationMs <= 0L || width <= 0) return false
        val left = paddingLeft.toFloat() + handleWidth / 2f
        val right = width - paddingRight.toFloat() - handleWidth / 2f
        if (right <= left) return false
        val startX = xFor(startMs, left, right)
        val endX = xFor(endMs, left, right)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val touch = event.x
                draggingStart = abs(touch - startX) <= abs(touch - endX)
                draggingEnd = !draggingStart
                if (abs(touch - if (draggingStart) startX else endX) > 28f * density) {
                    draggingStart = touch < (startX + endX) / 2f
                    draggingEnd = !draggingStart
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                updateFromTouch(event.x, left, right)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                updateFromTouch(event.x, left, right)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                updateFromTouch(event.x, left, right)
                draggingStart = false
                draggingEnd = false
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun updateFromTouch(x: Float, left: Float, right: Float) {
        val fraction = ((x - left) / (right - left)).coerceIn(0f, 1f)
        val value = (durationMs * fraction).toLong()
        val minGap = min(500L, durationMs)
        if (draggingStart) startMs = value.coerceAtMost((endMs - minGap).coerceAtLeast(0L))
        if (draggingEnd) endMs = value.coerceAtLeast((startMs + minGap).coerceAtMost(durationMs))
        invalidate()
        onRangeChanged?.invoke(startMs, endMs)
    }

    private fun xFor(time: Long, left: Float, right: Float): Float {
        if (durationMs <= 0L) return left
        return left + (right - left) * (time.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    }
}

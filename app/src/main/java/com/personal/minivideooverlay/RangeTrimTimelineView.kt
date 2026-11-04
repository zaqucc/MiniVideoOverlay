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

/**
 * Filmstrip timeline with independent in/out handles and a movable preview playhead.
 * Dragging the playhead never changes the trim range.
 */
class RangeTrimTimelineView(context: Context) : View(context) {
    var onRangeChanged: ((Long, Long) -> Unit)? = null
    var onPlayheadChanged: ((Long) -> Unit)? = null
    var onScrubStarted: (() -> Unit)? = null
    var onScrubFinished: (() -> Unit)? = null

    private var durationMs = 0L
    private var startMs = 0L
    private var endMs = 0L
    private var playheadMs = 0L
    private var thumbnails: List<Bitmap> = emptyList()
    private var draggingStart = false
    private var draggingEnd = false
    private var draggingPlayhead = false
    private val density = resources.displayMetrics.density
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF303134.toInt() }
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB8000000.toInt() }
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1FFFFFFF }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val playheadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val playheadDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val frameRect = RectF()
    private val handleWidth get() = 10f * density
    private val trackTop get() = 15f * density
    private val trackBottom get() = height - 23f * density
    private val touchSlop get() = 30f * density

    fun setVideoDuration(duration: Long) {
        durationMs = duration.coerceAtLeast(0L)
        if (endMs <= 0L || endMs > durationMs) endMs = durationMs
        startMs = startMs.coerceIn(0L, endMs)
        playheadMs = playheadMs.coerceIn(0L, durationMs)
        invalidate()
    }

    fun setRange(start: Long, end: Long) {
        startMs = start.coerceIn(0L, durationMs)
        endMs = end.coerceIn(startMs, durationMs)
        playheadMs = playheadMs.coerceIn(0L, durationMs)
        invalidate()
    }

    fun setPlayhead(position: Long) {
        playheadMs = position.coerceIn(0L, durationMs)
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
        if (right <= left) return
        val top = trackTop
        val bottom = max(top + 1f, trackBottom)
        val track = RectF(left, top, right, bottom)
        canvas.drawRoundRect(track, 5f * density, 5f * density, trackPaint)

        if (thumbnails.isNotEmpty() && durationMs > 0L) {
            val cellWidth = track.width() / thumbnails.size
            thumbnails.forEachIndexed { index, bitmap ->
                if (!bitmap.isRecycled) {
                    frameRect.set(left + index * cellWidth, top, left + (index + 1) * cellWidth + 1f, bottom)
                    canvas.drawBitmap(bitmap, null, frameRect, null)
                }
            }
        }

        val startX = xFor(startMs, left, right)
        val endX = xFor(endMs, left, right)
        val playX = xFor(playheadMs, left, right)
        canvas.drawRect(left, top, startX, bottom, shadePaint)
        canvas.drawRect(endX, top, right, bottom, shadePaint)
        canvas.drawRect(startX, top, endX, bottom, selectionPaint)

        // Google Photos-inspired trim grips: clean white vertical rails with rounded caps.
        canvas.drawRoundRect(RectF(startX - handleWidth / 2f, top - 4f * density, startX + handleWidth / 2f, bottom + 4f * density), 4f * density, 4f * density, handlePaint)
        canvas.drawRoundRect(RectF(endX - handleWidth / 2f, top - 4f * density, endX + handleWidth / 2f, bottom + 4f * density), 4f * density, 4f * density, handlePaint)

        // Independent playhead, always visible above the selected filmstrip.
        canvas.drawLine(playX, top - 8f * density, playX, bottom + 8f * density, playheadPaint)
        canvas.drawCircle(playX, top - 8f * density, 4f * density, playheadDotPaint)

        val hint = "GESER FILMSTRIP / GARIS PUTIH UNTUK MELIHAT FRAME"
        val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFBDBDBD.toInt()
            textSize = 9f * density
        }
        canvas.drawText(hint, left, height - 4f * density, hintPaint)
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
                val nearStart = abs(touch - startX) <= touchSlop
                val nearEnd = abs(touch - endX) <= touchSlop
                draggingStart = nearStart && (!nearEnd || abs(touch - startX) <= abs(touch - endX))
                draggingEnd = nearEnd && !draggingStart
                draggingPlayhead = !draggingStart && !draggingEnd
                parent?.requestDisallowInterceptTouchEvent(true)
                if (draggingPlayhead) {
                    onScrubStarted?.invoke()
                    updatePlayhead(event.x, left, right)
                } else {
                    updateRangeFromTouch(event.x, left, right)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (draggingPlayhead) updatePlayhead(event.x, left, right)
                else updateRangeFromTouch(event.x, left, right)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (draggingPlayhead) {
                    updatePlayhead(event.x, left, right)
                    onScrubFinished?.invoke()
                } else updateRangeFromTouch(event.x, left, right)
                draggingStart = false
                draggingEnd = false
                draggingPlayhead = false
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

    private fun updateRangeFromTouch(x: Float, left: Float, right: Float) {
        val value = timeFor(x, left, right)
        val minGap = min(500L, durationMs)
        if (draggingStart) startMs = value.coerceAtMost((endMs - minGap).coerceAtLeast(0L))
        if (draggingEnd) endMs = value.coerceAtLeast((startMs + minGap).coerceAtMost(durationMs))
        invalidate()
        onRangeChanged?.invoke(startMs, endMs)
    }

    private fun updatePlayhead(x: Float, left: Float, right: Float) {
        playheadMs = timeFor(x, left, right)
        invalidate()
        onPlayheadChanged?.invoke(playheadMs)
    }

    private fun timeFor(x: Float, left: Float, right: Float): Long =
        (durationMs * ((x - left) / (right - left)).coerceIn(0f, 1f)).toLong()

    private fun xFor(time: Long, left: Float, right: Float): Float {
        if (durationMs <= 0L) return left
        return left + (right - left) * (time.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    }
}

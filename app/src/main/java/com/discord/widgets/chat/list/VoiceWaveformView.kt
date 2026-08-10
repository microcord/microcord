package com.discord.widgets.chat.list

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import com.discord.R
import com.discord.utilities.color.ColorCompat

internal class VoiceWaveformView(
    context: Context,
    private val recording: Boolean = false,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val activeColor = ColorCompat.getThemedColor(context, R.attr.colorInteractiveActive)
    private val inactiveColor = ColorCompat.getThemedColor(
        context,
        if (recording) R.attr.colorTextNormal else R.attr.colorInteractiveMuted,
    )
    private var sourceSamples = emptyList<Int>()
    private var samples = emptyList<Int>()
    private var progress = 0f
    var onSeek: ((Float) -> Unit)? = null

    fun setSamples(values: List<Int>) {
        sourceSamples = values
        updateSamples()
        invalidate()
    }

    fun addSample(sample: Int) {
        sourceSamples = (sourceSamples + sample.coerceIn(0, 255)).takeLast((width / dp(6)).coerceAtLeast(1))
        samples = sourceSamples
        invalidate()
    }

    fun setProgress(value: Float) {
        progress = value.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val step = dp(6).toFloat()
        paint.strokeWidth = dp(2).toFloat()
        samples.forEachIndexed { index, sample ->
            paint.color = if (!recording && index.toFloat() / samples.size <= progress) activeColor else inactiveColor
            val barHeight = maxOf(dp(2).toFloat(), height * sample / 255f)
            val x = step * (index + 0.5f)
            canvas.drawLine(x, (height - barHeight) / 2, x, (height + barHeight) / 2, paint)
        }
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        updateSamples()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val seek = onSeek ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> parent?.requestDisallowInterceptTouchEvent(true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        if (event.actionMasked != MotionEvent.ACTION_CANCEL) {
            seek((event.x / width.coerceAtLeast(1)).coerceIn(0f, 1f))
        }
        if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun updateSamples() {
        val maxSamples = (width / dp(6)).coerceAtLeast(1)
        samples = if (recording) sourceSamples.takeLast(maxSamples) else downsampleVoiceWaveform(sourceSamples, maxSamples)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

internal fun downsampleVoiceWaveform(samples: List<Int>, maxSamples: Int): List<Int> {
    if (samples.size <= maxSamples || maxSamples <= 0) return samples
    return List(maxSamples) { index ->
        val start = index * samples.size / maxSamples
        val end = (index + 1) * samples.size / maxSamples
        samples.subList(start, maxOf(start + 1, end)).average().toInt()
    }
}

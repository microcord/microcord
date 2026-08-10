package com.discord.widgets.chat.list

import android.content.Context
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.util.AttributeSet
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageButton
import com.discord.R
import com.discord.utilities.color.ColorCompat

class AudioAttachmentPlayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    private val play = AppCompatImageButton(context)
    private val seek = SeekBar(context)
    private val time = TextView(context)
    private var knownDurationMs = 0L
    private val playback = AudioPlaybackController(
        context,
        "AudioAttachment",
        POSITION_POLL_INTERVAL_MS,
        ::setPlaying,
        ::renderPosition,
        ::setDuration,
    )

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), 0, dp(8), dp(8))
        play.apply {
            setImageResource(R.drawable.ic_play_arrow_24dp)
            imageTintList = android.content.res.ColorStateList.valueOf(
                ColorCompat.getThemedColor(context, R.attr.colorInteractiveActive),
            )
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            contentDescription = context.getString(R.string.play)
            setOnClickListener { togglePlayback() }
        }
        addView(play, LayoutParams(dp(40), dp(40)))
        seek.max = 1
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) playback.seekTo(progress.toLong())
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        })
        addView(seek, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        time.apply {
            setTextColor(ColorCompat.getThemedColor(context, R.attr.colorTextMuted))
            includeFontPadding = false
            gravity = Gravity.CENTER
        }
        addView(time, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = dp(4) })
    }

    fun configure(url: String, durationSecs: Double? = null) {
        playback.configure(url)
        knownDurationMs = 0L
        seek.max = 1
        setDuration(((durationSecs ?: 0.0) * 1000).toLong())
        renderPosition(0L)
    }

    fun releasePlayer() {
        playback.release()
    }

    override fun onDetachedFromWindow() {
        releasePlayer()
        super.onDetachedFromWindow()
    }

    private fun togglePlayback() = playback.toggle()

    private fun setPlaying(value: Boolean) {
        play.setImageResource(if (value) R.drawable.ic_pause_24dp else R.drawable.ic_play_arrow_24dp)
        play.contentDescription = context.getString(if (value) R.string.pause else R.string.play)
    }

    private fun renderPosition(positionMs: Long) {
        seek.progress = positionMs.coerceIn(0L, knownDurationMs).toInt()
        time.text = "${DateUtils.formatElapsedTime(positionMs / 1000)} / ${DateUtils.formatElapsedTime(knownDurationMs / 1000)}"
    }

    private fun setDuration(durationMs: Long) {
        if (durationMs <= 0) return
        knownDurationMs = durationMs.coerceAtMost(Int.MAX_VALUE.toLong())
        seek.max = knownDurationMs.toInt()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val POSITION_POLL_INTERVAL_MS = 100L
    }
}

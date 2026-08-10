package com.discord.widgets.chat.list

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.format.DateUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageButton
import androidx.core.view.setPadding
import com.discord.R
import com.discord.api.message.attachment.MessageAttachment
import com.discord.utilities.color.ColorCompat
import okio.ByteString.Companion.decodeBase64

class VoiceMessageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    private val play = AppCompatImageButton(context)
    private val waveform = VoiceWaveformView(context)
    private val duration = TextView(context)
    private val speed = TextView(context)
    private var attachment: MessageAttachment? = null
    private var speedIndex = 1
    private var displayedSeconds = -1
    private val playback = AudioPlaybackController(
        context,
        "VoiceMessage",
        VOICE_POSITION_POLL_INTERVAL_MS,
        ::setPlaying,
        ::updatePosition,
    )

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8))
        background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(ColorCompat.getThemedColor(context, R.attr.colorBackgroundTertiary))
            setStroke(dp(1), ColorCompat.getThemedColor(context, R.attr.colorInteractiveMuted))
        }
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
        addView(waveform, LayoutParams(0, dp(36), 1f).apply { marginStart = dp(4) })
        duration.setTextColor(ColorCompat.getThemedColor(context, R.attr.colorTextMuted))
        addView(duration, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        speed.apply {
            setTextColor(ColorCompat.getThemedColor(context, R.attr.colorInteractiveActive))
            setPadding(dp(8))
            text = "1x"
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            includeFontPadding = false
            setOnClickListener { cycleSpeed() }
        }
        addView(speed, LayoutParams(dp(56), dp(40)))
        waveform.onSeek = { fraction ->
            val total = ((attachment?.durationSecs ?: 0.0) * 1000).toLong()
            playback.seekTo((total * fraction).toLong())
        }
    }

    fun configure(attachment: MessageAttachment) {
        if (this.attachment?.url != attachment.url) releasePlayer()
        this.attachment = attachment
        playback.configure(attachment.url)
        val samples = attachment.waveform?.decodeBase64()?.toByteArray()?.map { it.toInt() and 0xff }.orEmpty()
        waveform.setSamples(samples)
        displayedSeconds = -1
        updateDuration(attachment.durationSecs ?: 0.0)
    }

    fun releasePlayer() {
        playback.release()
        waveform.setProgress(0f)
        play.setImageResource(R.drawable.ic_play_arrow_24dp)
        play.contentDescription = context.getString(R.string.play)
    }

    override fun onDetachedFromWindow() {
        releasePlayer()
        super.onDetachedFromWindow()
    }

    private fun togglePlayback() {
        playback.setSpeed(PLAYBACK_RATES[speedIndex])
        playback.toggle()
    }

    private fun updatePosition(positionMs: Long) {
        val total = ((attachment?.durationSecs ?: 0.0) * 1000).toFloat()
        waveform.setProgress(if (total > 0) positionMs / total else 0f)
        updateDuration(positionMs / 1000.0)
    }

    private fun setPlaying(value: Boolean) {
        play.setImageResource(if (value) R.drawable.ic_pause_24dp else R.drawable.ic_play_arrow_24dp)
        play.contentDescription = context.getString(if (value) R.string.pause else R.string.play)
    }

    private fun cycleSpeed() {
        speedIndex = (speedIndex + 1) % PLAYBACK_RATES.size
        val value = PLAYBACK_RATES[speedIndex]
        playback.setSpeed(value)
        speed.text = if (value % 1f == 0f) "${value.toInt()}x" else "${value}x"
    }

    private fun updateDuration(seconds: Double) {
        val wholeSeconds = seconds.toInt().coerceAtLeast(0)
        if (wholeSeconds == displayedSeconds) return
        displayedSeconds = wholeSeconds
        duration.text = DateUtils.formatElapsedTime(wholeSeconds.toLong())
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val VOICE_POSITION_POLL_INTERVAL_MS = 50L
        val PLAYBACK_RATES = floatArrayOf(0.75f, 1f, 1.5f, 2f)
    }
}

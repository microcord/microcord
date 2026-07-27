package com.discord.views.calls

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.cardview.widget.CardView
import com.discord.R
import com.discord.stores.StoreVoiceParticipants
import com.discord.utilities.dimen.dpToPixels
import com.discord.views.VoiceUserView

class StageCallSpeakerView(context: Context, attrs: AttributeSet?) : CardView(context, attrs, 0) {
    private val speaker: VoiceUserView
    private val blockedIndicator: ImageView
    private val deafenIndicator: ImageView
    private val moderatorIndicator: ImageView
    private val muteIndicator: ImageView
    private val speakerName: TextView
    private val pulse: SpeakerPulseView
    private var configuration = 0

    init {
        LayoutInflater.from(context).inflate(R.layout.view_stage_channel_speaker, this)
        speaker = requireNotNull(findViewById(R.id.stage_channel_speaker))
        blockedIndicator = requireNotNull(findViewById(R.id.stage_channel_speaker_blocked))
        deafenIndicator = requireNotNull(findViewById(R.id.stage_channel_speaker_deafen_indicator))
        moderatorIndicator = requireNotNull(findViewById(R.id.stage_channel_speaker_mod_indicator))
        muteIndicator = requireNotNull(findViewById(R.id.stage_channel_speaker_mute_indicator))
        speakerName = requireNotNull(findViewById(R.id.stage_channel_speaker_name))
        pulse = requireNotNull(findViewById(R.id.stage_channel_speaker_pulse))
    }

    fun configure(voiceUser: StoreVoiceParticipants.VoiceUser, isModerator: Boolean, isBlocked: Boolean) {
        configuration++
        val currentConfiguration = configuration
        speaker.setOnBitmapLoadedListener { bitmap, _ ->
            val color = representativeColor(bitmap)
            post { if (configuration == currentConfiguration) setCardBackgroundColor(color) }
        }
        pulse.setPulsing(voiceUser.isSpeaking)
        speaker.setVoiceUser(voiceUser, R.dimen.avatar_size_hero)
        speaker.setRingMargin(dpToPixels(2))

        val indicators = stageSpeakerIndicators(voiceUser.isMuted, voiceUser.isDeafened, isModerator, isBlocked)
        muteIndicator.visibleIf(indicators.showMute)
        deafenIndicator.visibleIf(indicators.showDeafen)
        moderatorIndicator.visibleIf(indicators.showModerator)
        blockedIndicator.visibleIf(indicators.showBlocked)
        speakerName.text = voiceUser.displayName
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        configuration++
    }

    private fun View.visibleIf(visible: Boolean) {
        visibility = if (visible) View.VISIBLE else View.GONE
    }


    private fun representativeColor(bitmap: Bitmap): Int {
        if (bitmap.isRecycled || bitmap.width == 0 || bitmap.height == 0) return Color.rgb(88, 101, 242)
        val buckets = IntArray(32 * 32 * 32)
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val color = bitmap.getPixel(x, y)
            if (Color.alpha(color) >= 128) {
                val bucket = (Color.red(color) shr 3 shl 10) or (Color.green(color) shr 3 shl 5) or
                    (Color.blue(color) shr 3)
                buckets[bucket]++
            }
        }
        val bucket = buckets.indices.maxByOrNull(buckets::get) ?: return Color.rgb(88, 101, 242)
        return Color.rgb(((bucket shr 10) and 31) * 255 / 31, ((bucket shr 5) and 31) * 255 / 31, (bucket and 31) * 255 / 31)
    }
}

internal data class StageSpeakerIndicators(
    val showMute: Boolean,
    val showDeafen: Boolean,
    val showModerator: Boolean,
    val showBlocked: Boolean,
)

internal fun stageSpeakerIndicators(muted: Boolean, deafened: Boolean, moderator: Boolean, blocked: Boolean) =
    StageSpeakerIndicators(muted && !deafened, deafened, moderator, blocked)

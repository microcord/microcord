package com.discord.widgets.user.usersheet

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.discord.R
import com.discord.views.calls.VolumeSliderView
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlin.math.roundToInt

class UserProfileVoiceSettingsView(context: Context, attrs: AttributeSet) : LinearLayout(context, attrs) {
    data class ViewState(
        val isMuted: Boolean,
        val isDeafened: Boolean?,
        val outputVolume: Float?,
    )

    private val deafen: SwitchMaterial
    private val mute: SwitchMaterial
    private val volumeLabel: TextView
    private val volumeSlider: VolumeSliderView

    init {
        LayoutInflater.from(context).inflate(R.layout.user_profile_voice_settings_view, this)
        deafen = requireNotNull(findViewById(R.id.user_sheet_deafen))
        mute = requireNotNull(findViewById(R.id.user_sheet_muted))
        volumeLabel = requireNotNull(findViewById(R.id.user_sheet_volume_label))
        volumeSlider = requireNotNull(findViewById(R.id.user_sheet_volume_slider))
    }

    fun setOnMuteChecked(callback: (Boolean) -> Unit) {
        mute.setOnCheckedChangeListener { _, checked -> callback(checked) }
    }

    fun setOnDeafenChecked(callback: (Boolean) -> Unit) {
        deafen.setOnCheckedChangeListener { _, checked -> callback(checked) }
    }

    fun setOnVolumeChange(callback: (Float, Boolean) -> Unit) {
        volumeSlider.setOnVolumeChange(callback)
    }

    fun updateView(state: ViewState) {
        val volumeVisible = state.outputVolume != null
        volumeLabel.visibility = if (volumeVisible) View.VISIBLE else View.GONE
        volumeSlider.visibility = if (volumeVisible) View.VISIBLE else View.GONE
        mute.isChecked = state.isMuted
        deafen.visibility = if (state.isDeafened == null) View.GONE else View.VISIBLE
        deafen.isChecked = state.isDeafened ?: false
        volumeSlider.findViewById<android.widget.SeekBar>(R.id.volume_slider_seek_bar).progress =
            state.outputVolume?.roundToInt() ?: 0
    }
}

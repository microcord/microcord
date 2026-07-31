package com.discord.widgets.voice.controls

import com.discord.R

enum class VoiceControlsOutputSelectorState(
    val audioOutputIconRes: Int,
    val isButtonActive: Boolean,
    val showMoreOptions: Boolean,
) {
    SPEAKER_ON(R.drawable.ic_sound_24dp, true, false),
    SPEAKER_OFF(R.drawable.ic_sound_quiet_24dp, false, false),
    BLUETOOTH_ON_AND_MORE(R.drawable.ic_sound_bluetooth_24dp, true, true),
    SPEAKER_ON_AND_MORE(R.drawable.ic_sound_24dp, true, true),
    SPEAKER_OFF_AND_MORE(R.drawable.ic_sound_quiet_24dp, false, true),
}

package com.discord.widgets.voice.sheet

import com.discord.models.user.User

/** Which of the two upstream surfaces the sheet stands in for. */
enum class VoiceSettingsMode { MENU, OPTIONS }

data class VoiceMember(
    val user: User,
    val name: String,
    val hasVideo: Boolean,
    val isMuted: Boolean,
)

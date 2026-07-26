package com.discord.widgets.stage.model

import com.discord.api.channel.Channel
import com.discord.stores.StoreVoiceParticipants
import com.discord.widgets.voice.fullscreen.stage.StageCallItem

/**
 * One video surface on a stage — either a speaker's camera or their screen share.
 *
 * A single user can contribute two of these at once (camera *and* stream), which is why [id]
 * exists: it is `"USER-<userId>"` or `"STREAM-<userId>"` and is what keeps the two apart in the
 * grid's diffing.
 */
data class StageMediaParticipant(
    val id: String,
    val voiceUser: StoreVoiceParticipants.VoiceUser,
    val channel: Channel,
    val isModerator: Boolean,
    val isBlocked: Boolean,
    val mediaType: StageCallItem.MediaType,
)

package com.discord.widgets.voice.fullscreen.stage

import com.discord.api.channel.Channel
import com.discord.stores.StoreVoiceParticipants

/**
 * A stage row that stands for a person, as opposed to a header, divider or details block.
 *
 * The adapter uses this to answer "which user did the tap land on" without caring whether the row
 * was a speaker tile, an audience row or a media tile.
 */
interface StageCallParticipantItem {
    val channel: Channel
    val voiceUser: StoreVoiceParticipants.VoiceUser
}

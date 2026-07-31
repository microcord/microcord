package com.discord.widgets.stage

import com.discord.api.channel.Channel
import com.discord.api.stageinstance.StageInstance
import com.discord.api.stageinstance.StageInstancePrivacyLevel
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreStream
import com.discord.utilities.rest.RestAPI
import rx.Observable

/**
 * The four REST calls the stage surfaces make.
 *
 * Speaking on a stage is expressed as the *absence* of suppression: accepting an invitation to
 * speak clears `suppress`, declining sets it. The endpoint differs for yourself
 * (`voice-states/@me`) versus someone you are moderating (`voice-states/{userId}`), which is the
 * only reason [setUserSuppressedInChannel] takes `meId`.
 */
object StageChannelAPI {

    /**
     * Accepts or declines the current user's invitation to speak in [channelId].
     *
     * Returns `null` when the channel is unknown, which is how the callers distinguish
     * "nothing to do" from a request that was actually issued.
     */
    fun ackInvitationToSpeak(channelId: Long, accept: Boolean): Observable<Void>? =
        StoreStream.getChannels().getChannel(channelId)?.let { ackInvitationToSpeak(it, accept) }

    fun ackInvitationToSpeak(channel: Channel, accept: Boolean): Observable<Void> =
        setUserSuppressedInChannel(
            channel = channel,
            userId = StoreStream.getUsers().getMe().id,
            isSuppressed = !accept,
        )

    @JvmOverloads
    fun setUserSuppressedInChannel(
        channel: Channel,
        userId: Long,
        isSuppressed: Boolean,
        meId: Long = StoreStream.getUsers().getMe().id,
    ): Observable<Void> {
        val body = RestAPIParams.ChannelVoiceStateUpdate(
            channelId = channel.id,
            suppress = isSuppressed,
        )
        return if (userId == meId) {
            RestAPI.apiSerializeNulls.updateMyVoiceStates(channel.guildId, body)
        } else {
            RestAPI.api.updateUserVoiceStates(channel.guildId, userId, body)
        }
    }

    @JvmOverloads
    fun startStageInstance(
        channelId: Long,
        topic: String,
        privacyLevel: StageInstancePrivacyLevel,
        sendStartNotification: Boolean,
        guildScheduledEventId: String? = null,
    ): Observable<StageInstance> = RestAPI.api.startStageInstance(
        RestAPIParams.StartStageInstanceBody(
            channelId = channelId,
            topic = topic,
            privacyLevel = privacyLevel,
            sendStartNotification = sendStartNotification,
            guildScheduledEventId = guildScheduledEventId,
        ),
    )

    @JvmOverloads
    fun updateStageInstance(
        channelId: Long,
        topic: String? = null,
        privacyLevel: StageInstancePrivacyLevel? = null,
    ): Observable<StageInstance> = RestAPI.api.updateStageInstance(
        channelId,
        RestAPIParams.UpdateStageInstanceBody(topic = topic, privacyLevel = privacyLevel),
    )

    /** Ends the stage instance running in [channelId]; the channel itself stays. */
    fun endStageInstance(channelId: Long): Observable<Unit> =
        RestAPI.api.endStageInstance(channelId)
}

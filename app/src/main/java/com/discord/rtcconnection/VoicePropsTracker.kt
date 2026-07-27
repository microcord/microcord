package com.discord.rtcconnection

import com.discord.api.voice.state.VoiceState
import com.discord.stores.isRemoveSignal

internal class VoicePropsTracker(
    private val guildId: Long,
    private val channelId: Long,
    private val localUserId: Long,
    voiceStates: Map<Long, VoiceState>?,
) {
    private val allVoiceStateUsers = linkedSetOf<Long>()
    private val currentVoiceStateUsers = linkedSetOf<Long>()
    private val allListenerUsers = linkedSetOf<Long>()
    private val currentPotentialListeners = linkedSetOf<Long>()
    private val allSpeakerUsers = linkedSetOf<Long>()
    private val currentSpeakerUsers = linkedSetOf<Long>()
    private var localUserSpeaking = false
    private var maxVoiceStateCount = 0
    private var maxListenerCount = 0
    private var maxSpeakerCount = 0

    init {
        voiceStates?.forEach { (userId, voiceState) ->
            if (voiceState.channelId == channelId) {
                currentVoiceStateUsers += userId
                if (!voiceState.deaf && !voiceState.selfDeaf) currentPotentialListeners += userId
            }
        }
    }

    fun getProps(properties: MutableMap<String, Any>) {
        properties["max_voice_state_count"] = maxVoiceStateCount
        properties["total_voice_state_count"] = allVoiceStateUsers.size
        properties["max_listener_count"] = maxListenerCount
        properties["total_listener_count"] = allListenerUsers.size
        properties["max_speaker_count"] = maxSpeakerCount
        properties["total_speaker_count"] = allSpeakerUsers.size
    }

    fun handleOnSpeaking(userId: Long, isSpeaking: Boolean) {
        if (isSpeaking) {
            allSpeakerUsers += userId
            currentSpeakerUsers += userId
            maxSpeakerCount = maxOf(maxSpeakerCount, currentSpeakerUsers.size)
        } else {
            currentSpeakerUsers -= userId
        }

        if (userId == localUserId && localUserSpeaking != isSpeaking) {
            localUserSpeaking = isSpeaking
            if (isSpeaking) {
                allListenerUsers += currentPotentialListeners
                maxListenerCount = maxOf(maxListenerCount, currentPotentialListeners.size)
            }
        }
    }

    fun handleVoiceStateUpdate(voiceState: VoiceState) {
        if (voiceState.guildId != guildId) return

        if (voiceState.isRemoveSignal) {
            currentVoiceStateUsers -= voiceState.userId
            currentPotentialListeners -= voiceState.userId
            currentSpeakerUsers -= voiceState.userId
            return
        }

        if (voiceState.channelId != channelId) return
        currentVoiceStateUsers += voiceState.userId
        allVoiceStateUsers += voiceState.userId
        maxVoiceStateCount = maxOf(maxVoiceStateCount, currentVoiceStateUsers.size)

        if (!voiceState.deaf && !voiceState.selfDeaf) {
            currentPotentialListeners += voiceState.userId
            if (localUserSpeaking) {
                allListenerUsers += voiceState.userId
                maxListenerCount = maxOf(maxListenerCount, currentPotentialListeners.size)
            }
        } else {
            currentPotentialListeners -= voiceState.userId
        }
    }
}

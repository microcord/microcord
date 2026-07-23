package com.discord.widgets.stage.usersheet

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.voice.state.StageRequestToSpeakState
import com.discord.widgets.stage.StageRoles

class UserProfileStageActionsView(context: Context, attrs: AttributeSet) : LinearLayout(context, attrs) {
    data class ViewState(
        val isMe: Boolean,
        val channel: Channel?,
        val userStageRole: StageRoles?,
        val userRequestToSpeakState: StageRequestToSpeakState,
        val userInSameVoiceChannel: Boolean,
        val canMuteMembers: Boolean,
        val isUpdatingSuppressed: Boolean = false,
        val isInvitingToSpeak: Boolean = false,
    )

    private val invite: TextView
    private val move: TextView

    init {
        LayoutInflater.from(context).inflate(R.layout.user_profile_stage_actions_view, this)
        invite = requireNotNull(findViewById(R.id.user_profile_stage_invite_to_speak))
        move = requireNotNull(findViewById(R.id.user_profile_stage_move_to_audience))
    }

    fun setOnInviteToSpeak(callback: () -> Unit) = invite.setOnClickListener { callback() }
    fun setOnMoveToAudience(callback: () -> Unit) = move.setOnClickListener { callback() }

    fun updateView(state: ViewState) {
        visibility = if (state.userInSameVoiceChannel && state.canMuteMembers) View.VISIBLE else View.GONE
        if (visibility != View.VISIBLE) return
        invite.visibility = if (state.userRequestToSpeakState.canBeInvitedToSpeak) View.VISIBLE else View.GONE
        invite.isEnabled = !state.isInvitingToSpeak
        invite.setText(if (state.isMe) R.string.stage_channel_user_invited_to_speak else R.string.stage_channel_user_invite_to_speak)
        invite.setCompoundDrawablesWithIntrinsicBounds(
            if (state.isMe) R.drawable.ic_stage_move_to_speaker else R.drawable.ic_stage_invite_speak,
            0, 0, 0,
        )
        move.visibility = if (state.userStageRole?.isSpeaker == true) View.VISIBLE else View.GONE
        move.isEnabled = !state.isUpdatingSuppressed
    }
}

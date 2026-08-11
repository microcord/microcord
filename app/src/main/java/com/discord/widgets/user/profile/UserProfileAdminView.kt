package com.discord.widgets.user.profile

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.discord.R

class UserProfileAdminView(context: Context, attrs: AttributeSet) : LinearLayout(context, attrs) {
    data class ViewState(
        val showEditMemberButton: Boolean,
        val showKickButton: Boolean,
        val showDisableCommunicationButton: Boolean,
        val showVerifyMemberButton: Boolean,
        val isMemberVerified: Boolean,
        val isMultiUserDM: Boolean,
        val showBanButton: Boolean,
        val showServerMuteButton: Boolean,
        val isServerMuted: Boolean,
        val showServerDeafenButton: Boolean,
        val isServerDeafened: Boolean,
        val showServerMoveAndDisconnectButtons: Boolean,
        val isAdminSectionEnabled: Boolean,
        val isMe: Boolean,
        val isCommunicationDisabled: Boolean,
    )

    private val editMember: TextView
    private val disableCommunication: TextView
    private val verifyMember: TextView
    private val serverMute: TextView
    private val serverDeafen: TextView
    private val serverMove: TextView
    private val serverDisconnect: TextView
    private val kick: TextView
    private val ban: TextView

    init {
        LayoutInflater.from(context).inflate(R.layout.user_profile_admin_view, this)
        editMember = requireNotNull(findViewById(R.id.user_profile_admin_edit_member))
        disableCommunication = requireNotNull(findViewById(R.id.user_profile_admin_disable_communication))
        verifyMember = requireNotNull(findViewById(R.id.user_profile_admin_verify_member))
        serverMute = requireNotNull(findViewById(R.id.user_profile_admin_server_mute))
        serverDeafen = requireNotNull(findViewById(R.id.user_profile_admin_server_deafen))
        serverMove = requireNotNull(findViewById(R.id.user_profile_admin_server_move))
        serverDisconnect = requireNotNull(findViewById(R.id.user_profile_admin_server_disconnect))
        kick = requireNotNull(findViewById(R.id.user_profile_admin_kick))
        ban = requireNotNull(findViewById(R.id.user_profile_admin_ban))
    }

    fun setOnEditMember(callback: () -> Unit) = editMember.setOnClickListener { callback() }
    fun setOnKick(callback: () -> Unit) = kick.setOnClickListener { callback() }
    fun setOnBan(callback: () -> Unit) = ban.setOnClickListener { callback() }
    fun setOnDisableCommunication(callback: () -> Unit) =
        disableCommunication.setOnClickListener { callback() }
    fun setOnVerifyMember(callback: () -> Unit) = verifyMember.setOnClickListener { callback() }
    fun setOnServerMute(callback: () -> Unit) = serverMute.setOnClickListener { callback() }
    fun setOnServerDeafen(callback: () -> Unit) = serverDeafen.setOnClickListener { callback() }
    fun setOnServerMove(callback: () -> Unit) = serverMove.setOnClickListener { callback() }
    fun setOnDisconnect(callback: () -> Unit) = serverDisconnect.setOnClickListener { callback() }

    fun updateView(state: ViewState) {
        editMember.visible(state.showEditMemberButton)
        kick.visible(state.showKickButton)
        kick.setText(if (state.isMultiUserDM) R.string.remove_from_group else R.string.kick)
        verifyMember.visible(state.showVerifyMemberButton)
        verifyMember.setText(
            if (state.isMemberVerified) R.string.manually_unverify_member
            else R.string.manually_verify_member,
        )
        disableCommunication.visible(state.showDisableCommunicationButton)
        disableCommunication.setText(
            if (state.isCommunicationDisabled) R.string.remove_time_out else R.string.time_out,
        )
        disableCommunication.setCompoundDrawablesWithIntrinsicBounds(
            if (state.isCommunicationDisabled) R.drawable.ic_enable_guild_communication_24dp
            else R.drawable.ic_disable_guild_communication_red_24dp,
            0,
            0,
            0,
        )
        ban.visible(state.showBanButton)
        serverMute.visible(state.showServerMuteButton)
        serverMute.setText(if (state.isServerMuted) R.string.server_unmute else R.string.server_mute)
        serverMute.setCompoundDrawablesWithIntrinsicBounds(
            themedResource(if (state.isServerMuted) R.attr.ic_mic_muted_grey else R.attr.ic_mic_grey),
            0,
            0,
            0,
        )
        serverDeafen.visible(state.showServerDeafenButton)
        serverDeafen.setText(if (state.isServerDeafened) R.string.server_undeafen else R.string.server_deafen)
        serverDeafen.setCompoundDrawablesWithIntrinsicBounds(
            themedResource(R.attr.ic_headset_deafened_grey),
            0,
            0,
            0,
        )
        serverMove.visible(state.showServerMoveAndDisconnectButtons)
        serverDisconnect.visible(state.showServerMoveAndDisconnectButtons)
        serverDisconnect.setText(if (state.isMe) R.string.disconnect_self else R.string.disconnect_other)
    }

    private fun TextView.visible(visible: Boolean) {
        visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun themedResource(attribute: Int): Int {
        val value = TypedValue()
        context.theme.resolveAttribute(attribute, value, true)
        return value.resourceId
    }
}

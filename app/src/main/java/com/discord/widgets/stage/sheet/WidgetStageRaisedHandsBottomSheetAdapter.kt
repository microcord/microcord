package com.discord.widgets.stage.sheet

import android.content.Context
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.role.GuildRole
import com.discord.api.utcdatetime.UtcDateTime
import com.discord.models.member.GuildMember
import com.discord.models.user.User
import com.discord.stores.StoreVoiceParticipants
import com.discord.utilities.SnowflakeUtils
import com.discord.utilities.mg_recycler.MGRecyclerAdapterSimple
import com.discord.utilities.mg_recycler.MGRecyclerDataPayload
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder
import com.discord.utilities.time.ClockFactory
import com.discord.utilities.view.extensions.setEnabledAndAlpha
import com.discord.views.user.avatarUrl
import com.discord.views.user.setAvatar
import com.discord.views.VitoImageView
import java.util.concurrent.TimeUnit

/**
 * The queue of people who have raised a hand in a stage, oldest request first.
 *
 * Each row carries a one-line "who is this?" summary so a moderator can judge an unfamiliar
 * account before putting them on stage: brand-new Discord accounts and non-members are called out
 * explicitly, members are described by their highest hoisted role.
 */
class WidgetStageRaisedHandsBottomSheetAdapter(
    recycler: RecyclerView,
    private val onInviteToSpeak: (StoreVoiceParticipants.VoiceUser) -> Unit = {},
    private val onDismissRequest: (StoreVoiceParticipants.VoiceUser) -> Unit = {},
    private val onViewProfile: (StoreVoiceParticipants.VoiceUser) -> Unit = {},
) : MGRecyclerAdapterSimple<WidgetStageRaisedHandsBottomSheetAdapter.ListItem>(recycler) {

    sealed class ListItem : MGRecyclerDataPayload {
        data class Participant(
            val participant: StoreVoiceParticipants.VoiceUser,
            val requestToSpeakTimestamp: UtcDateTime,
            val member: GuildMember?,
            val role: GuildRole?,
        ) : ListItem() {
            override val type = TYPE_PARTICIPANT
            override val key = participant.user.id.toString()
        }
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): MGRecyclerViewHolder<*, ListItem> = when (viewType) {
        TYPE_PARTICIPANT -> ParticipantViewHolder(this)
        else -> throw invalidViewTypeException(viewType)
    }

    private class ParticipantViewHolder(
        private val stageAdapter: WidgetStageRaisedHandsBottomSheetAdapter,
    ) : MGRecyclerViewHolder<MGRecyclerAdapterSimple<ListItem>, ListItem>(
        R.layout.stage_raised_hands_item_user,
        stageAdapter,
    ) {
        private val avatar: VitoImageView =
            itemView.findViewById(R.id.stage_raised_hands_item_user_avatar)
        private val name: TextView = itemView.findViewById(R.id.stage_raised_hands_item_user_name)
        private val memberInfo: TextView = itemView.findViewById(R.id.stage_raised_hands_item_role)
        private val inviteButton: ImageView =
            itemView.findViewById(R.id.stage_raised_hands_item_invite_button)
        private val dismissButton: ImageView =
            itemView.findViewById(R.id.stage_raised_hands_item_dismiss_button)

        override fun onConfigure(position: Int, data: ListItem) {
            super.onConfigure(position, data)
            val item = data as? ListItem.Participant ?: return
            val voiceUser = item.participant

            avatar.setOnClickListener { stageAdapter.onViewProfile(voiceUser) }
            // Someone already invited stays listed until they accept, but inviting them twice
            // does nothing, so the button goes flat.
            inviteButton.setEnabledAndAlpha(!voiceUser.isInvitedToSpeak)
            inviteButton.setOnClickListener { stageAdapter.onInviteToSpeak(voiceUser) }
            dismissButton.setOnClickListener { stageAdapter.onDismissRequest(voiceUser) }

            val size = itemView.resources.getDimensionPixelSize(R.dimen.avatar_size_standard)
            setAvatar(avatar, avatarUrl(voiceUser.user, voiceUser.guildMember, size), true, size)

            name.text = voiceUser.displayName
            // A member's role colour only applies when they actually have one; opaque black is
            // the "no colour" sentinel and must not override the theme.
            item.member?.color?.takeIf { it != ROLE_COLOR_UNSET }?.let(name::setTextColor)

            memberInfo.text = getParticipantMemberInfo(memberInfo.context, item)
        }

        /**
         * The single line of context under a requester's name, in decreasing order of how much a
         * moderator needs to know it.
         */
        private fun getParticipantMemberInfo(
            context: Context,
            item: ListItem.Participant,
        ): String {
            if (isNewUser(item.participant.user)) {
                return context.getString(R.string.request_to_speak_member_info_new_to_discord)
            }
            val joinedAt = item.member?.joinedAt
                ?: return context.getString(R.string.request_to_speak_member_info_non_member)

            if (item.member.getRoles().isNotEmpty()) {
                return item.role?.name
                    ?: context.getString(R.string.request_to_speak_member_info_member_roles)
            }
            val memberFor = ClockFactory.get().currentTimeMillis() - joinedAt.dateTimeMillis
            return if (memberFor < MILLIS_PER_DAY) {
                context.getString(R.string.request_to_speak_member_info_new_member)
            } else {
                context.getString(R.string.request_to_speak_member_info_member)
            }
        }

        /** An account less than a week old is worth flagging before it is handed a microphone. */
        private fun isNewUser(user: User): Boolean =
            ClockFactory.get().currentTimeMillis() - SnowflakeUtils.toTimestamp(user.id) <=
                MILLIS_PER_WEEK
    }

    private companion object {
        const val TYPE_PARTICIPANT = 0

        /** Opaque black — what a member with no coloured role reports. */
        const val ROLE_COLOR_UNSET = 0xFF000000.toInt()
        val MILLIS_PER_DAY = TimeUnit.DAYS.toMillis(1)
        val MILLIS_PER_WEEK = TimeUnit.DAYS.toMillis(7)
    }
}

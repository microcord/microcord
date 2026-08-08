package com.discord.widgets.chat.list.adapter

import androidx.annotation.LayoutRes
import androidx.fragment.app.findFragment
import com.discord.app.AppFragment
import com.discord.models.domain.ModelInvite
import com.discord.widgets.guilds.invite.InviteJoinHelper

/** Shared invite acceptance flow for chat invite rows. */
abstract class WidgetChatListAdapterItemInviteBase(
    @LayoutRes layoutResId: Int,
    adapter: WidgetChatListAdapter,
) : WidgetChatListItem(layoutResId, adapter) {

    protected fun joinServerOrDM(
        javaClass: Class<*>,
        invite: ModelInvite,
        location: String = INVITE_LOCATION,
        inviteInstanceId: String? = null,
        onInvitePostSuccess: (ModelInvite) -> Unit = {},
        onInviteFlowFinished: (ModelInvite) -> Unit = {},
    ) {
        InviteJoinHelper.joinViaInvite(
            invite = invite,
            javaClass = javaClass,
            fragment = itemView.findFragment<AppFragment>(),
            location = location,
            inviteInstanceId = inviteInstanceId,
            onInvitePostSuccess = onInvitePostSuccess,
            onInviteFlowFinished = onInviteFlowFinished,
        )
    }

    companion object {
        const val INVITE_LOCATION = "Invite Button Embed"
        const val EVENT_INVITE_LOCATION = "Guild Scheduled Event Invite Button Embed"

        /** Reference `getInviteInstanceId`: identifies the embed a join came from. */
        fun inviteInstanceId(messageId: Long, inviteCode: String) = "$messageId:$inviteCode"
    }
}

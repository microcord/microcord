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
        onInvitePostSuccess: (ModelInvite) -> Unit = {},
        onInviteFlowFinished: (ModelInvite) -> Unit = {},
    ) {
        InviteJoinHelper.joinViaInvite(
            invite = invite,
            javaClass = javaClass,
            fragment = itemView.findFragment<AppFragment>(),
            location = INVITE_LOCATION,
            onInvitePostSuccess = onInvitePostSuccess,
            onInviteFlowFinished = onInviteFlowFinished,
        )
    }

    private companion object {
        const val INVITE_LOCATION = "Invite Button Embed"
    }
}

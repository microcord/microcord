package com.discord.widgets.servers.member_verification.form_fields

import com.discord.api.role.GuildRole
import com.discord.simpleast.core.node.Node
import com.discord.utilities.textprocessing.MessageRenderContext

data class MemberVerificationItemTerm(
    val index: Int,
    val rule: String,
    val ast: MutableList<Node<MessageRenderContext>>,
    val channelNames: Map<Long, String>,
    val roles: Map<Long, GuildRole>,
    val allowAnimatedEmojis: Boolean,
    val isFirstItem: Boolean,
    val isLastItem: Boolean,
) : MemberVerificationItem {
    override val type = MemberVerificationItem.TERM
    override val key = "$type$index$rule"
}

package com.discord.widgets.servers.member_verification.form_fields

data class MemberVerificationItemHeader(val title: String) : MemberVerificationItem {
    override val type = MemberVerificationItem.HEADER
    override val key = title
}

package com.discord.widgets.servers.member_verification.form_fields

data class MemberVerificationItemTextInput(
    val fieldIndex: Int,
    val response: String?,
) : MemberVerificationItem {
    override val type = MemberVerificationItem.TEXT_INPUT
    override val key = "$fieldIndex$type"
}

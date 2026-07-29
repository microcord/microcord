package com.discord.widgets.servers.member_verification.form_fields

data class MemberVerificationItemParagraph(
    val fieldIndex: Int,
    val response: String?,
) : MemberVerificationItem {
    override val type = MemberVerificationItem.PARAGRAPH
    override val key = "$fieldIndex$type"
}

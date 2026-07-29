package com.discord.widgets.servers.member_verification.form_fields

data class MemberVerificationItemMultipleChoice(
    val fieldIndex: Int,
    val choices: List<String>,
    val response: Int?,
) : MemberVerificationItem {
    override val type = MemberVerificationItem.MULTIPLE_CHOICE
    override val key = "$fieldIndex$type"
}

package com.discord.widgets.servers.member_verification.form_fields

data class MemberVerificationItemApproveTerms(
    val fieldIndex: Int,
    val isApproved: Boolean,
) : MemberVerificationItem {
    override val type = MemberVerificationItem.APPROVE_TERMS
    override val key = type.toString()
}

package com.discord.widgets.servers.member_verification.form_fields

import com.discord.utilities.mg_recycler.MGRecyclerDataPayload

interface MemberVerificationItem : MGRecyclerDataPayload {
    companion object {
        const val HEADER = 0
        const val TERMS_HEADER = 1
        const val APPROVE_TERMS = 2
        const val TERM = 3
        const val TEXT_INPUT = 4
        const val PARAGRAPH = 5
        const val MULTIPLE_CHOICE = 6
    }
}

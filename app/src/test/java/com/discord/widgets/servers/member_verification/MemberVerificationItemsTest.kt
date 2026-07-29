package com.discord.widgets.servers.member_verification

import com.discord.widgets.servers.member_verification.form_fields.MemberVerificationItem
import com.discord.widgets.servers.member_verification.form_fields.MemberVerificationItemApproveTerms
import com.discord.widgets.servers.member_verification.form_fields.MemberVerificationItemHeader
import com.discord.widgets.servers.member_verification.form_fields.MemberVerificationItemMultipleChoice
import com.discord.widgets.servers.member_verification.form_fields.MemberVerificationItemParagraph
import com.discord.widgets.servers.member_verification.form_fields.MemberVerificationItemTermsHeader
import com.discord.widgets.servers.member_verification.form_fields.MemberVerificationItemTextInput
import org.junit.Assert.assertEquals
import org.junit.Test

class MemberVerificationItemsTest {

    @Test
    fun `row types and keys match the reference contracts`() {
        assertEquals(0 to "Questions", row(MemberVerificationItemHeader("Questions")))
        assertEquals(1 to "1", row(MemberVerificationItemTermsHeader()))
        assertEquals(2 to "2", row(MemberVerificationItemApproveTerms(4, true)))
        assertEquals(4 to "74", row(MemberVerificationItemTextInput(7, "answer")))
        assertEquals(5 to "75", row(MemberVerificationItemParagraph(7, "answer")))
        assertEquals(6 to "76", row(MemberVerificationItemMultipleChoice(7, listOf("A"), 0)))
    }

    private fun row(item: MemberVerificationItem) = item.type to item.key
}

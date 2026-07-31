package com.discord.widgets.servers.member_verification

import com.discord.api.guildjoinrequest.ApplicationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MemberVerificationDialogViewModelsTest {

    @Test
    fun `pending dialog state follows application preview and outdated form`() {
        assertEquals(
            MemberVerificationPendingViewModel.DialogState.PENDING,
            pending(ApplicationStatus.PENDING).dialogState,
        )
        assertEquals(
            MemberVerificationPendingViewModel.DialogState.CANCEL,
            pending(ApplicationStatus.PENDING, preview = true).dialogState,
        )
        assertEquals(
            MemberVerificationPendingViewModel.DialogState.REJECTED,
            pending(ApplicationStatus.REJECTED).dialogState,
        )
        assertEquals(
            MemberVerificationPendingViewModel.DialogState.UPGRADE,
            pending(ApplicationStatus.PENDING, outdated = true).dialogState,
        )
    }

    @Test
    fun `pending dialog has no state for an application that is not pending or rejected`() {
        assertNull(
            MemberVerificationPendingViewModel.reduce(
                MemberVerificationPendingViewModel.StoreState(false, false, ApplicationStatus.STARTED, null),
                null,
            ),
        )
    }

    @Test
    fun `success dialog closes after the join request is acknowledged`() {
        assertEquals(
            MemberVerificationSuccessViewModel.ViewState.Loaded("Manticord"),
            MemberVerificationSuccessViewModel.reduce(
                MemberVerificationSuccessViewModel.StoreState(null, "Manticord"),
            ),
        )
        assertSame(
            MemberVerificationSuccessViewModel.ViewState.Acked,
            MemberVerificationSuccessViewModel.reduce(
                MemberVerificationSuccessViewModel.StoreState("2026-07-30T00:00:00Z", "Manticord"),
            ),
        )
    }

    @Test
    fun `member verification requires every non-terms response`() {
        assertTrue(WidgetMemberVerificationViewModel.areResponsesValid(emptyList()))
        assertTrue(WidgetMemberVerificationViewModel.areResponsesValid(listOf("answer", 1)))
        assertFalse(WidgetMemberVerificationViewModel.areResponsesValid(listOf(null, "answer")))
        assertFalse(WidgetMemberVerificationViewModel.areResponsesValid(listOf("")))
    }

    private fun pending(
        status: ApplicationStatus,
        preview: Boolean = false,
        outdated: Boolean = false,
    ) = requireNotNull(
        MemberVerificationPendingViewModel.reduce(
            MemberVerificationPendingViewModel.StoreState(outdated, preview, status, "reason"),
            null,
        ),
    )
}

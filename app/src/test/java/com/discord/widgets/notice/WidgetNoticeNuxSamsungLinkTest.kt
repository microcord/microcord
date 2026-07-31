package com.discord.widgets.notice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetNoticeNuxSamsungLinkTest {

    @Test
    fun accountMustBeStrictlyOlderThanTwentyEightDays() {
        assertFalse(WidgetNoticeNuxSamsungLink.isAccountOldEnough(2_419_200_000L))
        assertTrue(WidgetNoticeNuxSamsungLink.isAccountOldEnough(2_419_200_001L))
    }
}

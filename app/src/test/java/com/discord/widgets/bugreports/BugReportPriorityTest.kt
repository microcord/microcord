package com.discord.widgets.bugreports

import com.discord.R
import org.junit.Assert.assertEquals
import org.junit.Test

class BugReportPriorityTest {

    /**
     * **The index into this list is the `priority` field sent to `POST /private/bug-reports`.**
     * Reordering it silently retags every future report, so both the order and the emoji ids are
     * pinned to the reference here.
     */
    @Test
    fun `priority levels are P0 to P3 in wire order`() {
        assertEquals(
            listOf(
                Priority(
                    R.string.bug_report_priority_critical_title,
                    R.string.bug_report_priority_critical_description,
                    801497159479722084L,
                ),
                Priority(
                    R.string.bug_report_priority_high_title,
                    R.string.bug_report_priority_high_description,
                    410336837563973632L,
                ),
                Priority(
                    R.string.bug_report_priority_low_title,
                    R.string.bug_report_priority_low_description,
                    841420679643529296L,
                ),
                Priority(
                    R.string.bug_report_priority_very_low_title,
                    R.string.bug_report_priority_very_low_description,
                    827645852352512021L,
                ),
            ),
            WidgetBugReport.priorityLevels,
        )
    }
}

package com.discord.widgets.mobile_reports

import com.discord.api.report.MenuAPIResponse
import com.discord.api.report.NodeResult
import com.discord.api.report.ReportNode
import com.discord.api.report.ReportNodeBottomButton
import com.discord.api.report.ReportNodeElement
import com.discord.api.report.ReportNodeElementData
import com.discord.api.report.ReportType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileReportsContractsTest {

    @Test
    fun `report arguments preserve reference type and channel semantics`() {
        assertEquals(ReportType.Message, MobileReportArgs.Message(1L, 2L).reportType)
        assertEquals(2L, MobileReportArgs.Message(1L, 2L).channelId)
        assertEquals(ReportType.StageChannel, MobileReportArgs.StageChannel(3L).reportType)
        assertEquals(3L, MobileReportArgs.StageChannel(3L).channelId)
        assertEquals(5L, MobileReportArgs.DirectoryServer(4L, 6L, 5L).channelId)
        assertEquals(-1L, MobileReportArgs.GuildScheduledEvent(7L, 8L).channelId)
    }

    @Test
    fun `menu state maps only element types present on the node`() {
        val checkboxData = listOf(ReportNodeElementData("spam", "Spam"))
        val node = node(
            id = 2,
            elements = listOf(
                ReportNodeElement("checkbox", "reason", checkboxData, true),
                ReportNodeElement("breadcrumbs", "", null, false),
                ReportNodeElement("success", "", null, false),
            ),
        )
        val menu = menu(node)
        val state = MobileReportsViewModel.ViewState.Menu(
            menu = menu,
            messagePreview = null,
            channelPreview = null,
            directoryServerPreview = null,
            eventPreview = null,
            nodeNavigationType = MobileReportsViewModel.NodeNavigationType.Next(node),
            history = listOf(NodeResult(node, null, null)),
            blockUserElement = null,
        )

        val rendered = state.genNodeState()
        assertEquals("reason", rendered.checkboxElement?.name)
        assertEquals(checkboxData, rendered.checkboxElement?.data)
        assertNotNull(rendered.breadcrumbsElement)
        assertTrue(rendered.successElement)
        assertNull(rendered.messagePreviewElement)
        assertEquals(ReportNodeBottomButton.Done, rendered.bottomButton)
    }

    @Test
    fun `back arrow hides at root success and loading only`() {
        val root = node(1)
        val middle = node(2)
        val success = node(3)
        val menu = menu(root, middle, success)
        fun state(node: ReportNode, submit: MobileReportsViewModel.SubmitState) =
            MobileReportsViewModel.ViewState.Menu(
                menu, null, null, null, null,
                MobileReportsViewModel.NodeNavigationType.Next(node),
                submit,
                emptyList(),
                null,
            )

        assertTrue(state(root, MobileReportsViewModel.SubmitState.None).shouldHideBackArrow())
        assertTrue(state(success, MobileReportsViewModel.SubmitState.None).shouldHideBackArrow())
        assertTrue(state(middle, MobileReportsViewModel.SubmitState.Loading).shouldHideBackArrow())
        assertFalse(state(middle, MobileReportsViewModel.SubmitState.None).shouldHideBackArrow())
    }

    private fun node(
        id: Int,
        elements: List<ReportNodeElement> = emptyList(),
    ) = ReportNode(
        id = id,
        menuName = null,
        header = "Header",
        subheader = null,
        info = null,
        description = null,
        children = emptyList(),
        elements = elements,
        button = ReportNodeBottomButton.Done,
    )

    private fun menu(vararg nodes: ReportNode) = MenuAPIResponse(
        name = "menu",
        variant = "variant",
        version = "1",
        language = "en",
        successNodeId = nodes.last().id,
        rootNodeId = nodes.first().id,
        postbackUrl = "",
        nodes = nodes.associateBy(ReportNode::id),
    )
}

package com.discord.widgets.chat.list.adapter

import com.discord.simpleast.core.node.TextNode
import com.discord.utilities.textprocessing.MessageRenderContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetChatListAdapterItemEmbedTest {
    @Test
    fun plainMessageTextCannotCrashSpoilerDetection() {
        val nodes = parseEmbedTextSafely<MessageRenderContext>("Reported Review") {
            throw RuntimeException("matcher found no matches")
        }

        assertEquals(1, nodes.size)
        assertTrue(nodes.single() is TextNode)
    }
}

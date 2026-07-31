package com.discord.utilities.textprocessing

import com.discord.simpleast.core.node.Node
import com.discord.utilities.textprocessing.node.ChannelMentionNode
import com.discord.utilities.textprocessing.node.RoleMentionNode
import com.discord.utilities.textprocessing.node.UserMentionNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TagsBuilderTest {
    @Test
    fun extractsOnlyConcreteEntityMentions() {
        val ast: List<Node<*>> = listOf(
            UserMentionNode<UserMentionNode.RenderContext>(UserMentionNode.Type.USER, 1),
            UserMentionNode<UserMentionNode.RenderContext>(UserMentionNode.Type.HERE),
            RoleMentionNode<RoleMentionNode.RenderContext>(2),
            ChannelMentionNode<ChannelMentionNode.RenderContext>(3),
        )

        val tags = TagsBuilder.extractTags(ast)

        assertEquals(setOf(1L), tags.users)
        assertEquals(setOf(2L), tags.roles)
        assertEquals(setOf(3L), tags.channels)
        assertFalse(tags.isEmpty)
    }
}

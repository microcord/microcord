package com.discord.utilities.textprocessing

import com.discord.simpleast.core.node.Node
import com.discord.utilities.textprocessing.node.ChannelMentionNode
import com.discord.utilities.textprocessing.node.RoleMentionNode
import com.discord.utilities.textprocessing.node.UserMentionNode

class TagsBuilder {
    private var taggedChannelIds: MutableSet<Long>? = null
    private var taggedRoleIds: MutableSet<Long>? = null
    private var taggedUserIds: MutableSet<Long>? = null

    fun build() = Tags(
        taggedUserIds ?: emptySet(),
        taggedChannelIds ?: emptySet(),
        taggedRoleIds ?: emptySet(),
    )

    fun processAst(ast: Collection<Node<*>>) {
        ast.forEach(::walk)
    }

    fun processNode(node: Node<*>?) {
        when (node) {
            is UserMentionNode<*> -> if (node.type == UserMentionNode.Type.USER) {
                taggedUserIds = (taggedUserIds ?: HashSet(4)).also { it += node.userId }
            }
            is RoleMentionNode<*> ->
                taggedRoleIds = (taggedRoleIds ?: HashSet(4)).also { it += node.roleId }
            is ChannelMentionNode<*> ->
                taggedChannelIds = (taggedChannelIds ?: HashSet(4)).also { it += node.channelId }
        }
    }

    private fun walk(node: Node<*>) {
        processNode(node)
        node.getChildren()?.forEach(::walk)
    }

    companion object {
        fun extractTags(ast: Collection<Node<*>>) = TagsBuilder().apply { processAst(ast) }.build()
    }
}

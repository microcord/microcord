package com.discord.widgets.chat.list.adapter

import com.discord.simpleast.core.node.Node
import com.discord.simpleast.core.node.TextNode

internal fun <T> parseEmbedTextSafely(text: String, parse: () -> MutableList<Node<T>>): MutableList<Node<T>> =
    try {
        parse()
    } catch (_: RuntimeException) {
        arrayListOf(TextNode(text))
    }

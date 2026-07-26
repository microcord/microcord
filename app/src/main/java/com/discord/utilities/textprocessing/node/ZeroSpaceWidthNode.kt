package com.discord.utilities.textprocessing.node

import com.discord.simpleast.core.node.TextNode

/**
 * Trailing zero width space appended to every rendered message.
 *
 * It keeps the last real span from swallowing touches on the empty area after the text and keeps
 * an empty message from collapsing to zero height.
 */
class ZeroSpaceWidthNode<T> : TextNode<T>(ZERO_WIDTH_SPACE) {
    companion object {
        const val ZERO_WIDTH_SPACE = "\u200B"
    }
}

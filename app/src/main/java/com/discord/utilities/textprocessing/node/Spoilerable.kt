package com.discord.utilities.textprocessing.node

/**
 * Implemented by nodes that must render differently while they are hidden inside an unrevealed
 * spoiler (emoji and code blocks paint themselves with the spoiler colour instead of their own).
 */
interface Spoilerable {
    var isRevealed: Boolean
}

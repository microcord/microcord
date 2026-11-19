package com.discord.api.message.reaction

/**
 * One emoji's reactions on a message. [count] is every reaction with it; super ("burst") reactions
 * are split out in [countDetails], which a reaction built before they existed leaves null.
 */
data class MessageReaction(
    val count: Int,
    val emoji: MessageReactionEmoji,
    val me: Boolean,
    val countDetails: CountDetails? = null,
    val meBurst: Boolean = false,
    /** Hex colours the burst pill and its effect are drawn with, picked by the server per emoji. */
    val burstColors: List<String>? = null,
) {
    data class CountDetails(val normal: Int, val burst: Int)

    val normalCount: Int get() = countDetails?.normal ?: count
    val burstCount: Int get() = countDetails?.burst ?: 0

    companion object {
        const val TYPE_NORMAL = 0
        const val TYPE_BURST = 1
    }
}

data class MessageReactionEmoji(
    val id: String?,
    val name: String?,
    val animated: Boolean,
) {
    val reactionKey: String
        get() = id ?: requireNotNull(name)

    val isCustom: Boolean
        get() = id != null
}

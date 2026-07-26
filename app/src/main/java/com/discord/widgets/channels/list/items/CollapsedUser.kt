package com.discord.widgets.channels.list.items

import com.discord.models.user.User
import com.discord.utilities.mg_recycler.MGRecyclerDataPayload
import com.discord.utilities.user.UserUtils

/**
 * One avatar in the collapsed pile drawn on a voice channel row.
 *
 * [emptySlot] marks the trailing "+N" bubble, which carries [extraCount] instead of a user.
 */
data class CollapsedUser(
    val user: User,
    val emptySlot: Boolean = false,
    val extraCount: Long = 0,
) : MGRecyclerDataPayload {
    override val type: Int = 0
    override val key: String = user.id.toString()

    companion object {
        /** The overflow bubble. The reference caps the printed count at 99. */
        @JvmStatic
        @JvmOverloads
        fun createEmptyUser(extraCount: Long = 0): CollapsedUser =
            CollapsedUser(UserUtils.EMPTY_USER, true, minOf(99L, extraCount))
    }
}

package com.discord.widgets.channels.list.items

import com.discord.models.user.CoreUser
import com.discord.models.user.User

data class CollapsedUser(
    val user: User,
    val emptySlot: Boolean = false,
    val extraCount: Long = 0,
) {
    val key: String = user.id.toString()
    val type: Int = 0

    companion object {
        fun createEmptyUser(extraCount: Long = 0) = CollapsedUser(
            user = CoreUser(0),
            emptySlot = true,
            extraCount = extraCount.coerceAtMost(99),
        )
    }
}

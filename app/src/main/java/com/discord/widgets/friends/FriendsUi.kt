package com.discord.widgets.friends

/**
 * The lightweight user/relationship projection still used by the **blocked users** screen
 * (`widgets/settings/account/WidgetSettingsBlockedUsers`).
 *
 * The friends list itself no longer goes through these types: it is driven by
 * [FriendsListViewModel], which works on the real `com.discord.models.user.User`,
 * `com.discord.models.presence.Presence` and `FriendSuggestion` models the way the reference does.
 * What is left here is only what the blocked-users screen needs, and it belongs with that screen —
 * moving it there is a follow-up for whoever owns `widgets/settings`.
 */
data class FriendUserUi(
    val id: Long,
    val username: String,
    val discriminator: Int = 0,
    val avatarUrl: String? = null,
)

enum class FriendPresence { ONLINE, IDLE, DND, OFFLINE }

enum class FriendRelationship { FRIEND, BLOCKED, INCOMING, OUTGOING }

data class FriendRelationshipUi(
    val user: FriendUserUi,
    val relationship: FriendRelationship,
    val presence: FriendPresence = FriendPresence.OFFLINE,
    val activity: String? = null,
)

data class BlockedUserUi(val user: FriendUserUi)

sealed class BlockedUsersState {
    data object Loading : BlockedUsersState()
    data class Loaded(val users: List<BlockedUserUi>) : BlockedUsersState()
}

object BlockedUsersBuilder {
    fun build(users: Collection<FriendRelationshipUi>) = BlockedUsersState.Loaded(
        users.filter { it.relationship == FriendRelationship.BLOCKED }
            .sortedBy { it.user.username.lowercase() }
            .map { BlockedUserUi(it.user) },
    )
}

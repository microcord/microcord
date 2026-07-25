package com.discord.widgets.friends

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

data class FriendSuggestionUi(val user: FriendUserUi, val publicName: String? = null, val affinityRank: Int = Int.MAX_VALUE)

data class FriendsListInput(
    val loading: Boolean = false,
    val relationships: List<FriendRelationshipUi> = emptyList(),
    val suggestions: List<FriendSuggestionUi> = emptyList(),
    val suggestionsExpanded: Boolean = false,
    val pendingExpanded: Boolean = false,
)

sealed class FriendsListState {
    data object Loading : FriendsListState()
    data object Empty : FriendsListState()
    data class Loaded(val rows: List<FriendRow>) : FriendsListState()
}

enum class FriendSection { SUGGESTIONS, PENDING, ONLINE, OFFLINE }

sealed class FriendRow(val stableId: Long) {
    data class Header(
        val section: FriendSection,
        val count: Int,
        val expanded: Boolean = false,
        val expandable: Boolean = false,
    ) : FriendRow(Long.MIN_VALUE + section.ordinal)

    data class Friend(val value: FriendRelationshipUi) : FriendRow(value.user.id shl 3)
    data class Pending(val value: FriendRelationshipUi) : FriendRow((value.user.id shl 3) or 1)
    data class Suggestion(val value: FriendSuggestionUi) : FriendRow((value.user.id shl 3) or 2)
}

object FriendsListBuilder {
    fun build(input: FriendsListInput): FriendsListState {
        if (input.loading) return FriendsListState.Loading
        val relationships = input.relationships.filterNot { it.relationship == FriendRelationship.BLOCKED }
        val pending = relationships.filter {
            it.relationship == FriendRelationship.INCOMING || it.relationship == FriendRelationship.OUTGOING
        }.sortedWith(compareBy<FriendRelationshipUi>({ it.relationship != FriendRelationship.INCOMING }, { it.user.username.lowercase() }))
        val friends = relationships.filter { it.relationship == FriendRelationship.FRIEND }
        val online = friends.filter { it.presence != FriendPresence.OFFLINE }.sortedBy { it.user.username.lowercase() }
        val offline = friends.filter { it.presence == FriendPresence.OFFLINE }.sortedBy { it.user.username.lowercase() }
        val suggestions = input.suggestions.sortedWith(compareBy<FriendSuggestionUi> { it.affinityRank }.thenBy { it.user.id })
        val rows = buildList {
            if (suggestions.isNotEmpty()) {
                add(FriendRow.Header(FriendSection.SUGGESTIONS, suggestions.size, input.suggestionsExpanded, suggestions.size > 2))
                addAll((if (input.suggestionsExpanded) suggestions else suggestions.take(2)).map(FriendRow::Suggestion))
            }
            if (pending.isNotEmpty()) {
                add(FriendRow.Header(FriendSection.PENDING, pending.size, input.pendingExpanded, pending.size > 2))
                addAll((if (input.pendingExpanded) pending else pending.take(2)).map(FriendRow::Pending))
            }
            if (online.isNotEmpty()) {
                add(FriendRow.Header(FriendSection.ONLINE, online.size))
                addAll(online.map(FriendRow::Friend))
            }
            if (offline.isNotEmpty()) {
                add(FriendRow.Header(FriendSection.OFFLINE, offline.size))
                addAll(offline.map(FriendRow::Friend))
            }
        }
        return if (rows.isEmpty()) FriendsListState.Empty else FriendsListState.Loaded(rows)
    }
}

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

data class UsernameTag(val username: String, val discriminator: Int)

object FriendUsernameValidator {
    private val pattern = Regex("^(.+)#(\\d{4})$")

    fun parse(value: String): UsernameTag? {
        val match = pattern.matchEntire(value.trim()) ?: return null
        return UsernameTag(match.groupValues[1], match.groupValues[2].toInt())
    }
}

sealed class AddFriendResult {
    data object Success : AddFriendResult()
    data class Error(val message: String) : AddFriendResult()
    data class Captcha(val siteKey: String, val rqtoken: String? = null) : AddFriendResult()
}

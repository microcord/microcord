package com.discord.api.friendsuggestions

import com.discord.api.user.User

data class FriendSuggestion(
    val suggestedUser: User,
    val reasons: List<FriendSuggestionReason>,
)

data class BulkFriendSuggestions(
    val bulkAddToken: String,
    val friendSuggestions: List<FriendSuggestion>,
) {
    override fun toString() = "BulkFriendSuggestions(bulkAddToken=<redacted>, friendSuggestions=$friendSuggestions)"
}

data class BulkAddFriendsResponse(
    val successfulRequests: List<String>,
    val failedRequests: List<String>,
)

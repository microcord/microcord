package com.discord.api.creatormonetization

/** A guild's outstanding application to enable creator monetization. */
data class CreatorMonetizationEnableRequest(
    val id: Long,
    val state: State,
) {
    /** Where the application currently sits in review. */
    enum class State {
        OPEN,
        REJECTED,
        APPROVED,
    }
}

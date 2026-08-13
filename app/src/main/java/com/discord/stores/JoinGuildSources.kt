package com.discord.stores

/**
 * Reference `JoinGuildSources`: the `source` reported in `X-Context-Properties` on
 * `PUT /guilds/{id}/members/@me`. Only the sources this app can reach are listed; a join with no
 * source sends `{}`, as the reference does.
 */
object JoinGuildSources {
    const val CHAT_INPUT_BLOCKER = "Chat Input Blocker - Lurker Mode"

    /** The reference reports mobile discovery lurks under its own platform-tagged source. */
    const val MOBILE_GUILD_DISCOVERY = "iOS Guild Discovery - Lurker"

    const val GUILD_PROFILE_LURKER = "Guild Profile - Lurker Mode"

    /** A directory server row. */
    const val DIRECTORY_ENTRY = "Directory Channel Entry"

    /** A directory event card, which the reference reports separately from the row. */
    const val DIRECTORY_EVENTS = "Directory Channel Events"
}

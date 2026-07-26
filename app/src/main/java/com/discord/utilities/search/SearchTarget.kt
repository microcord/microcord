package com.discord.utilities.search

/**
 * What a message search is scoped to — one guild, or one private channel.
 *
 * In the reference this is `StoreSearch.SearchTarget`. `StoreSearch` does not exist in this project
 * yet, and the whole search domain layer (history cache, fetcher, query store) is keyed on it, so it
 * lives here for now. **When the store wave lands `StoreSearch`, move this type back inside it** —
 * see `docs/parity-audit/followups.md`.
 *
 * The declaration order of [Type] matters: it is dispatched on by ordinal in the reference
 * (`GUILD` first, then `CHANNEL`), which is what selects the guild-scoped vs channel-scoped REST
 * endpoint and search-data builder.
 */
data class SearchTarget(val type: Type, val id: Long) {
    enum class Type { GUILD, CHANNEL }
}

package com.discord.utilities.search.network.state

/**
 * Where one search request is in its life.
 *
 * [INDEXING] is a real server answer, not a client state: the guild's message index is still being
 * built and the request should be retried after the delay the server named.
 *
 * Ordinal order is `NONE, INDEXING, IN_PROGRESS, LOADING_MORE, COMPLETED, FAILED` (recovered from
 * the enum's static initialiser; the decompiled source lists them alphabetically).
 */
enum class QueryFetchState {
    NONE,
    INDEXING,
    IN_PROGRESS,
    LOADING_MORE,
    COMPLETED,
    FAILED,
}

package com.discord.utilities.mg_recycler

/** Wraps an arbitrary value as a list row for adapters that render exactly one row type. */
data class SingleTypePayload<T>(
    val data: T,
    override val key: String,
    override val type: Int = 0,
) : MGRecyclerDataPayload

package com.discord.api.activity

data class ActivityTimestamps(
    private val start: String?,
    private val end: String?,
) {
    val startMs: Long
        get() = parse(start)
    val endMs: Long
        get() = parse(end)

    private fun parse(value: String?): Long {
        if (value.isNullOrEmpty()) return 0
        return runCatching {
            value.toLong().let { if (value.length < 13) it * 1_000 else it }
        }.getOrDefault(0)
    }
}

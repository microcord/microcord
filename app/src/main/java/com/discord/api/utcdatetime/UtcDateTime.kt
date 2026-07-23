package com.discord.api.utcdatetime

data class UtcDateTime(val dateTimeMillis: Long) : Comparable<UtcDateTime> {
    override fun compareTo(other: UtcDateTime) = dateTimeMillis.compareTo(other.dateTimeMillis)
}

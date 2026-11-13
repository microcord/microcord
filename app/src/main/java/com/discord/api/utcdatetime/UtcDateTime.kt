package com.discord.api.utcdatetime

import com.discord.utilities.persister.CachedFormat

@CachedFormat
data class UtcDateTime(val dateTimeMillis: Long) : Comparable<UtcDateTime> {
    override fun compareTo(other: UtcDateTime) = dateTimeMillis.compareTo(other.dateTimeMillis)
}

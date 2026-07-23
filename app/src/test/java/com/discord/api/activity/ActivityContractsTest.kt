package com.discord.api.activity

import com.google.gson.GsonBuilder
import org.junit.Assert.assertEquals
import org.junit.Test

class ActivityContractsTest {
    private val gson = GsonBuilder()
        .registerTypeAdapter(ActivityType::class.java, ActivityTypeTypeAdapter())
        .registerTypeAdapter(ActivityPlatform::class.java, ActivityPlatformTypeAdapter())
        .create()

    @Test
    fun normalizesTimestampsAndUnknownEnums() {
        assertEquals(1_000_000L, ActivityTimestamps("1000", null).startMs)
        assertEquals(1_650_000_000_000L, ActivityTimestamps("1650000000000", null).startMs)
        assertEquals(0L, ActivityTimestamps("invalid", null).startMs)
        assertEquals(ActivityType.UNKNOWN, gson.fromJson("99", ActivityType::class.java))
        assertEquals(ActivityPlatform.UNKNOWN, gson.fromJson("\"future_console\"", ActivityPlatform::class.java))
        assertEquals("\"spotify\"", gson.toJson(ActivityPlatform.SPOTIFY, ActivityPlatform::class.java))
    }
}

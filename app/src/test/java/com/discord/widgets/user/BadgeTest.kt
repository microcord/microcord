package com.discord.widgets.user

import com.discord.api.user.UserProfile
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BadgeTest {
    private val gson = GsonBuilder()
        .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
        .create()

    @Test
    fun `modern profile badges preserve server order and unknown ids`() {
        val profile = gson.fromJson(
            """{
                "user":{"id":"2","username":"target"},
                "badges":[
                    {"id":"new_badge","description":"A new badge","icon":"abc123","link":"https://discord.com/example"},
                    {"id":"malformed","description":"No asset","icon":""}
                ],
                "guild_badges":[
                    {"id":"unknown_guild_badge","description":"Unknown is safe","icon":"def456"}
                ]
            }""".trimIndent(),
            UserProfile::class.java,
        )

        val badges = Badge.getModernBadges(profile)!!

        assertEquals(listOf("unknown_guild_badge", "new_badge"), badges.map(Badge::badgeId))
        assertEquals(
            listOf(
                "https://cdn.discordapp.com/badge-icons/def456.png",
                "https://cdn.discordapp.com/badge-icons/abc123.png",
            ),
            badges.map(Badge::iconUrl),
        )
        assertEquals(listOf("Unknown is safe", "A new badge"), badges.map(Badge::tooltip))
        assertEquals("https://discord.com/example", badges.last().link)
    }

    @Test
    fun `absent modern arrays retain the legacy fallback signal`() {
        assertNull(Badge.getModernBadges(UserProfile()))
        assertEquals(emptyList<Badge>(), Badge.getModernBadges(UserProfile(badges = emptyList())))
    }
}

package com.discord.models.user

import com.discord.api.user.User
import com.discord.nullserializable.NullSerializable
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreUserTest {
    @Test
    fun mergesOmittedAndExplicitNullFields() {
        val old = CoreUser(1, "old", avatar = "avatar", banner = "banner", globalName = "Old")
        val omitted = CoreUser.merge(old, User(1, "new", globalName = "Display"))
        assertEquals("avatar", omitted.avatar)
        assertEquals("Display", omitted.globalName)
        val cleared = CoreUser.merge(old, User(1, "new", avatar = NullSerializable.Null()))
        assertNull(cleared.avatar)
        assertNull(cleared.globalName)
        assertTrue(User(1, "name", token = "secret").toString().contains("token=<redacted>"))
    }

    @Test
    fun parsesAndPreservesPrimaryGuild() {
        val api = GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .create()
            .fromJson(
                """{"id":"1","username":"name","discriminator":"0","primary_guild":{"identity_enabled":true,"identity_guild_id":"2","tag":"TEST","badge":"hash"}}""",
                User::class.java,
            )
        val user = CoreUser(api)

        assertEquals(2L, user.primaryGuild?.identityGuildId)
        assertEquals("TEST", user.primaryGuild?.tag)
        assertEquals(user.primaryGuild, CoreUser.merge(user, User(1, "renamed")).primaryGuild)
    }
}

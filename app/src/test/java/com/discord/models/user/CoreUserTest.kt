package com.discord.models.user

import com.discord.api.user.User
import com.discord.nullserializable.NullSerializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreUserTest {
    @Test
    fun mergesOmittedAndExplicitNullFields() {
        val old = CoreUser(1, "old", avatar = "avatar", banner = "banner")
        val omitted = CoreUser.merge(old, User(1, "new"))
        assertEquals("avatar", omitted.avatar)
        val cleared = CoreUser.merge(old, User(1, "new", avatar = NullSerializable.Null()))
        assertNull(cleared.avatar)
        assertTrue(User(1, "name", token = "secret").toString().contains("token=<redacted>"))
    }
}

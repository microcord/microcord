package com.discord.views.user

import com.discord.models.user.CoreUser
import org.junit.Assert.assertEquals
import org.junit.Test

class UserViewFormattingTest {
    @Test
    fun preservesLegacyDiscriminatorAndMediaProxySizing() {
        assertEquals("member", formatUsername(CoreUser(1, "member")))
        assertEquals("member#0042", formatUsername(CoreUser(1, "member", discriminator = 42)))
        assertEquals(80, mediaProxySize(72))
    }
}

package com.discord.views.user

import com.discord.models.user.CoreUser
import org.junit.Assert.assertEquals
import org.junit.Test

class UserViewFormattingTest {
    @Test
    fun preservesLegacyDiscriminatorAndMediaProxySizing() {
        assertEquals("member#0000", formatUsername(CoreUser(1, "member")))
        assertEquals(80, mediaProxySize(72))
    }
}

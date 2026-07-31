package com.discord.stores

import com.discord.api.user.User
import com.google.gson.Gson
import org.junit.Assert.assertFalse
import org.junit.Test

class StoreUserPresenceUpdateTest {
    @Test
    fun `partial presence user without discriminator is ignored`() {
        val partial = Gson().fromJson("{\"id\":42}", User::class.java)

        assertFalse(isCompletePresenceUser(partial))
    }
}

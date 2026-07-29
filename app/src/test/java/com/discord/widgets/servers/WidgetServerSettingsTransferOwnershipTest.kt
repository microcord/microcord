package com.discord.widgets.servers

import com.discord.api.premium.PremiumTier
import com.discord.api.user.NsfwAllowance
import com.discord.models.guild.Guild
import com.discord.models.user.CoreUser
import com.discord.models.user.MeUser
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetServerSettingsTransferOwnershipTest {
    @Test fun `model preserves MFA and guild ownership`() {
        val owned = WidgetServerSettingsTransferOwnership.createModel(
            Guild(id = 1, ownerId = 7),
            CoreUser(id = 8, username = "target"),
            me(id = 7, mfaEnabled = true),
        )!!
        val joined = WidgetServerSettingsTransferOwnership.createModel(
            Guild(id = 1, ownerId = 9),
            owned.user,
            me(id = 7, mfaEnabled = false),
        )!!

        assertTrue(owned.isMfaEnabled)
        assertTrue(owned.isGuildOwner)
        assertFalse(joined.isMfaEnabled)
        assertFalse(joined.isGuildOwner)
        assertNull(WidgetServerSettingsTransferOwnership.createModel(null, owned.user, me(7, false)))
    }

    private fun me(id: Long, mfaEnabled: Boolean) = MeUser(
        id = id, username = "me", avatar = null, banner = null, isBot = false,
        isSystemUser = false, discriminator = 1, premiumTier = PremiumTier.NONE, email = null,
        mfaEnabled = mfaEnabled, isVerified = true, token = null, flags = 0, publicFlags = 0,
        phoneNumber = null, nsfwAllowance = NsfwAllowance.UNKNOWN, bio = null, bannerColor = null,
    )
}

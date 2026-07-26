package com.discord.widgets.hubs

import com.discord.api.hubs.EmailVerification
import com.discord.api.hubs.GuildInfo
import com.discord.models.hubs.DirectoryEntryCategory
import com.discord.stores.utilities.Default
import com.discord.stores.utilities.Loading
import com.discord.stores.utilities.Success
import com.discord.widgets.hubs.events.HubEventsPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HubStateTest {

    @Test
    fun multipleDomainsOnlyWhenTheLookupReturnedGuilds() {
        assertFalse(HubEmailState().hasMultipleDomains)
        assertFalse(
            HubEmailState(verifyEmailAsync = Success(EmailVerification(emptyList(), true))).hasMultipleDomains,
        )
        assertTrue(
            HubEmailState(
                verifyEmailAsync = Success(EmailVerification(listOf(guildInfo()), false)),
            ).hasMultipleDomains,
        )
        // An in-flight lookup has no response to read, so it is never "multiple".
        assertFalse(HubEmailState(verifyEmailAsync = Loading).hasMultipleDomains)
    }

    @Test
    fun categoryIsUnresolvedUntilOneIsPicked() {
        assertNull(HubDescriptionState().selectedCategory)
    }

    @Test
    fun categoryKeysResolveAgainstTheSchoolCategorySet() {
        // A null channel is not an HQ directory, so the school list is used. The two lists reuse the
        // same keys, which is exactly why the channel has to take part in the lookup.
        assertEquals(
            DirectoryEntryCategory.SchoolClub,
            HubDescriptionState(primaryCategoryId = DirectoryEntryCategory.SchoolClub.key).selectedCategory,
        )
        assertEquals(
            DirectoryEntryCategory.Uncategorized,
            HubDescriptionState(primaryCategoryId = 999).selectedCategory,
        )
    }

    @Test
    fun guildInitialsDropPossessivesAndWhitespace() {
        assertEquals("SU", computeGuildInitials("Springfield University"))
        assertEquals("HN", computeGuildInitials("Homer's Nuclear"))
        assertEquals("A", computeGuildInitials("Anon"))
    }

    @Test
    fun hubStoresStartUninstalledAndReportIt() {
        HubStores.reset()
        assertFalse(HubStores.isFullyInstalled)
        // The stand-ins answer "nothing loaded", which is what drives the screens' empty states.
        assertNull(HubStores.directories.getGuildScheduledEventsForChannel(1L))
        assertEquals(emptySet<Long>(), HubStores.guildScheduledEvents.getMeGuildScheduledEventIds(1L))
        assertEquals(0L, HubStores.voiceChannelSelection.getSelectedVoiceChannelId())
    }

    @Test
    fun eventRowsCarryStableKeysAndTheirOwnViewTypes() {
        assertEquals(HubEventsPage.HEADER, HubEventsPage.Header.type)
        assertEquals(HubEventsPage.FOOTER, HubEventsPage.Footer(Default).type)
        // Header and footer are singletons in the list, so a constant key is stable by construction.
        assertTrue(HubEventsPage.Header.key != HubEventsPage.Footer(Default).key)
    }

    private fun guildInfo() = GuildInfo(icon = null, id = 1L, name = "Springfield University")
}

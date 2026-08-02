package com.discord.widgets.emoji

import com.discord.models.guild.Guild
import com.discord.widgets.emoji.EmojiSheetViewModel.Companion.CustomEmojiGuildInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A custom emoji can come from a server the viewer is not in, so the popout has to describe the
 * source guild along two independent axes: is it *discoverable* (can we show its public profile and
 * offer "Join"?) and are we *in* it (can we favourite the emoji?).
 *
 * The four resolutions the view model produces map onto these two flags, and two different surfaces
 * read them — the guild card and the analytics event — so the pairing is pinned here.
 */
class EmojiSheetGuildInfoTest {

    private fun guild(id: Long = 1L) = Guild(id = id, name = "Wumpus HQ")

    /** In one of my guilds, and that guild is discoverable: everything is available. */
    @Test
    fun `a discoverable guild I am in is public and joinable-from`() {
        val info = CustomEmojiGuildInfo.Known(guild(), isPublic = true, isUserInGuild = true, approximateOnline = 12)
        assertTrue(info.isPublic)
        assertTrue(info.isUserInGuild)
        assertEquals(12, requireNotNull(info.approximateOnline))
    }

    /**
     * In one of my guilds but it is not discoverable — or it 404'd out of discovery since. The
     * public profile is dropped (no online count) but favouriting still works.
     */
    @Test
    fun `a private guild I am in keeps membership and loses the public profile`() {
        val info = CustomEmojiGuildInfo.Known(guild(), isPublic = false, isUserInGuild = true, approximateOnline = null)
        assertFalse(info.isPublic)
        assertTrue(info.isUserInGuild)
        assertNull(info.approximateOnline)
    }

    /** Resolved only through `GET /emojis/{id}/guild`: public, but not mine. */
    @Test
    fun `a guild I am not in is public and not joined`() {
        val info = CustomEmojiGuildInfo.Known(guild(), isPublic = true, isUserInGuild = false, approximateOnline = 900)
        assertTrue(info.isPublic)
        assertFalse(info.isUserInGuild)
    }

    /**
     * Nothing resolved. Both flags are false, which is what makes the guild card hide itself and
     * the favourite star unavailable — the emoji renders alone rather than under an empty card.
     */
    @Test
    fun `Unknown is neither public nor joined`() {
        assertFalse(CustomEmojiGuildInfo.Unknown.isPublic)
        assertFalse(CustomEmojiGuildInfo.Unknown.isUserInGuild)
    }

}

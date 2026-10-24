package com.discord.widgets.chat.list.entries

import com.discord.models.message.Message
import com.discord.widgets.auth.WidgetOauth2Authorize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApplicationLinkEntryTest {

    @Test
    fun `only app install links get a card, once per application`() {
        val content = "add https://discord.com/oauth2/authorize?client_id=15 and " +
            "https://canary.discord.com/oauth2/authorize?client_id=16&scope=applications.commands " +
            "https://discord.com/oauth2/authorize?client_id=15 " +
            "https://discord.com/oauth2/authorize?client_id=17&scope=bot+applications.commands " +
            "https://discordapp.com/oauth2/authorize?client_id=18&scope=identify"
        val entries = ChatListEntry.createApplicationLinkEntries(Message(id = 1, channelId = 2, content = content))
        assertEquals(listOf(15L, 16L), entries.map { (it as ApplicationLinkEntry).applicationId })
    }

    @Test
    fun `a bare link installs the app instead of asking for scopes`() {
        assertEquals(15L, WidgetOauth2Authorize.installApplicationId(mapOf("client_id" to "15")))
        assertNull(WidgetOauth2Authorize.installApplicationId(mapOf("client_id" to "15", "scope" to "identify")))
        assertNull(WidgetOauth2Authorize.installApplicationId(mapOf("client_id" to "15", "integration_type" to "1")))
    }
}

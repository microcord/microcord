package com.discord.widgets.user.presence

import com.discord.api.activity.ActivityAssets
import com.discord.api.application.Application
import com.discord.api.presence.ClientStatus
import com.discord.models.presence.Presence
import com.discord.utilities.presence.createPlayingActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelRichPresenceTest {
    @Test
    fun applicationIconReplacesOnlyTheGenericPlaceholderNotActivityArtwork() {
        val application = Application(100L, "Game", null, null, null, "icon", null, null, null, null, null)
        val activity = requireNotNull(createPlayingActivity("Game", 1L)).copy(applicationId = application.id)
        val model = ModelRichPresence(Presence(ClientStatus.ONLINE, null, listOf(activity)), application)
        val iconUrl = "https://cdn.discordapp.com/app-icons/100/icon.png?size=160"
        assertEquals(iconUrl, model.applicationIconFallback)
        assertNull(model.copy(primaryApplication = null).applicationIconFallback)
        assertNull(model.copy(primaryApplication = application.copy(icon = "")).applicationIconFallback)

        for (assets in listOf(
            ActivityAssets("large", null, null, null),
            ActivityAssets(null, null, "small", null),
            ActivityAssets("mp:external/test/image.png", null, null, null),
            ActivityAssets("spotify:album", null, null, null),
        )) {
            assertNull(model.copy(presence = model.presence.copy(activities = listOf(activity.copy(assets = assets))))
                .applicationIconFallback)
        }
        assertEquals(iconUrl, model.copy(presence = model.presence.copy(activities = listOf(
            activity.copy(assets = ActivityAssets("", null, null, null)),
        ))).applicationIconFallback)
    }
}

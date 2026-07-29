package com.discord.widgets.guilds

import com.discord.models.domain.ModelGuildFolder
import com.discord.widgets.guilds.invite.GuildInviteShareSheetViewModel
import com.discord.widgets.guilds.invite.InviteSuggestionItemV2
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import rx.Observable
import rx.android.plugins.RxAndroidPlugins
import rx.android.plugins.RxAndroidSchedulersHook
import rx.schedulers.Schedulers
import rx.plugins.RxJavaHooks

class GuildIndependentViewModelsTest {
    @Before fun setUpMainScheduler() {
        RxJavaHooks.setOnComputationScheduler { Schedulers.immediate() }
        RxAndroidPlugins.getInstance().reset()
        RxAndroidPlugins.getInstance().registerSchedulersHook(
            object : RxAndroidSchedulersHook() {
                override fun getMainThreadScheduler() = Schedulers.immediate()
            },
        )
    }

    @After fun resetMainScheduler() {
        RxAndroidPlugins.getInstance().reset()
        RxJavaHooks.reset()
    }

    @Test fun `folder edits expose save only after a real change`() {
        val folder = ModelGuildFolder(7L, listOf(1L), 0x123456, "Friends")
        val model = WidgetGuildFolderSettingsViewModel(
            folderId = 7L,
            storeStateObservable = Observable.just(
                WidgetGuildFolderSettingsViewModel.StoreState.Valid(folder, emptyList()),
            ),
        )

        assertFalse((model.requireViewState() as WidgetGuildFolderSettingsViewModel.ViewState.Loaded).showSave)
        model.setName("Games")
        assertTrue((model.requireViewState() as WidgetGuildFolderSettingsViewModel.ViewState.Loaded).showSave)
        model.setName("Friends")
        assertFalse((model.requireViewState() as WidgetGuildFolderSettingsViewModel.ViewState.Loaded).showSave)
    }

    @Test fun `search placeholder is not counted as a search result`() {
        val empty = GuildInviteShareSheetViewModel.ViewState(
            invite = null,
            inviteSettings = null,
            inviteSuggestionItems = listOf(InviteSuggestionItemV2.SearchNoResultsItem),
            channel = null,
            showInviteSettings = true,
            guildId = 1L,
        )

        assertTrue(empty.hasResults)
        assertFalse(empty.hasSearchResults)
    }
}

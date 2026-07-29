package com.discord.widgets.guilds.contextmenu

import com.discord.models.domain.ModelGuildFolder
import com.discord.models.guild.Guild
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import rx.Observable
import rx.android.plugins.RxAndroidPlugins
import rx.android.plugins.RxAndroidSchedulersHook
import rx.schedulers.Schedulers

class ContextMenuViewModelTest {
    @Before fun setUpMainScheduler() {
        RxAndroidPlugins.getInstance().reset()
        RxAndroidPlugins.getInstance().registerSchedulersHook(
            object : RxAndroidSchedulersHook() {
                override fun getMainThreadScheduler() = Schedulers.immediate()
            },
        )
    }

    @After fun resetMainScheduler() = RxAndroidPlugins.getInstance().reset()

    @Test fun `guild owner cannot leave and unread guild can be acknowledged`() {
        val state = GuildContextMenuViewModel(
            guildId = 1,
            storeStateObservable = Observable.just(
                GuildContextMenuViewModel.StoreState.Valid(
                    Guild(id = 1, name = "Guild", ownerId = 7),
                    myUserId = 7,
                    isGuildUnread = true,
                    selectedGuildId = 1,
                ),
            ),
        ).requireViewState() as GuildContextMenuViewModel.ViewState.Valid

        assertTrue(state.showMarkAsRead)
        assertFalse(state.showLeaveGuild)
        assertTrue(state.isGuildSelected)
    }

    @Test fun `folder only offers mark read when one of its guilds is unread`() {
        val folder = ModelGuildFolder(10, listOf(1, 2), name = "Folder")
        val unread = FolderContextMenuViewModel(
            10,
            Observable.just(FolderContextMenuViewModel.StoreState.Valid(folder, setOf(2))),
        ).requireViewState() as FolderContextMenuViewModel.ViewState.Valid
        val read = FolderContextMenuViewModel(
            10,
            Observable.just(FolderContextMenuViewModel.StoreState.Valid(folder, setOf(3))),
        ).requireViewState() as FolderContextMenuViewModel.ViewState.Valid

        assertTrue(unread.showMarkAsRead)
        assertFalse(read.showMarkAsRead)
    }
}

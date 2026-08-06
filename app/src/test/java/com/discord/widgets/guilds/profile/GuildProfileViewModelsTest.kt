package com.discord.widgets.guilds.profile

import com.discord.api.guild.GuildFeature
import com.discord.api.guild.preview.GuildPreview
import com.discord.api.premium.PremiumTier
import com.discord.api.user.NsfwAllowance
import com.discord.models.domain.ModelNotificationSettings
import com.discord.models.guild.Guild
import com.discord.models.member.GuildMember
import com.discord.models.user.MeUser
import com.discord.restapi.RestAPIInterface
import com.discord.stores.StoreGuildProfiles
import com.discord.utilities.channel.GuildChannelsInfo
import com.discord.utilities.permissions.ManageGuildContext
import java.lang.reflect.Proxy
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import rx.Observable
import rx.android.plugins.RxAndroidPlugins
import rx.android.plugins.RxAndroidSchedulersHook
import rx.schedulers.Schedulers

class GuildProfileViewModelsTest {
    @Before fun setUpMainScheduler() {
        RxAndroidPlugins.getInstance().reset()
        RxAndroidPlugins.getInstance().registerSchedulersHook(object : RxAndroidSchedulersHook() {
            override fun getMainThreadScheduler() = Schedulers.immediate()
        })
    }

    @After fun resetMainScheduler() = RxAndroidPlugins.getInstance().reset()

    @Test fun `identity state preserves edits and tracks avatar reset as dirty`() {
        val member = member(nick = "before", avatar = "guild-avatar")
        val loaded = WidgetChangeGuildIdentityViewModel.ViewState.Loaded(
            guild(), me(), member, canChangeNickname = true, currentNickname = "before",
        )

        assertFalse(loaded.isDirty)
        assertTrue(loaded.displayingGuildAvatar)

        val reset = loaded.copy(currentAvatar = com.discord.nullserializable.NullSerializable.Null())
        assertTrue(reset.isDirty)
        assertFalse(reset.displayingGuildAvatar)
    }

    @Test fun `member profile exposes exact owner and lurking action gates`() {
        val memberState = state(guild = guild(ownerId = 7), lurking = false)
        val memberViewModel = WidgetGuildProfileSheetViewModel(
            viewingGuild = true,
            guildId = 1,
            api = noCallsApi(),
            storeObservable = Observable.just(memberState),
        )
        val memberLoaded = memberViewModel.requireViewState() as WidgetGuildProfileSheetViewModel.ViewState.Loaded

        assertFalse(memberLoaded.actions!!.canLeaveGuild)
        assertTrue(memberLoaded.tabItems != null)

        val lurkerViewModel = WidgetGuildProfileSheetViewModel(
            viewingGuild = false,
            guildId = 1,
            api = noCallsApi(),
            storeObservable = Observable.just(memberState.copy(isLurking = true)),
        )
        val lurkerLoaded = lurkerViewModel.requireViewState() as WidgetGuildProfileSheetViewModel.ViewState.Loaded
        assertTrue(lurkerLoaded.actions == null)
        assertTrue(lurkerLoaded.tabItems == null)
        assertTrue(lurkerLoaded.bottomActions.showViewServer)
    }

    @Test fun `private guild profile remains open without actions`() {
        val privateState = state(guild(), lurking = false).copy(
            guild = null,
            guildProfile = StoreGuildProfiles.GuildProfileData(
                StoreGuildProfiles.FetchStates.SUCCEEDED,
                data = null,
                isPrivate = true,
            ),
        )
        val viewModel = WidgetGuildProfileSheetViewModel(
            viewingGuild = false,
            guildId = 1,
            api = noCallsApi(),
            storeObservable = Observable.just(privateState),
        )

        val loaded = viewModel.requireViewState() as WidgetGuildProfileSheetViewModel.ViewState.Loaded
        assertTrue(loaded.isPrivate)
        assertFalse(loaded.bottomActions.showViewServer)
    }

    @Test fun `public tag profile enters through server preview`() {
        val preview = GuildPreview(
            id = 1,
            name = "Guild",
            description = null,
            splash = null,
            banner = null,
            icon = null,
            approximatePresenceCount = 2,
            approximateMemberCount = 10,
            emojis = emptyList(),
            features = listOf(GuildFeature.DISCOVERABLE),
            featurableInDirectory = true,
        )
        val previewState = state(guild(), lurking = false).copy(
            guild = null,
            guildProfile = StoreGuildProfiles.GuildProfileData(
                StoreGuildProfiles.FetchStates.SUCCEEDED,
                preview,
            ),
        )
        val viewModel = WidgetGuildProfileSheetViewModel(
            viewingGuild = false,
            guildId = 1,
            api = noCallsApi(),
            storeObservable = Observable.just(previewState),
        )

        val loaded = viewModel.requireViewState() as WidgetGuildProfileSheetViewModel.ViewState.Loaded
        assertTrue(loaded.bottomActions.showViewServer)

        val restricted = WidgetGuildProfileSheetViewModel(
            viewingGuild = false,
            guildId = 1,
            api = noCallsApi(),
            storeObservable = Observable.just(previewState.copy(
                guildProfile = StoreGuildProfiles.GuildProfileData(
                    StoreGuildProfiles.FetchStates.SUCCEEDED,
                    preview.copy(features = emptyList()),
                ),
            )),
        ).requireViewState() as WidgetGuildProfileSheetViewModel.ViewState.Loaded
        assertFalse(restricted.bottomActions.showViewServer)
    }

    private fun state(guild: Guild, lurking: Boolean) = WidgetGuildProfileSheetViewModel.StoreState(
        guild = guild,
        guildProfile = null,
        guildChannelsInfo = info(),
        me = me(),
        computedMe = member(),
        restrictedGuildIds = emptyList(),
        isDeveloper = false,
        isLurking = lurking,
        isUnread = true,
    )

    private fun info() = GuildChannelsInfo(
        guild = guild(),
        everyoneRole = null,
        notificationSettings = ModelNotificationSettings(),
        hideMutedChannels = false,
        channelPermissions = emptyMap(),
        ableToInstantInvite = true,
        unelevated = false,
        isVerifiedServer = false,
        manageGuildContext = ManageGuildContext(true, true, false, false, false, true, false, true, true),
        canChangeNickname = true,
        guildRoles = emptyMap(),
    )

    private fun guild(ownerId: Long = 9) = Guild(
        id = 1, name = "Guild", ownerId = ownerId, features = setOf(GuildFeature.VERIFIED),
    )

    private fun member(nick: String? = "nick", avatar: String? = null) = GuildMember(
        color = 0, hoistRoleId = 0, roles = emptyList(), nick = nick, premiumSince = null,
        pending = false, joinedAt = null, guildId = 1, userId = 7, avatarHash = avatar,
    )

    private fun me() = MeUser(
        id = 7, username = "me", avatar = null, banner = null, isBot = false,
        isSystemUser = false, discriminator = 1, premiumTier = PremiumTier.NONE, email = null,
        mfaEnabled = false, isVerified = true, token = null, flags = 0, publicFlags = 0,
        phoneNumber = null, nsfwAllowance = NsfwAllowance.UNKNOWN, bio = null, bannerColor = null,
    )

    private fun noCallsApi() = Proxy.newProxyInstance(
        RestAPIInterface::class.java.classLoader,
        arrayOf(RestAPIInterface::class.java),
    ) { _, method, _ -> error("Unexpected API call: ${method.name}") } as RestAPIInterface
}

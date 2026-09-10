package com.discord.widgets.guilds.profile

import androidx.annotation.StringRes
import com.discord.R
import com.discord.api.guild.GuildFeature
import com.discord.api.guild.GuildIncidentsData
import com.discord.api.guild.preview.GuildPreview
import com.discord.api.permission.Permission
import com.discord.utilities.permissions.PermissionUtils
import com.discord.models.domain.emoji.ModelEmojiCustom
import com.discord.models.guild.Guild
import com.discord.models.member.GuildMember
import com.discord.models.user.MeUser
import com.discord.restapi.RestAPIInterface
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreGuildProfiles
import com.discord.stores.JoinGuildSources
import com.discord.stores.StoreStream
import com.discord.stores.UserSettings
import com.discord.utilities.channel.GuildChannelsInfo
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.time.ClockFactory
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import rx.Observable
import rx.subjects.PublishSubject

class WidgetGuildProfileSheetViewModel(
    private val viewingGuild: Boolean,
    val guildId: Long,
    private val api: RestAPIInterface = RestAPI.api,
    storeObservable: Observable<StoreState> = observeStores(guildId),
) : com.discord.app.AppViewModel<WidgetGuildProfileSheetViewModel.ViewState>(ViewState.Loading) {
    private val eventSubject = PublishSubject.create<Event>()

    init {
        storeObservable.ui(this).appSubscribe(WidgetGuildProfileSheetViewModel::class.java) {
            handleStoreState(it)
        }
    }

    fun observeEvents(): Observable<Event> = eventSubject

    private fun handleStoreState(state: StoreState) {
        val guild = state.guild
        val profile = state.guildProfile?.data
        when {
            guild != null -> updateViewState(loadedGuild(state, guild, profile))
            state.guildProfile?.isPrivate == true -> updateViewState(loadedPrivate(guildId, state.me))
            state.guildProfile?.fetchState == StoreGuildProfiles.FetchStates.SUCCEEDED && profile != null ->
                updateViewState(loadedPreview(profile, state.me))
            state.guildProfile?.fetchState == StoreGuildProfiles.FetchStates.FAILED ||
                state.guildProfile?.fetchState == StoreGuildProfiles.FetchStates.SUCCEEDED ->
                updateViewState(ViewState.Invalid)
            else -> updateViewState(ViewState.Loading)
        }
    }

    private fun loadedGuild(state: StoreState, guild: Guild, preview: GuildPreview?): ViewState.Loaded {
        val manage = state.guildChannelsInfo.manageGuildContext
        val actions = if (state.isLurking) null else Actions(
            isUnread = state.isUnread,
            canManageChannels = manage.canManageChannels,
            canManageEvents = manage.canManageEvents,
            canChangeNickname = state.guildChannelsInfo.canChangeNickname,
            nick = state.computedMe?.nick,
            guildAvatar = state.computedMe?.avatarHash,
            isAllowDMChecked = guild.id !in state.restrictedGuildIds,
            isAllowMessageRequestsChecked = guild.id !in state.messageRequestRestrictedGuildIds,
            hideMutedChannels = state.guildChannelsInfo.hideMutedChannels,
            canLeaveGuild = !guild.isOwner(state.me.id),
            isDeveloper = state.isDeveloper,
            username = state.me.username,
            isMuted = guild.id in state.mutedGuildIds,
            isCommunity = GuildFeature.COMMUNITY in guild.features,
            showAllChannels = !state.guildChannelsInfo.notificationSettings.isOptInChannelsEnabled,
            canReportRaid = canReportRaid(guild, state.guildPermissions),
        )
        return ViewState.Loaded(
            guildId = guild.id,
            guildName = guild.name,
            guildShortName = guild.shortName,
            guildIcon = guild.icon,
            guildDescription = guild.description,
            verifiedPartneredIconRes = badge(guild.features),
            banner = Banner(guild.id, guild.banner, Banner.Type.BANNER),
            onlineCount = preview?.approximatePresenceCount,
            memberCount = preview?.approximateMemberCount,
            tabItems = if (state.isLurking) null else TabItems(
                manage.canManage(), state.guildChannelsInfo.ableToInstantInvite, guild.premiumSubscriptionCount,
            ),
            actions = actions,
            bottomActions = BottomActions(
                showUploadEmoji = !state.isLurking && manage.canManageEmojisAndStickers,
                showViewServer = !viewingGuild || state.isLurking,
            ),
            isGuildHub = guild.isHub(),
            meUser = state.me,
            emojis = if (state.isLurking) emptyList() else state.emojis,
        )
    }

    private fun loadedPreview(preview: GuildPreview, me: MeUser) = ViewState.Loaded(
        guildId = preview.id,
        guildName = preview.name,
        guildShortName = Guild.toShortName(preview.name),
        guildIcon = preview.icon,
        guildDescription = preview.description,
        verifiedPartneredIconRes = badge(preview.features),
        banner = Banner(preview.id, preview.splash, Banner.Type.SPLASH),
        onlineCount = preview.approximatePresenceCount,
        memberCount = preview.approximateMemberCount,
        tabItems = null,
        actions = null,
        bottomActions = BottomActions(false, GuildFeature.DISCOVERABLE in preview.features),
        isGuildHub = GuildFeature.HUB in preview.features,
        meUser = me,
    )

    private fun loadedPrivate(guildId: Long, me: MeUser) = ViewState.Loaded(
        guildId = guildId,
        guildName = "",
        guildShortName = "?",
        guildIcon = null,
        guildDescription = null,
        verifiedPartneredIconRes = null,
        banner = Banner(guildId, null, Banner.Type.SPLASH),
        onlineCount = null,
        memberCount = null,
        tabItems = null,
        actions = null,
        bottomActions = BottomActions(false, false),
        isGuildHub = false,
        meUser = me,
        isPrivate = true,
    )

    fun onClickViewServer(guildId: Long, channelId: Long?) {
        if (StoreStream.getGuilds().getGuild(guildId) == null) {
            StoreStream.getLurking().startLurkingAndNavigate(guildId, channelId, JoinGuildSources.GUILD_PROFILE_LURKER)
        } else if (channelId == null) {
            StoreStream.selectGuild(guildId)
        } else {
            StoreStream.selectChannel(guildId, channelId)
        }
    }

    fun onClickLeaveServer(guildId: Long, onSuccess: () -> Unit) =
        api.leaveGuild(guildId).restSubscribeOn().ui(this)
            .appSubscribe(WidgetGuildProfileSheetViewModel::class.java) { onSuccess() }

    fun onClickMarkAsRead(guildId: Long) =
        api.ackGuild(guildId).restSubscribeOn().ui(this)
            .appSubscribe(WidgetGuildProfileSheetViewModel::class.java) {
                eventSubject.onNext(Event.DismissAndShowToast(R.string.marked_as_read))
            }

    fun onClickResetNickname(guildId: Long, onSuccess: () -> Unit) =
        onClickSaveNickname(guildId, "", onSuccess)

    fun onClickSaveNickname(guildId: Long, nick: String, onSuccess: () -> Unit) =
        api.changeGuildNickname(guildId, RestAPIParams.Nick(nick)).restSubscribeOn().ui(this)
            .appSubscribe(WidgetGuildProfileSheetViewModel::class.java) { onSuccess() }

    fun setAllowDM(guildId: Long, allowed: Boolean) =
        StoreStream.getUserSettings().setRestrictedGuildId(null, guildId, !allowed)

    fun setHideMutedChannels(guildId: Long, hidden: Boolean) =
        StoreStream.getUserGuildSettings().setHideMutedChannels(guildId, hidden)

    fun setAllowMessageRequests(guildId: Long, allowed: Boolean) {
        val current = UserSettings.messageRequestRestrictedGuildIds.get().toMutableSet()
        if (allowed) current.remove(guildId) else current.add(guildId)
        UserSettings.messageRequestRestrictedGuildIds.set(current.toList())
    }

    /** Matches the reference: any moderation-shaped permission may raise a raid report. */
    private fun canReportRaid(guild: Guild, permissions: Long?): Boolean {
        if (hasDetectedActivity(guild.incidentsData)) return false
        return PermissionUtils.can(Permission.BAN_MEMBERS, permissions) ||
            PermissionUtils.can(Permission.KICK_MEMBERS, permissions) ||
            PermissionUtils.can(Permission.MODERATE_MEMBERS, permissions) ||
            PermissionUtils.can(Permission.MANAGE_GUILD, permissions)
    }

    private fun hasDetectedActivity(incidents: GuildIncidentsData?): Boolean {
        val cutoff = ClockFactory.get().currentTimeMillis() - RAID_ALERT_WINDOW_MS
        return (incidents?.raidDetectedAt?.dateTimeMillis ?: 0) > cutoff ||
            (incidents?.dmSpamDetectedAt?.dateTimeMillis ?: 0) > cutoff
    }

    fun setShowAllChannels(context: android.content.Context, guildId: Long, showAll: Boolean) =
        StoreStream.getUserGuildSettings().setGuildOptInChannels(context, guildId, optInOnly = !showAll)

    fun setMuted(context: android.content.Context, guildId: Long, muted: Boolean) =
        StoreStream.getUserGuildSettings().setGuildMuted(context, guildId, muted)

    data class Actions(
        val isUnread: Boolean,
        val canManageChannels: Boolean,
        val canManageEvents: Boolean,
        val canChangeNickname: Boolean,
        val nick: String?,
        val guildAvatar: String?,
        val isAllowDMChecked: Boolean,
        val isAllowMessageRequestsChecked: Boolean,
        val hideMutedChannels: Boolean,
        val canLeaveGuild: Boolean,
        val isDeveloper: Boolean,
        val username: String,
        val isMuted: Boolean,
        val isCommunity: Boolean,
        val showAllChannels: Boolean,
        val canReportRaid: Boolean,
    ) {
        val displayGuildIdentityRow = !nick.isNullOrEmpty() || !guildAvatar.isNullOrEmpty()
    }

    data class Banner(val guildId: Long, val hash: String?, val type: Type) {
        enum class Type { BANNER, SPLASH }
    }
    data class BottomActions(val showUploadEmoji: Boolean, val showViewServer: Boolean)
    data class TabItems(val canAccessSettings: Boolean, val ableToInstantInvite: Boolean, val premiumSubscriptionCount: Int)
    sealed class Event { data class DismissAndShowToast(@StringRes val stringRes: Int) : Event() }

    data class StoreState(
        val guild: Guild?,
        val guildProfile: StoreGuildProfiles.GuildProfileData?,
        val guildChannelsInfo: GuildChannelsInfo,
        val me: MeUser,
        val computedMe: GuildMember?,
        val restrictedGuildIds: List<Long>,
        val messageRequestRestrictedGuildIds: List<Long>,
        val mutedGuildIds: List<Long>,
        val guildPermissions: Long?,
        val isDeveloper: Boolean,
        val isLurking: Boolean,
        val isUnread: Boolean,
        val emojis: List<ModelEmojiCustom>,
    )

    sealed class ViewState {
        object Invalid : ViewState()
        object Loading : ViewState()
        data class Loaded(
            val guildId: Long,
            val guildName: String,
            val guildShortName: String,
            val guildIcon: String?,
            val guildDescription: String?,
            val verifiedPartneredIconRes: Int?,
            val banner: Banner,
            val onlineCount: Int?,
            val memberCount: Int?,
            val tabItems: TabItems?,
            val actions: Actions?,
            val bottomActions: BottomActions,
            val isGuildHub: Boolean,
            val meUser: MeUser,
            val emojis: List<ModelEmojiCustom> = emptyList(),
            val isPrivate: Boolean = false,
        ) : ViewState()
    }

    companion object {
        private const val RAID_ALERT_WINDOW_MS = 2 * 60 * 60 * 1000L

        private fun badge(features: Collection<GuildFeature>): Int? = when {
            GuildFeature.VERIFIED in features -> R.drawable.ic_verified_badge
            GuildFeature.PARTNERED in features -> R.drawable.ic_partnered_badge
            else -> null
        }

        internal fun observeStores(guildId: Long): Observable<StoreState> = Observable.combineLatest(
            listOf(
                StoreStream.getGuilds().observeGuild(guildId),
                StoreStream.getGuildProfiles().observeGuildProfile(guildId),
                StoreStream.getReadStates().getIsUnread(guildId),
                StoreStream.getUsers().observeMe(),
                StoreStream.getUsers().observeMe()
                    .flatMap { me -> StoreStream.getGuilds().observeComputedMember(guildId, me.id) },
                StoreStream.getUserSettings().observeRestrictedGuildIds(),
                UserSettings.messageRequestRestrictedGuildIds.observe(),
                StoreStream.getUserGuildSettings().observeMutedGuildIds(),
                StoreStream.getLurking().isLurkingObs(guildId),
                GuildChannelsInfo.get(guildId),
                StoreStream.getEmojis().observeGuildEmoji(guildId),
                StoreStream.getPermissions().observePermissionsForGuild(guildId),
            ),
        ) { args ->
            @Suppress("UNCHECKED_CAST")
            StoreState(
                guild = args[0] as Guild?,
                guildProfile = args[1] as StoreGuildProfiles.GuildProfileData?,
                guildChannelsInfo = args[9] as GuildChannelsInfo,
                me = args[3] as MeUser,
                computedMe = args[4] as GuildMember?,
                restrictedGuildIds = args[5] as List<Long>,
                messageRequestRestrictedGuildIds = args[6] as List<Long>,
                mutedGuildIds = args[7] as List<Long>,
                isDeveloper = StoreStream.getUserSettings().getIsDeveloperMode(),
                isLurking = args[8] as Boolean,
                isUnread = args[2] as Boolean,
                emojis = args[10] as List<ModelEmojiCustom>,
                guildPermissions = args[11] as Long?,
            )
        }
    }
}

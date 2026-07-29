package com.discord.widgets.guilds.profile

import androidx.annotation.StringRes
import com.discord.R
import com.discord.api.guild.GuildFeature
import com.discord.api.guild.preview.GuildPreview
import com.discord.models.domain.emoji.Emoji
import com.discord.models.domain.emoji.ModelEmojiCustom
import com.discord.models.guild.Guild
import com.discord.models.member.GuildMember
import com.discord.models.user.MeUser
import com.discord.restapi.RestAPIInterface
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreEmoji
import com.discord.stores.StoreGuildProfiles
import com.discord.stores.StoreStream
import com.discord.utilities.channel.GuildChannelsInfo
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.user.UserUtils.isPremium
import rx.Observable
import rx.subjects.PublishSubject

class WidgetGuildProfileSheetViewModel(
    private val viewingGuild: Boolean,
    val guildId: Long,
    private val api: RestAPIInterface = RestAPI.api,
    storeObservable: Observable<StoreState> = observeStores(guildId),
) : com.discord.app.AppViewModel<WidgetGuildProfileSheetViewModel.ViewState>(ViewState.Loading) {
    private val eventSubject = PublishSubject.create<Event>()
    private var isEmojiSectionExpanded = false

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
            hideMutedChannels = state.guildChannelsInfo.hideMutedChannels,
            canLeaveGuild = !guild.isOwner(state.me.id),
            isDeveloper = state.isDeveloper,
            username = state.me.username,
        )
        val emojis = if (state.isLurking) preview?.emojis?.map { ModelEmojiCustom(it, guild.id) } else state.emojis
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
            emojisData = EmojisData(state.me.isPremium, isEmojiSectionExpanded, emojis.orEmpty()),
            bottomActions = BottomActions(
                showUploadEmoji = !state.isLurking && manage.canManageEmojisAndStickers,
                showJoinServer = state.isLurking,
                showViewServer = !viewingGuild,
            ),
            isGuildHub = guild.isHub(),
            meUser = state.me,
        )
    }

    private fun loadedPreview(preview: GuildPreview, me: MeUser) = ViewState.Loaded(
        guildId = preview.id,
        guildName = preview.name,
        guildShortName = preview.name.replace(Regex("'s "), " ").replace(Regex("(\\w)(\\w*)"), "$1").replace(Regex("\\s"), ""),
        guildIcon = preview.icon,
        guildDescription = preview.description,
        verifiedPartneredIconRes = badge(preview.features),
        banner = Banner(preview.id, preview.splash, Banner.Type.SPLASH),
        onlineCount = preview.approximatePresenceCount,
        memberCount = preview.approximateMemberCount,
        tabItems = null,
        actions = null,
        emojisData = EmojisData(me.isPremium, isEmojiSectionExpanded, preview.emojis.map { ModelEmojiCustom(it, preview.id) }),
        bottomActions = BottomActions(false, true, !viewingGuild),
        isGuildHub = GuildFeature.HUB in preview.features,
        meUser = me,
    )

    fun onClickEmoji() {
        val state = viewState as? ViewState.Loaded ?: return
        if (isEmojiSectionExpanded) return
        isEmojiSectionExpanded = true
        updateViewState(state.copy(emojisData = state.emojisData.copy(isExpanded = true)))
    }

    fun onClickJoinServer(guildId: Long) = StoreStream.getLurking().postJoinGuildAsMember(guildId)
    fun onClickViewServer(guildId: Long, channelId: Long?) =
        StoreStream.getLurking().startLurkingAndNavigate(guildId, channelId)

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

    data class Actions(
        val isUnread: Boolean,
        val canManageChannels: Boolean,
        val canManageEvents: Boolean,
        val canChangeNickname: Boolean,
        val nick: String?,
        val guildAvatar: String?,
        val isAllowDMChecked: Boolean,
        val hideMutedChannels: Boolean,
        val canLeaveGuild: Boolean,
        val isDeveloper: Boolean,
        val username: String,
    ) {
        val displayGuildIdentityRow = !nick.isNullOrEmpty() || !guildAvatar.isNullOrEmpty()
    }

    data class Banner(val guildId: Long, val hash: String?, val type: Type) {
        enum class Type { BANNER, SPLASH }
    }
    data class BottomActions(val showUploadEmoji: Boolean, val showJoinServer: Boolean, val showViewServer: Boolean)
    data class EmojisData(val isPremium: Boolean, val isExpanded: Boolean, val emojis: List<Emoji>)
    data class TabItems(val canAccessSettings: Boolean, val ableToInstantInvite: Boolean, val premiumSubscriptionCount: Int)
    sealed class Event { data class DismissAndShowToast(@StringRes val stringRes: Int) : Event() }

    data class StoreState(
        val guild: Guild?,
        val guildProfile: StoreGuildProfiles.GuildProfileData?,
        val guildChannelsInfo: GuildChannelsInfo,
        val me: MeUser,
        val computedMe: GuildMember?,
        val restrictedGuildIds: List<Long>,
        val isDeveloper: Boolean,
        val emojis: List<Emoji>,
        val isLurking: Boolean,
        val isUnread: Boolean,
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
            val emojisData: EmojisData,
            val bottomActions: BottomActions,
            val isGuildHub: Boolean,
            val meUser: MeUser,
        ) : ViewState()
    }

    companion object {
        private fun badge(features: Collection<GuildFeature>): Int? = when {
            GuildFeature.VERIFIED in features -> R.drawable.ic_verified_badge
            GuildFeature.PARTNERED in features -> R.drawable.ic_partnered_badge
            else -> null
        }

        internal fun observeStores(guildId: Long): Observable<StoreState> = Observable.combineLatest(
            StoreStream.getGuilds().observeGuild(guildId),
            StoreStream.getGuildProfiles().observeGuildProfile(guildId),
            StoreStream.getReadStates().getIsUnread(guildId),
            StoreStream.getUsers().observeMe(),
            StoreStream.getUsers().observeMe().flatMap { me -> StoreStream.getGuilds().observeComputedMember(guildId, me.id) },
            StoreStream.getUserSettings().observeRestrictedGuildIds(),
            StoreStream.getEmojis().getEmojiSet(StoreEmoji.EmojiContext.GuildProfile(guildId), true, false)
                .map { it.customEmojis[guildId].orEmpty() },
            StoreStream.getLurking().isLurkingObs(guildId),
            GuildChannelsInfo.get(guildId),
        ) { guild, profile, unread, me, member, restricted, emojis, lurking, info ->
            StoreState(guild, profile, info, me, member, restricted, StoreStream.getUserSettings().getIsDeveloperMode(), emojis, lurking, unread)
        }
    }
}

package com.discord.widgets.channels

import android.content.Context
import androidx.annotation.MainThread
import com.discord.utilities.user.NicknameUtils
import com.discord.utilities.user.UserUtils.getDisplayName
import com.discord.api.channel.Channel
import com.discord.api.channel.getDMRecipient
import com.discord.api.channel.getDisplayName
import com.discord.api.channel.isGroup
import com.discord.api.channel.isPrivate
import com.discord.api.role.GuildRole
import com.discord.app.AppViewModel
import com.discord.models.member.GuildMember
import com.discord.models.user.User
import com.discord.panels.PanelState
import com.discord.simpleast.core.node.Node
import com.discord.simpleast.core.parser.Parser
import com.discord.stores.StoreChannels
import com.discord.stores.StoreChannelsSelected
import com.discord.stores.StoreGuilds
import com.discord.stores.StoreNavigation
import com.discord.stores.StoreStream
import com.discord.stores.StoreTabsNavigation
import com.discord.stores.StoreUser
import com.discord.stores.StoreUserSettings
import com.discord.utilities.channel.GuildChannelIconType
import com.discord.utilities.channel.getChannelType
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.textprocessing.DiscordParser
import com.discord.utilities.textprocessing.MessageParseState
import com.discord.utilities.textprocessing.MessagePreprocessor
import com.discord.utilities.textprocessing.MessageRenderContext
import com.discord.utilities.textprocessing.node.SpoilerNode
import com.discord.widgets.tabs.NavigationTab
import rx.Observable
import rx.subjects.PublishSubject

/** A parsed channel topic, ready to be handed to [com.discord.utilities.textprocessing.AstRenderer]. */
typealias AST = List<Node<MessageRenderContext>>

/** The parser configuration the topic bar uses: entity mentions on, masked links off. */
typealias TopicParser = Parser<MessageRenderContext, Node<MessageRenderContext>, MessageParseState>

/**
 * Drives [WidgetChannelTopic] — the header strip above the chat that names the current channel and
 * renders its topic.
 *
 * Four shapes of channel land on the same strip and each needs different data, so both the store
 * projection and the view state are sealed hierarchies rather than one nullable-heavy model:
 *
 * - [ViewState.NoChannel] — nothing selected; the strip hides itself;
 * - [ViewState.Guild.DefaultTopic] — a guild channel with no topic set, so only the name and the
 *   type icon are needed and no parse runs;
 * - [ViewState.Guild.Topic] — a guild channel *with* a topic: the topic is markdown and may mention
 *   users, channels and roles, so the name caches for the whole guild come along with it;
 * - [ViewState.DM] / [ViewState.GDM] — private channels, which have no topic but do have an
 *   overflow menu (pins, copy id, customise group, close/leave) and, for DMs, the per-guild
 *   nicknames [UserAkaView] shows.
 *
 * **Why the nav state gates the subscription.** Everything above is only visible while the right
 * panel is open on the home tab. When it is closed, the view model still subscribes — otherwise the
 * strip would be a frame behind when the panel opens — but takes a single emission and stops, so a
 * busy guild does not re-parse its topic on every member update behind a closed panel.
 *
 * Revealed spoilers are held here, not in the view, and are reset whenever the selected channel
 * changes so a spoiler revealed in one topic does not come back revealed in the next.
 */
class WidgetChannelTopicViewModel(
    storeStateObservable: Observable<StoreState> = observeNavState()
        .switchMap { navState ->
            if (navState.isRightPanelOpened) {
                observeStoreState(navState)
            } else {
                observeStoreState(navState).take(1)
            }
        },
    private val topicParser: TopicParser = DiscordParser.createParser(
        allowMaskedLinks = false,
        supportEntityMentions = true,
    ),
) : AppViewModel<WidgetChannelTopicViewModel.ViewState>(ViewState.NoChannel(false, false)) {

    private val eventSubject: PublishSubject<Event> = PublishSubject.create()

    private var revealedIndices: Set<Int> = emptySet()
    private var previousChannelId: Long? = 0L
    private var wasRightPanelOpened: Boolean = false
    private var wasOnHomeTab: Boolean = false

    init {
        storeStateObservable
            .computationLatest()
            .ui(this)
            .appSubscribe(WidgetChannelTopicViewModel::class.java) { handleStoreState(it) }
    }

    sealed class Event {
        /**
         * The strip has just become visible; the fragment moves accessibility focus onto the channel
         * name so a screen reader announces where the panel landed.
         */
        object FocusFirstElement : Event()
    }

    /** Whether the topic strip is currently on screen, which is what gates the subscription. */
    data class NavState(val isRightPanelOpened: Boolean, val isOnHomeTab: Boolean)

    sealed class StoreState {
        abstract val isRightPanelOpened: Boolean
        abstract val isOnHomeTab: Boolean

        data class NoChannel(
            override val isRightPanelOpened: Boolean,
            override val isOnHomeTab: Boolean,
        ) : StoreState()

        sealed class Guild : StoreState() {
            data class DefaultTopic(
                val channel: Channel,
                val channelIconType: GuildChannelIconType,
                override val isRightPanelOpened: Boolean,
                override val isOnHomeTab: Boolean,
            ) : Guild()

            data class Topic(
                val channel: Channel,
                val channels: Map<Long, Channel>,
                val channelIconType: GuildChannelIconType,
                val users: Map<Long, User>,
                val members: Map<Long, GuildMember>,
                val roles: Map<Long, GuildRole>,
                val allowAnimatedEmojis: Boolean,
                override val isRightPanelOpened: Boolean,
                override val isOnHomeTab: Boolean,
            ) : Guild()
        }

        data class DM(
            val channel: Channel,
            /** One member map per guild the viewer shares with the recipient. */
            val guildMembers: Collection<Map<Long, GuildMember>>,
            val developerModeEnabled: Boolean = false,
            override val isRightPanelOpened: Boolean,
            override val isOnHomeTab: Boolean,
        ) : StoreState()

        data class GDM(
            val channel: Channel,
            val developerModeEnabled: Boolean = false,
            override val isRightPanelOpened: Boolean,
            override val isOnHomeTab: Boolean,
        ) : StoreState()
    }

    sealed class ViewState {
        abstract val isRightPanelOpened: Boolean
        abstract val isOnHomeTab: Boolean

        data class NoChannel(
            override val isRightPanelOpened: Boolean,
            override val isOnHomeTab: Boolean,
        ) : ViewState()

        sealed class Guild : ViewState() {
            abstract val channelIconType: GuildChannelIconType

            data class DefaultTopic(
                override val channelIconType: GuildChannelIconType,
                val channel: Channel,
                override val isRightPanelOpened: Boolean,
                override val isOnHomeTab: Boolean,
            ) : Guild()

            data class Topic(
                /** Kept so a spoiler reveal can re-parse without going back to the stores. */
                val rawTopicString: String,
                val channelNames: Map<Long, String>,
                val userNames: Map<Long, String>,
                val roles: Map<Long, GuildRole>,
                val allowAnimatedEmojis: Boolean,
                val ast: AST,
                /** True when the topic contains code or links that `Linkify` would corrupt. */
                val isLinkifyConflicting: Boolean,
                override val channelIconType: GuildChannelIconType,
                val channel: Channel,
                override val isRightPanelOpened: Boolean,
                override val isOnHomeTab: Boolean,
            ) : Guild()
        }

        data class DM(
            val recipientName: String?,
            val recipientUserId: Long?,
            /** Per-guild nicknames for the recipient, shown by [UserAkaView] as "aka …". */
            val recipientNicknames: Set<String>,
            val guildMembers: List<GuildMember>,
            val channelId: Long,
            val developerModeEnabled: Boolean = false,
            override val isRightPanelOpened: Boolean,
            override val isOnHomeTab: Boolean,
        ) : ViewState()

        data class GDM(
            val channel: Channel,
            val channelId: Long,
            val developerModeEnabled: Boolean = false,
            override val isRightPanelOpened: Boolean,
            override val isOnHomeTab: Boolean,
        ) : ViewState()
    }

    fun listenForEvents(): Observable<Event> = eventSubject

    private fun generateAST(rawTopicString: CharSequence): MutableList<Node<MessageRenderContext>> =
        ArrayList(topicParser.parse(rawTopicString, MessageParseState.initialState))

    @MainThread
    private fun handleStoreState(storeState: StoreState) {
        val channelId = when (storeState) {
            is StoreState.Guild.Topic -> storeState.channel.id.also { id ->
                // Spoilers are revealed per channel; switching channels forgets them.
                if (previousChannelId != id) revealedIndices = emptySet()
            }
            is StoreState.Guild.DefaultTopic -> storeState.channel.id
            is StoreState.DM -> storeState.channel.id
            is StoreState.GDM -> storeState.channel.id
            is StoreState.NoChannel -> null
        }
        previousChannelId = channelId

        if (storeState.isRightPanelOpened != wasRightPanelOpened || storeState.isOnHomeTab != wasOnHomeTab) {
            if (storeState.isRightPanelOpened && storeState.isOnHomeTab) {
                eventSubject.onNext(Event.FocusFirstElement)
            }
            wasRightPanelOpened = storeState.isRightPanelOpened
            wasOnHomeTab = storeState.isOnHomeTab
        }

        when (storeState) {
            is StoreState.NoChannel ->
                updateViewState(ViewState.NoChannel(storeState.isRightPanelOpened, storeState.isOnHomeTab))
            is StoreState.Guild -> handleGuildStoreState(storeState)
            is StoreState.DM, is StoreState.GDM -> handlePrivateStoreState(storeState)
        }
    }

    private fun handleGuildStoreState(storeState: StoreState.Guild) = when (storeState) {
        is StoreState.Guild.DefaultTopic -> updateViewState(
            ViewState.Guild.DefaultTopic(
                channelIconType = storeState.channelIconType,
                channel = storeState.channel,
                isRightPanelOpened = storeState.isRightPanelOpened,
                isOnHomeTab = storeState.isOnHomeTab,
            ),
        )

        is StoreState.Guild.Topic -> {
            val rawTopicString = storeState.channel.topic.orEmpty()
            val ast = generateAST(rawTopicString)
            // myUserId is -1 so no mention in a topic ever highlights as "mentions me".
            val preprocessor = MessagePreprocessor(
                myUserId = -1L,
                visibleSpoilerNodeIndices = revealedIndices,
                embeds = null,
                shouldJumboify = false,
                maxNodes = null,
            )
            preprocessor.process(ast)

            val userNames = storeState.users.mapValues { (userId, user) ->
                storeState.members[userId]?.nick ?: user.username
            }
            // Channels whose name resolves to blank are dropped so `#` renders as literal text
            // rather than an empty mention pill.
            val channelNames = storeState.channels
                .mapValues { (_, channel) -> channel.getDisplayName() }
                .filterValues { it.isNotEmpty() }

            updateViewState(
                ViewState.Guild.Topic(
                    rawTopicString = rawTopicString,
                    channelNames = channelNames,
                    userNames = userNames,
                    roles = storeState.roles,
                    allowAnimatedEmojis = storeState.allowAnimatedEmojis,
                    ast = ast,
                    isLinkifyConflicting = preprocessor.isLinkifyConflicting,
                    channelIconType = storeState.channelIconType,
                    channel = storeState.channel,
                    isRightPanelOpened = storeState.isRightPanelOpened,
                    isOnHomeTab = storeState.isOnHomeTab,
                ),
            )
        }
    }

    private fun handlePrivateStoreState(storeState: StoreState) {
        when (storeState) {
            is StoreState.DM -> {
                val recipient = storeState.channel.getDMRecipient()
                val recipientId = recipient?.id
                val members = storeState.guildMembers.mapNotNull { membersForGuild ->
                    membersForGuild[recipientId]
                }
                updateViewState(
                    ViewState.DM(
                        recipientName = recipient?.let { nickOrUsername(it, storeState.channel) },
                        recipientUserId = recipientId,
                        recipientNicknames = members.mapNotNull { it.nick }.toSet(),
                        guildMembers = members,
                        channelId = storeState.channel.id,
                        developerModeEnabled = storeState.developerModeEnabled,
                        isRightPanelOpened = storeState.isRightPanelOpened,
                        isOnHomeTab = storeState.isOnHomeTab,
                    ),
                )
            }

            is StoreState.GDM -> updateViewState(
                ViewState.GDM(
                    channel = storeState.channel,
                    channelId = storeState.channel.id,
                    developerModeEnabled = storeState.developerModeEnabled,
                    isRightPanelOpened = storeState.isRightPanelOpened,
                    isOnHomeTab = storeState.isOnHomeTab,
                ),
            )

            else -> Unit
        }
    }

    /**
     * Reveals the spoiler [spoilerNode] belongs to and re-renders the topic.
     *
     * The AST carries node identity, so the reveal has to be applied by re-parsing with the index
     * added to [revealedIndices] rather than by mutating the rendered spans.
     */
    @MainThread
    fun handleOnIndexClicked(spoilerNode: SpoilerNode<*>) {
        val topic = viewState as? ViewState.Guild.Topic ?: return
        val ast = generateAST(topic.rawTopicString)
        revealedIndices = revealedIndices + spoilerNode.id
        val preprocessor = MessagePreprocessor(
            myUserId = -1L,
            visibleSpoilerNodeIndices = revealedIndices,
            embeds = null,
            shouldJumboify = false,
            maxNodes = null,
        )
        preprocessor.process(ast)
        updateViewState(
            topic.copy(ast = ast, isLinkifyConflicting = preprocessor.isLinkifyConflicting),
        )
    }

    /** Closes the DM or leaves the group DM the strip is currently showing. */
    @JvmOverloads
    fun handleClosePrivateChannel(context: Context, silent: Boolean = false) {
        val channelId = previousChannelId ?: return
        RestAPI.api.deleteChannel(channelId, silent)
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(WidgetChannelTopicViewModel::class.java, context) { }
    }

    /**
     * The nickname the viewer sees for [user] in [channel].
     *
     * Group DMs carry per-channel nicknames; a one-to-one DM has none, so this falls back to the
     * username. Mirrors `GuildMember.getNickOrUsername(user, member, channel, nicks)` in the
     * reference — that overload does not exist on the target's [GuildMember] yet.
     */
    private fun nickOrUsername(user: User, channel: Channel): String = user.getDisplayName(
        channel.nicks?.firstOrNull { it.id == user.id }?.nick
            ?: NicknameUtils.getNickname(null, channel.id, user.id),
    )

    companion object {

        /** Right-panel-open plus home-tab, the two conditions for the strip being on screen. */
        fun observeNavState(
            storeNavigation: StoreNavigation = StoreStream.getNavigation(),
            storeTabsNavigation: StoreTabsNavigation = StoreStream.getTabsNavigation(),
        ): Observable<NavState> = Observable.combineLatest(
            storeNavigation.observeRightPanelState().map { it == PanelState.Open },
            storeTabsNavigation.observeSelectedTab().map { it == NavigationTab.HOME },
        ) { isOpen, isOnHomeTab -> NavState(isOpen, isOnHomeTab) }

        fun observeStoreState(
            navState: NavState,
            storeChannelsSelected: StoreChannelsSelected = StoreStream.getChannelsSelected(),
            storeChannels: StoreChannels = StoreStream.getChannels(),
            storeUser: StoreUser = StoreStream.getUsers(),
            storeGuilds: StoreGuilds = StoreStream.getGuilds(),
            storeUserSettings: StoreUserSettings = StoreStream.getUserSettings(),
        ): Observable<StoreState> = storeChannelsSelected.observeSelectedChannel().switchMap { channel ->
            when {
                channel == null -> Observable.just(
                    StoreState.NoChannel(navState.isRightPanelOpened, navState.isOnHomeTab),
                )

                channel.isPrivate() ->
                    mapChannelToPrivateStoreState(channel, navState, storeGuilds, storeUserSettings)

                !channel.topic.isNullOrEmpty() -> mapChannelToGuildStoreState(
                    channel,
                    navState,
                    storeChannels,
                    storeUser,
                    storeGuilds,
                    storeUserSettings,
                )

                else -> Observable.just(
                    StoreState.Guild.DefaultTopic(
                        channel,
                        getChannelType(channel),
                        navState.isRightPanelOpened,
                        navState.isOnHomeTab,
                    ),
                )
            }
        }

        private fun mapChannelToGuildStoreState(
            channel: Channel,
            navState: NavState,
            storeChannels: StoreChannels,
            storeUser: StoreUser,
            storeGuilds: StoreGuilds,
            storeUserSettings: StoreUserSettings,
        ): Observable<StoreState> {
            val guildId = channel.guildId
            return Observable.combineLatest(
                storeChannels.observeChannelsForGuild(guildId),
                storeUser.observeAllUsers(),
                storeGuilds.observeComputed(guildId),
                storeGuilds.observeRoles(guildId),
                storeUserSettings.observeIsAnimatedEmojisEnabled(),
            ) { channels, users, members, roles, allowAnimatedEmojis ->
                StoreState.Guild.Topic(
                    channel = channel,
                    channels = channels,
                    channelIconType = getChannelType(channel),
                    users = users,
                    members = members,
                    roles = roles,
                    allowAnimatedEmojis = allowAnimatedEmojis,
                    isRightPanelOpened = navState.isRightPanelOpened,
                    isOnHomeTab = navState.isOnHomeTab,
                )
            }
        }

        private fun mapChannelToPrivateStoreState(
            channel: Channel,
            navState: NavState,
            storeGuilds: StoreGuilds,
            storeUserSettings: StoreUserSettings,
        ): Observable<StoreState> {
            if (channel.isGroup()) {
                return Observable.just(
                    StoreState.GDM(
                        channel,
                        storeUserSettings.getIsDeveloperMode(),
                        navState.isRightPanelOpened,
                        navState.isOnHomeTab,
                    ),
                )
            }
            // Every guild's member map, because the "aka" row lists the recipient's nickname in
            // each guild the viewer shares with them.
            return storeGuilds.observeComputed().map { membersByGuild ->
                StoreState.DM(
                    channel,
                    membersByGuild.values,
                    storeUserSettings.getIsDeveloperMode(),
                    navState.isRightPanelOpened,
                    navState.isOnHomeTab,
                )
            }
        }
    }
}

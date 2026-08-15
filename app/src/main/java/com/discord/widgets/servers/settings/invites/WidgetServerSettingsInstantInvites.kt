package com.discord.widgets.servers.settings.invites

import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.channel.getDisplayName
import com.discord.api.guild.GuildFeature
import com.discord.app.AppFragment
import com.discord.app.AppViewFlipper
import com.discord.models.domain.ModelInvite
import com.discord.models.guild.Guild
import com.discord.models.member.GuildMember
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreGuilds
import com.discord.stores.StoreInstantInvites
import com.discord.stores.StoreStream
import com.discord.utilities.mg_recycler.MGRecyclerAdapter
import com.discord.utilities.mg_recycler.MGRecyclerAdapterSimple
import com.discord.utilities.mg_recycler.MGRecyclerDataPayload
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.user.UserUtils
import com.discord.views.CheckedSetting
import com.discord.widgets.servers.WidgetServerSettingsInstantInvitesActions
import com.discord.widgets.settings.SettingsNavigation
import com.discord.widgets.settings.SettingsStrings
import rx.Observable
import rx.subjects.BehaviorSubject

class WidgetServerSettingsInstantInvites :
    AppFragment(R.layout.widget_server_settings_instant_invites) {

    private val guildId: Long by lazy {
        mostRecentIntent.getLongExtra(INTENT_EXTRA_GUILD_ID, INVALID_GUILD_ID)
    }
    private val channelId: Long by lazy { mostRecentIntent.getLongExtra(INTENT_EXTRA_CHANNEL_ID, 0L) }

    /** A group DM's invites are not indexed by guild, so this screen holds them itself. */
    private val privateInvites: BehaviorSubject<List<ModelInvite>?> =
        BehaviorSubject.create<List<ModelInvite>?>(null as List<ModelInvite>?)
    private val isPrivate: Boolean get() = guildId <= 0L
    private val storeInstantInvites: StoreInstantInvites = StoreStream.getInstantInvites()
    private lateinit var viewFlipper: AppViewFlipper
    private lateinit var pauseContainer: View
    private lateinit var pauseInvites: CheckedSetting
    private var adapter: Adapter? = null

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarDisplayHomeAsUpEnabled()
        viewFlipper = view.findViewById(R.id.server_settings_instant_invites_view_flipper)
        pauseContainer = view.findViewById(R.id.server_settings_instant_invites_pause_container)
        pauseInvites = view.findViewById(R.id.server_settings_instant_invites_pause)
        adapter = MGRecyclerAdapter.configure(
            Adapter(view.findViewById(R.id.server_settings_instant_invites_recycler)),
        )
        if (isPrivate) fetchPrivateInvites() else {
            storeInstantInvites.clearInvites(guildId)
            storeInstantInvites.fetchGuildInvites(guildId)
        }
    }

    private fun fetchPrivateInvites() {
        RestAPI.api.getChannelInvites(channelId)
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(
                WidgetServerSettingsInstantInvites::class.java,
                errorHandler = { privateInvites.onNext(emptyList()) },
            ) { privateInvites.onNext(it) }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        if (isPrivate) fetchPrivateInvites()
        Model.get(guildId, channelId, privateInvites, storeInstantInvites = storeInstantInvites)
            .ui(this)
            .subscribe(::configureUI)
    }

    private fun configureUI(model: Model?) {
        if (model == null) {
            appActivity?.onBackPressed()
            return
        }
        viewFlipper.displayedChild = when {
            model.inviteItems == null -> VIEW_INDEX_INVITES_LOADING
            model.inviteItems.isEmpty() -> VIEW_INDEX_NO_INVITES
            else -> VIEW_INDEX_INVITES_LIST
        }
        model.inviteItems?.let { items ->
            adapter?.configure(
                items,
                onInviteSelectedListener = { invite ->
                    WidgetServerSettingsInstantInvitesActions.create(
                        parentFragmentManager,
                        requireNotNull(invite.code),
                    )
                },
                onInviteExpiredListener = storeInstantInvites::onInviteRemoved,
            )
        }
        model.guild?.takeIf { channelId == 0L }?.let(::configurePauseInvites)
        setActionBarTitle(if (isPrivate) R.string.invite_links else R.string.instant_invites)
        setActionBarSubtitle(
            StoreStream.getChannels().getChannel(channelId)?.getDisplayName() ?: model.guild?.name,
        )
    }

    /**
     * Pausing invites is the `INVITES_DISABLED` guild feature, toggled through the guild PATCH.
     * Only a Community server may hold it, so elsewhere the row shows why it cannot be used
     * rather than disappearing.
     */
    private fun configurePauseInvites(guild: Guild) {
        pauseContainer.visibility = View.VISIBLE
        val disabled = guild.hasFeature(GuildFeature.INVITES_DISABLED)
        pauseInvites.setChecked(disabled, false)
        if (!guild.hasFeature(GuildFeature.COMMUNITY)) {
            pauseInvites.setSubtext(getString(R.string.tooltip_community_feature_required))
            pauseInvites.setDisabledTooltip(R.string.tooltip_community_feature_required)
            return
        }
        pauseInvites.setSubtext(
            if (disabled) {
                getString(R.string.invites_disabled_tip)
            } else {
                SettingsStrings.render(
                    requireContext(),
                    R.string.invites_disabled_description_mobile,
                    "helpArticleUrl" to INVITES_DISABLED_HELP_URL,
                )
            },
        )
        pauseInvites.alpha = 1f
        pauseInvites.setContainerClickListener { setInvitesPaused(guild, !disabled) }
    }

    private fun setInvitesPaused(guild: Guild, paused: Boolean) {
        val features = if (paused) {
            guild.features + GuildFeature.INVITES_DISABLED
        } else {
            guild.features - GuildFeature.INVITES_DISABLED
        }
        RestAPI.api.updateGuild(guildId, RestAPIParams.UpdateGuild(features = features.toList()))
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(WidgetServerSettingsInstantInvites::class.java, context) { }
    }

    class Adapter(recycler: RecyclerView) :
        MGRecyclerAdapterSimple<Model.InviteItem>(recycler, deferredDiffs = false) {

        private var onInviteSelectedListener: (ModelInvite) -> Unit = {}
        private var onInviteExpiredListener: (ModelInvite) -> Unit = {}

        fun configure(
            data: List<Model.InviteItem>,
            onInviteSelectedListener: (ModelInvite) -> Unit,
            onInviteExpiredListener: (ModelInvite) -> Unit,
        ) {
            this.onInviteSelectedListener = onInviteSelectedListener
            this.onInviteExpiredListener = onInviteExpiredListener
            setData(data)
        }

        fun onInviteSelected(invite: ModelInvite) = onInviteSelectedListener(invite)

        fun onInviteExpired(invite: ModelInvite) = onInviteExpiredListener(invite)

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): MGRecyclerViewHolder<*, Model.InviteItem> = if (viewType == Model.InviteItem.TYPE_INVITE) {
            WidgetServerSettingsInstantInvitesListItem(this)
        } else {
            throw invalidViewTypeException(viewType)
        }
    }

    data class Model(
        val guild: Guild?,
        val inviteItems: List<InviteItem>?,
    ) {
        data class InviteItem(
            val invite: ModelInvite,
            val guildId: Long,
            val guildMember: GuildMember?,
        ) : MGRecyclerDataPayload, Comparable<InviteItem> {
            override val key: String = invite.code.orEmpty()
            override val type: Int = TYPE_INVITE

            override fun compareTo(other: InviteItem): Int {
                val channelName = invite.channel?.getDisplayName()
                val otherChannelName = other.invite.channel?.getDisplayName()
                val channelComparison = if (channelName != null && otherChannelName != null) {
                    channelName.compareTo(otherChannelName, ignoreCase = true)
                } else {
                    0
                }
                if (channelComparison != 0) return channelComparison
                val inviter = invite.getInviter()
                val otherInviter = other.invite.getInviter()
                return UserUtils.compareUserNames(
                    inviter?.username.orEmpty(),
                    otherInviter?.username.orEmpty(),
                    null,
                    null,
                    inviter?.id ?: 0L,
                    otherInviter?.id ?: 0L,
                )
            }

            companion object {
                const val TYPE_INVITE = 0

                fun createList(
                    invites: Collection<ModelInvite>,
                    guildId: Long,
                    guildMembers: Map<Long, GuildMember>,
                ): List<InviteItem> = invites.map { invite ->
                    InviteItem(
                        invite,
                        guildId,
                        invite.getInviter()?.let { guildMembers[it.id] },
                    )
                }.sorted()
            }
        }

        companion object {
            /** [channelId] of `0` keeps every invite in the guild; otherwise only that channel's. */
            fun get(
                guildId: Long,
                channelId: Long = 0L,
                privateInvites: Observable<List<ModelInvite>?> = Observable.just(null),
                storeGuilds: StoreGuilds = StoreStream.getGuilds(),
                storeInstantInvites: StoreInstantInvites = StoreStream.getInstantInvites(),
            ): Observable<Model?> {
                if (guildId <= 0L) {
                    return privateInvites
                        .map { invites ->
                            Model(null, invites?.let { InviteItem.createList(it, 0L, emptyMap()) })
                        }
                        .computationLatest()
                        .distinctUntilChanged()
                }
                val guildState = Observable.combineLatest(
                    storeGuilds.observeGuild(guildId),
                    storeGuilds.observeGuildMembers(guildId),
                ) { guild, members -> guild to members }
                val loading = guildState.take(1).map { (guild, _) -> guild?.let { Model(it, null) } }
                val loaded = Observable.combineLatest(
                    guildState,
                    storeInstantInvites.observeInvites(guildId),
                ) { (guild, members), invites ->
                    val scoped = if (channelId == 0L) {
                        invites
                    } else {
                        invites.filterValues { it.channel?.id == channelId }
                    }
                    guild?.let { Model(it, InviteItem.createList(scoped.values, guildId, members)) }
                }
                return Observable.concat(loading, loaded).computationLatest().distinctUntilChanged()
            }
        }
    }

    companion object {
        private const val INTENT_EXTRA_GUILD_ID = "INTENT_EXTRA_GUILD_ID"
        private const val INTENT_EXTRA_CHANNEL_ID = "INTENT_EXTRA_CHANNEL_ID"
        private const val INVALID_GUILD_ID = -1L
        private const val VIEW_INDEX_INVITES_LOADING = 0
        private const val VIEW_INDEX_INVITES_LIST = 1
        private const val VIEW_INDEX_NO_INVITES = 2


        private const val INVITES_DISABLED_HELP_URL =
            "https://support.discord.com/hc/en-us/articles/13497892127383"

        fun create(context: Context, guildId: Long) {
            StoreStream.getAnalytics().onGuildSettingsPaneViewed("INSTANT_INVITES", guildId)
            SettingsNavigation.launch(
                context,
                WidgetServerSettingsInstantInvites::class.java,
                Intent().putExtra(INTENT_EXTRA_GUILD_ID, guildId),
            )
        }

        fun launch(context: Context, guildId: Long) = create(context, guildId)

        fun launchForChannel(context: Context, guildId: Long, channelId: Long) = SettingsNavigation.launch(
            context,
            WidgetServerSettingsInstantInvites::class.java,
            Intent()
                .putExtra(INTENT_EXTRA_GUILD_ID, guildId)
                .putExtra(INTENT_EXTRA_CHANNEL_ID, channelId),
        )
    }
}

/** Keeps the pre-existing focused contract test source-compatible with the reference-shaped model. */
internal object ServerSettingsInstantInvitesViewModel {
    data class StoreState(
        val guild: Guild?,
        val members: Map<Long, GuildMember>,
    )

    sealed class ViewState {
        object Unavailable : ViewState()

        data class Loaded(
            val isLoading: Boolean,
            val invites: List<WidgetServerSettingsInstantInvites.Model.InviteItem>,
        ) : ViewState()
    }

    fun toViewState(storeState: StoreState, invites: List<ModelInvite>?): ViewState {
        val guild = storeState.guild ?: return ViewState.Unavailable
        return ViewState.Loaded(
            isLoading = invites == null,
            invites = invites.orEmpty().map { invite ->
                WidgetServerSettingsInstantInvites.Model.InviteItem(
                    invite,
                    guild.id,
                    invite.getInviter()?.let { storeState.members[it.id] },
                )
            }.sorted(),
        )
    }
}

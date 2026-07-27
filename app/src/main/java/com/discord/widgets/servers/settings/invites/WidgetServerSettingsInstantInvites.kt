package com.discord.widgets.servers.settings.invites

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.doOnPreDraw
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.channel.getDisplayName
import com.discord.api.channel.getDisplayNameOrDefault
import com.discord.app.AppFragment
import com.discord.app.AppViewFlipper
import com.discord.app.AppViewModel
import com.discord.app.appViewModels
import com.discord.models.domain.ModelInvite
import com.discord.models.guild.Guild
import com.discord.models.member.GuildMember
import com.discord.models.user.CoreUser
import com.discord.stores.StoreStream
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.dimen.dpToPixels
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
import com.discord.utilities.view.extensions.setTextAndVisibilityBy
import com.discord.views.user.SettingsMemberView
import com.discord.widgets.servers.WidgetServerSettingsInstantInvitesActions
import com.discord.widgets.settings.SettingsNavigation
import java.util.Locale
import rx.Observable
import rx.subjects.BehaviorSubject

/**
 * The invites a server has issued: their code, channel, creator, uses and time left.
 *
 * Reference: `com.discord.widgets.servers.settings.invites.WidgetServerSettingsInstantInvites`.
 * Three states share one [AppViewFlipper] — loading, the list, and "no invites" — and the reference
 * keeps them apart because its `StoreInstantInvites.getInvites` returns a **nullable** map, where
 * `null` means "not fetched yet".
 *
 * **Divergence, and why.** This project's [com.discord.stores.StoreInstantInvites.getInvites]
 * returns `invitesSnapshot[guildId].orEmpty()`, so "never fetched" and "no invites" are the same
 * empty map, and `observeInvites` is `distinctUntilChanged` on top of that — a guild with no invites
 * produces no second emission at all, so a store-driven screen could never leave the loading state.
 * The list is therefore owned by [ServerSettingsInstantInvitesViewModel], which performs the single
 * `GET /guilds/{id}/invites` itself and gets real loading/loaded/error states from it. Revocation
 * still goes through the store (`onInviteRemoved`), and the list re-reads on resume — which is when
 * the revoke sheet has just been dismissed. Restoring parity is a one-line store change
 * (`getInvites` returning `Map<String, ModelInvite>?`); recorded in
 * `docs/parity-audit/followups.md`.
 *
 * The reference applies **no permission gate of its own** here: `Model.get` only requires the guild
 * to exist. Access is controlled by the server-settings shell, which shows the row behind Manage
 * Server. That is reproduced rather than "fixed".
 */
class WidgetServerSettingsInstantInvites :
    AppFragment(R.layout.widget_server_settings_instant_invites) {

    private val guildId: Long
        get() = arguments?.getLong(INTENT_EXTRA_GUILD_ID, INVALID_GUILD_ID)
            ?.takeIf { it != INVALID_GUILD_ID }
            ?: mostRecentIntent.getLongExtra(INTENT_EXTRA_GUILD_ID, INVALID_GUILD_ID)

    private val viewModel: ServerSettingsInstantInvitesViewModel by appViewModels {
        ServerSettingsInstantInvitesViewModel(guildId)
    }

    private lateinit var viewFlipper: AppViewFlipper
    private var adapter: Adapter? = null

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarDisplayHomeAsUpEnabled()
        setActionBarTitle(R.string.instant_invites)
        viewFlipper = view.findViewById(R.id.server_settings_instant_invites_view_flipper)
        val recycler = view.findViewById<RecyclerView>(R.id.server_settings_instant_invites_recycler)
        // Immediate diffs, not deferred: a row that owns a live countdown must be rebound in the
        // same frame its data changes, which is what the reference asks for too.
        adapter = MGRecyclerAdapter.configure<InviteItem, Adapter>(Adapter(recycler))
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        // Also the return path from the revoke sheet, so a revoked invite disappears.
        viewModel.refresh()
        viewModel.observeViewState().ui(this).subscribe(::configureUI)
    }

    private fun configureUI(viewState: ServerSettingsInstantInvitesViewModel.ViewState) {
        when (viewState) {
            is ServerSettingsInstantInvitesViewModel.ViewState.Uninitialized ->
                viewFlipper.displayedChild = VIEW_INDEX_INVITES_LOADING

            // The guild is gone from under the screen.
            is ServerSettingsInstantInvitesViewModel.ViewState.Unavailable ->
                appActivity?.onBackPressed()

            is ServerSettingsInstantInvitesViewModel.ViewState.Loaded -> {
                setActionBarSubtitle(viewState.guildName)
                viewFlipper.displayedChild = when {
                    viewState.isLoading -> VIEW_INDEX_INVITES_LOADING
                    viewState.invites.isEmpty() -> VIEW_INDEX_NO_INVITES
                    else -> VIEW_INDEX_INVITES_LIST
                }
                adapter?.configure(
                    data = viewState.invites,
                    onInviteSelected = { invite ->
                        invite.code?.let {
                            WidgetServerSettingsInstantInvitesActions.create(parentFragmentManager, it)
                        }
                    },
                    // A countdown that reaches zero drops the row rather than leaving a dead invite
                    // on screen; the server has already stopped honouring it.
                    onInviteExpired = { invite -> viewModel.onInviteExpired(invite) },
                )
            }
        }
    }

    /**
     * One invite row.
     *
     * Sorted by channel name, then by the inviter's name — the reference's ordering, so two invites
     * to the same channel stay grouped.
     */
    data class InviteItem(
        val invite: ModelInvite,
        val guildId: Long,
        val guildMember: GuildMember?,
    ) : MGRecyclerDataPayload, Comparable<InviteItem> {

        override val type: Int get() = TYPE_INVITE
        override val key: String get() = invite.code.orEmpty()

        override fun compareTo(other: InviteItem): Int {
            val thisChannel = invite.channel?.getDisplayName()
            val otherChannel = other.invite.channel?.getDisplayName()
            val byChannel = if (thisChannel != null && otherChannel != null) {
                thisChannel.compareTo(otherChannel, ignoreCase = true)
            } else {
                0
            }
            if (byChannel != 0) return byChannel
            val thisInviter = invite.getInviter()
            val otherInviter = other.invite.getInviter()
            return UserUtils.compareUserNames(
                thisInviter?.username.orEmpty(),
                otherInviter?.username.orEmpty(),
                null,
                null,
                thisInviter?.id ?: 0L,
                otherInviter?.id ?: 0L,
            )
        }
    }

    class Adapter(recycler: RecyclerView) :
        MGRecyclerAdapterSimple<InviteItem>(recycler, deferredDiffs = false) {

        private var onInviteSelectedListener: (ModelInvite) -> Unit = {}
        private var onInviteExpiredListener: (ModelInvite) -> Unit = {}

        fun configure(
            data: List<InviteItem>,
            onInviteSelected: (ModelInvite) -> Unit,
            onInviteExpired: (ModelInvite) -> Unit,
        ) {
            onInviteSelectedListener = onInviteSelected
            onInviteExpiredListener = onInviteExpired
            setData(data)
        }

        internal fun onInviteSelected(invite: ModelInvite) = onInviteSelectedListener(invite)

        internal fun onInviteExpired(invite: ModelInvite) = onInviteExpiredListener(invite)

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): WidgetServerSettingsInstantInvitesListItem =
            if (viewType == TYPE_INVITE) {
                WidgetServerSettingsInstantInvitesListItem(this)
            } else {
                throw invalidViewTypeException(viewType)
            }
    }

    companion object {
        private const val INTENT_EXTRA_GUILD_ID = "INTENT_EXTRA_GUILD_ID"
        private const val INVALID_GUILD_ID = -1L

        internal const val TYPE_INVITE = 0

        private const val VIEW_INDEX_INVITES_LOADING = 0
        private const val VIEW_INDEX_INVITES_LIST = 1
        private const val VIEW_INDEX_NO_INVITES = 2

        fun newInstance(guildId: Long) = WidgetServerSettingsInstantInvites().apply {
            arguments = Bundle().apply { putLong(INTENT_EXTRA_GUILD_ID, guildId) }
        }

        fun launch(context: Context, guildId: Long) {
            SettingsNavigation.launch(
                context,
                WidgetServerSettingsInstantInvites::class.java,
                Intent().putExtra(INTENT_EXTRA_GUILD_ID, guildId),
            )
        }
    }
}

/**
 * One invite: code, channel, creator, uses, and a live countdown to expiry.
 *
 * Reference: `WidgetServerSettingsInstantInvitesListItem`. The countdown is a per-row
 * [CountDownTimer] keyed on the invite code, so scrolling a recycled row onto a different invite
 * restarts it rather than continuing the previous one's clock.
 */
class WidgetServerSettingsInstantInvitesListItem(
    adapter: WidgetServerSettingsInstantInvites.Adapter,
) : MGRecyclerViewHolder<WidgetServerSettingsInstantInvites.Adapter, WidgetServerSettingsInstantInvites.InviteItem>(
    R.layout.widget_server_settings_instant_invite_list_item,
    adapter,
) {

    private val code: TextView = itemView.findViewById(R.id.invite_code)
    private val channelName: TextView = itemView.findViewById(R.id.invite_channel)
    private val expirationTime: TextView = itemView.findViewById(R.id.invite_expiration_time)
    private val uses: TextView = itemView.findViewById(R.id.invite_uses)
    private val member: SettingsMemberView = itemView.findViewById(R.id.settings_member)

    private var countdownTimer: CountDownTimer? = null

    /** The invite the running timer belongs to, so a rebind to the same invite does not restart it. */
    private var currentInviteCode: String? = null

    override fun onConfigure(
        position: Int,
        data: WidgetServerSettingsInstantInvites.InviteItem,
    ) {
        super.onConfigure(position, data)
        val invite = data.invite
        val inviter = invite.getInviter()

        itemView.setOnClickListener { adapter.onInviteSelected(invite) }
        code.text = invite.code
        uses.text = usesText(invite)
        channelName.setTextAndVisibilityBy(
            invite.channel?.getDisplayNameOrDefault(channelName.context, includePrefix = true),
        )

        if (inviter != null) {
            member.configure(CoreUser(inviter), data.guildMember)
            member.visibility = View.VISIBLE
        } else {
            member.visibility = View.GONE
        }

        setupExpirationTime(invite)

        // The countdown shrinks as digits roll over; pinning a minimum width once measured stops
        // the row's other columns shifting every second.
        expirationTime.doOnPreDraw { view ->
            view.minimumWidth = view.width + dpToPixels(COUNTDOWN_WIDTH_PADDING_DP)
        }
    }

    /** `"3"` when the invite has unlimited uses, `"3 / 10"` when it is capped. */
    private fun usesText(invite: ModelInvite): String {
        val maxUses = invite.getMaxUses()
        return if (maxUses > 0) "${invite.getUses()} / $maxUses" else invite.getUses().toString()
    }

    private fun setupExpirationTime(invite: ModelInvite) {
        val timeToExpiration = invite.getTimeToExpirationMillis()
        when {
            // A max age of zero is Discord's "never expires".
            invite.getMaxAge() == 0 -> {
                expirationTime.setText(R.string.max_age_never)
                expirationTime.setTextColor(ColorCompat.getColor(expirationTime, R.color.grey_1))
                cancelTimer()
            }

            timeToExpiration <= 0L -> {
                setCountdownText(0L)
                cancelTimer()
            }

            invite.code != currentInviteCode || currentInviteCode == null -> {
                setCountdownText(timeToExpiration)
                cancelTimer()
                currentInviteCode = invite.code
                countdownTimer = object : CountDownTimer(timeToExpiration, COUNTDOWN_TICK_MS) {
                    override fun onTick(millisUntilFinished: Long) =
                        setCountdownText(millisUntilFinished)

                    override fun onFinish() {
                        setCountdownText(0L)
                        adapter.onInviteExpired(invite)
                    }
                }.also { it.start() }
            }
        }
    }

    private fun setCountdownText(timeToExpiration: Long) {
        val seconds = timeToExpiration / 1_000L % 60
        val minutes = timeToExpiration / 60_000L % 60
        val hours = timeToExpiration / 3_600_000L % 24
        // Reference bug reproduced: days are taken modulo 24, not left unbounded, so an invite with
        // a 25-day life would display "01" days. In practice Discord's longest offered max age is
        // seven days, so the wrap is unreachable through the UI.
        val days = timeToExpiration / 86_400_000L % 24
        expirationTime.text =
            String.format(Locale.getDefault(), "%02d:%02d:%02d:%02d", days, hours, minutes, seconds)
        expirationTime.setTextColor(
            ColorCompat.getColor(expirationTime, R.color.status_green_500_dark),
        )
    }

    private fun cancelTimer() {
        countdownTimer?.cancel()
        countdownTimer = null
        currentInviteCode = null
    }

    private companion object {
        private const val COUNTDOWN_TICK_MS = 1_000L
        private const val COUNTDOWN_WIDTH_PADDING_DP = 4
    }
}

/**
 * State for [WidgetServerSettingsInstantInvites].
 *
 * Owns the invite fetch itself — see the screen's KDoc for why it cannot come from
 * `StoreInstantInvites` as the reference's does. The guild and its members still come from the
 * store, so a nickname or avatar change repaints without a refetch.
 */
class ServerSettingsInstantInvitesViewModel(
    private val guildId: Long,
    storeStateObservable: Observable<StoreState> = observeStoreState(guildId),
    private val fetchInvites: (Long) -> Observable<List<ModelInvite>>? = { id ->
        RestAPI.apiOrNull?.getGuildInvites(id)
    },
) : AppViewModel<ServerSettingsInstantInvitesViewModel.ViewState>(ViewState.Uninitialized) {

    data class StoreState(
        val guild: Guild?,
        val members: Map<Long, GuildMember>,
    )

    sealed class ViewState {
        object Uninitialized : ViewState()

        /** The guild no longer exists. The screen closes itself. */
        object Unavailable : ViewState()

        data class Loaded(
            val guildName: String,
            val isLoading: Boolean,
            val invites: List<WidgetServerSettingsInstantInvites.InviteItem>,
        ) : ViewState()
    }

    /** `null` while the fetch is in flight — the loading state the store cannot express. */
    private val invitesSubject = BehaviorSubject.create(null as List<ModelInvite>?)

    private var isFetching = false

    init {
        Observable.combineLatest(
            storeStateObservable,
            invitesSubject,
        ) { storeState, invites -> toViewState(storeState, invites) }
            .computationLatest()
            .distinctUntilChanged()
            .ui(this)
            .subscribe { updateViewState(it) }
    }

    /** Re-reads the guild's invites. Safe to call on every resume; concurrent calls collapse. */
    fun refresh() {
        if (isFetching) return
        val request = fetchInvites(guildId) ?: return
        isFetching = true
        invitesSubject.onNext(null)
        request
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(
                errorClass = ServerSettingsInstantInvitesViewModel::class.java,
                errorHandler = {
                    // A failed read leaves the screen on the empty state with the toast
                    // `appSubscribe` shows; there is nothing partial worth rendering.
                    isFetching = false
                    invitesSubject.onNext(emptyList())
                },
            ) { invites ->
                isFetching = false
                invitesSubject.onNext(invites)
            }
    }

    /**
     * Drops an invite whose countdown reached zero.
     *
     * The store is told as well, so anything else holding this invite — a chat invite card, for
     * instance — stops offering it too.
     */
    fun onInviteExpired(invite: ModelInvite) {
        StoreStream.getInstantInvites().onInviteRemoved(invite)
        invitesSubject.value?.let { current ->
            invitesSubject.onNext(current.filterNot { it.code == invite.code })
        }
    }

    companion object {
        internal fun toViewState(
            storeState: StoreState,
            invites: List<ModelInvite>?,
        ): ViewState {
            val guild = storeState.guild ?: return ViewState.Unavailable
            return ViewState.Loaded(
                guildName = guild.name,
                isLoading = invites == null,
                invites = invites.orEmpty()
                    .map { invite ->
                        WidgetServerSettingsInstantInvites.InviteItem(
                            invite = invite,
                            guildId = guild.id,
                            guildMember = invite.getInviter()?.let { storeState.members[it.id] },
                        )
                    }
                    .sorted(),
            )
        }

        fun observeStoreState(guildId: Long): Observable<StoreState> = Observable.combineLatest(
            StoreStream.getGuilds().observeGuild(guildId),
            StoreStream.getGuilds().observeGuildMembers(guildId),
        ) { guild, members -> StoreState(guild, members) }
    }
}

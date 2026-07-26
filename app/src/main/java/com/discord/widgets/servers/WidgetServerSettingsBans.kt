package com.discord.widgets.servers

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppViewFlipper
import com.discord.app.AppViewModel
import com.discord.app.appViewModels
import com.discord.models.domain.ModelBan
import com.discord.models.guild.Guild
import com.discord.models.user.CoreUser
import com.discord.models.user.MeUser
import com.discord.stores.StoreStream
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.locale.LocaleManager
import com.discord.utilities.mg_recycler.MGRecyclerAdapter
import com.discord.utilities.mg_recycler.MGRecyclerAdapterSimple
import com.discord.utilities.mg_recycler.MGRecyclerDataPayload
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder
import com.discord.api.permission.Permission
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.leadingEdgeThrottle
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.view.extensions.getTextOrEmpty
import com.discord.utilities.view.text.addBindedTextWatcher
import com.discord.widgets.settings.SettingsNavigation
import com.discord.widgets.settings.SettingsStrings
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import java.util.concurrent.TimeUnit
import rx.Observable
import rx.subjects.BehaviorSubject

/**
 * The server's ban list, searchable by username, with unban behind a confirmation.
 *
 * Reference: `com.discord.widgets.servers.WidgetServerSettingsBans`. Four states share one
 * [AppViewFlipper]: loading, the list, "nobody is banned", and "nothing matched the search" — the
 * last two are different situations and say different things.
 */
class WidgetServerSettingsBans : AppFragment(R.layout.widget_server_settings_bans) {

    private val guildId: Long
        get() = arguments?.getLong(INTENT_EXTRA_GUILD_ID, INVALID_GUILD_ID)
            ?.takeIf { it != INVALID_GUILD_ID }
            ?: mostRecentIntent.getLongExtra(INTENT_EXTRA_GUILD_ID, INVALID_GUILD_ID)

    private val viewModel: ServerSettingsBansViewModel by appViewModels {
        ServerSettingsBansViewModel(guildId)
    }

    private lateinit var viewFlipper: AppViewFlipper
    private lateinit var search: TextInputLayout
    private var adapter: Adapter? = null

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarDisplayHomeAsUpEnabled()
        setActionBarTitle(R.string.bans)

        viewFlipper = view.findViewById(R.id.server_settings_bans_view_flipper)
        search = view.findViewById(R.id.server_settings_bans_search)
        val recycler = view.findViewById<RecyclerView>(R.id.server_settings_bans_recycler)
        adapter = MGRecyclerAdapter.configure<BanItem, Adapter>(Adapter(recycler))
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.setFilter(search.getTextOrEmpty())
        search.editText?.addBindedTextWatcher(this) { viewModel.setFilter(it.toString()) }
        viewModel.observeViewState().ui(this).subscribe { configureUI(it) }
    }

    private fun configureUI(viewState: ServerSettingsBansViewModel.ViewState) {
        when (viewState) {
            is ServerSettingsBansViewModel.ViewState.Uninitialized ->
                viewFlipper.displayedChild = VIEW_INDEX_LOADING

            // Losing Ban Members while the screen is open is the same as never having reached it.
            is ServerSettingsBansViewModel.ViewState.Unauthorized -> appActivity?.onBackPressed()

            is ServerSettingsBansViewModel.ViewState.Loaded -> {
                setActionBarSubtitle(viewState.guildName)
                viewFlipper.displayedChild = when {
                    viewState.isLoading -> VIEW_INDEX_LOADING
                    viewState.totalBannedUsers == 0 -> VIEW_INDEX_NO_BANS
                    viewState.bans.isEmpty() -> VIEW_INDEX_NO_RESULTS
                    else -> VIEW_INDEX_BAN_LIST
                }
                adapter?.onBanSelected = ::showConfirmUnbanDialog
                adapter?.setData(viewState.bans)
            }
        }
    }

    /**
     * Reference uses `WidgetNoticeDialog` with the danger theme; that widget has not been
     * reconstructed, so the platform dialog stands in. The ban reason is shown under an uppercased
     * "Ban Reason" label so the moderator can see why the ban exists before lifting it.
     */
    private fun showConfirmUnbanDialog(ban: ModelBan) {
        val context = requireContext()
        val locale = LocaleManager().getPrimaryLocale(context)
        val reason = ban.reason ?: getString(R.string.no_ban_reason)
        MaterialAlertDialogBuilder(context)
            .setTitle(
                SettingsStrings.render(context, R.string.unban_user_title, "user" to ban.user.username),
            )
            .setMessage("${getString(R.string.ban_reason).uppercase(locale)}\n$reason")
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.unban) { _, _ -> viewModel.unban(ban) }
            .show()
    }

    /** One banned user. */
    data class BanItem(val ban: ModelBan) : MGRecyclerDataPayload {
        override val type: Int get() = TYPE_BAN
        override val key: String get() = ban.user.id.toString()
    }

    private class Adapter(recycler: RecyclerView) : MGRecyclerAdapterSimple<BanItem>(recycler) {

        var onBanSelected: ((ModelBan) -> Unit)? = null

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BanListItem =
            if (viewType == TYPE_BAN) BanListItem(this) else throw invalidViewTypeException(viewType)

        class BanListItem(
            adapter: Adapter,
        ) : MGRecyclerViewHolder<Adapter, BanItem>(R.layout.widget_server_settings_ban_list_item, adapter) {

            private val container: View = itemView.findViewById(R.id.ban_container)
            private val avatar: ImageView = itemView.findViewById(R.id.ban_list_item_avatar)
            private val name: TextView = itemView.findViewById(R.id.ban_list_item_name)

            override fun onConfigure(position: Int, data: BanItem) {
                super.onConfigure(position, data)
                name.text = data.ban.user.username
                IconUtils.setIcon(avatar, CoreUser(data.ban.user), R.dimen.avatar_size_standard)
                container.setOnClickListener { adapter.onBanSelected?.invoke(data.ban) }
            }
        }
    }

    companion object {
        private const val INTENT_EXTRA_GUILD_ID = "GUILD_ID"
        private const val INVALID_GUILD_ID = -1L

        private const val TYPE_BAN = 1

        private const val VIEW_INDEX_LOADING = 0
        private const val VIEW_INDEX_BAN_LIST = 1
        private const val VIEW_INDEX_NO_BANS = 2
        private const val VIEW_INDEX_NO_RESULTS = 3

        fun newInstance(guildId: Long) = WidgetServerSettingsBans().apply {
            arguments = Bundle().apply { putLong(INTENT_EXTRA_GUILD_ID, guildId) }
        }

        fun launch(context: Context, guildId: Long) {
            SettingsNavigation.launch(
                context,
                WidgetServerSettingsBans::class.java,
                Intent().putExtra(INTENT_EXTRA_GUILD_ID, guildId),
            )
        }
    }
}

/**
 * State for [WidgetServerSettingsBans].
 *
 * `StoreBans.observeBans` emits `null` until the list has been fetched, which is the loading state;
 * a fetched-but-empty guild emits an empty map. The two must stay distinguishable because the
 * screen shows a different thing for each.
 */
class ServerSettingsBansViewModel(
    private val guildId: Long,
    storeStateObservable: Observable<StoreState> = observeStoreState(guildId),
) : AppViewModel<ServerSettingsBansViewModel.ViewState>(ViewState.Uninitialized) {

    data class StoreState(
        val guild: Guild?,
        val canManageBans: Boolean,
        val bans: Map<Long, ModelBan>?,
    )

    sealed class ViewState {
        object Uninitialized : ViewState()
        object Unauthorized : ViewState()

        data class Loaded(
            val guildName: String,
            val isLoading: Boolean,
            val totalBannedUsers: Int,
            val bans: List<WidgetServerSettingsBans.BanItem>,
        ) : ViewState()
    }

    private val filterSubject = BehaviorSubject.create("")

    init {
        Observable.combineLatest(
            storeStateObservable,
            filterSubject.leadingEdgeThrottle(FILTER_THROTTLE_MS, TimeUnit.MILLISECONDS),
        ) { storeState, filter -> toViewState(storeState, filter) }
            .computationLatest()
            .distinctUntilChanged()
            .ui(this)
            .subscribe { updateViewState(it) }
    }

    fun setFilter(filter: String) = filterSubject.onNext(filter)

    fun unban(ban: ModelBan) {
        val api = RestAPI.apiOrNull ?: return
        api.unbanUser(guildId, ban.user.id)
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(ServerSettingsBansViewModel::class.java) {}
    }

    companion object {
        private const val FILTER_THROTTLE_MS = 100L

        internal fun toViewState(storeState: StoreState, filter: String): ViewState {
            val guild = storeState.guild
            if (guild == null || !storeState.canManageBans) return ViewState.Unauthorized
            val bans = storeState.bans
            return ViewState.Loaded(
                guildName = guild.name,
                isLoading = bans == null,
                totalBannedUsers = bans?.size ?: 0,
                bans = bans.orEmpty().values
                    .filter { it.user.username.contains(filter, ignoreCase = true) }
                    .sortedBy { it.user.username.lowercase() }
                    .map { WidgetServerSettingsBans.BanItem(it) },
            )
        }

        fun observeStoreState(guildId: Long): Observable<StoreState> = Observable.combineLatest(
            StoreStream.getGuilds().observeGuild(guildId),
            StoreStream.getPermissions().observePermissionsForGuild(guildId),
            StoreStream.getUsers().observeMe(),
            StoreStream.getBans().observeBans(guildId),
        ) { guild, guildPermissions, meUser: MeUser, bans ->
            StoreState(
                guild = guild,
                // Banning is an elevated action: a guild that requires MFA of its moderators
                // hides the screen from an owner or moderator who has not enabled it.
                canManageBans = guild != null && (
                    guild.isOwner(meUser.id) ||
                        PermissionUtils.canAndIsElevated(
                            Permission.BAN_MEMBERS,
                            guildPermissions,
                            meUser.mfaEnabled,
                            guild.mfaLevel,
                        )
                    ),
                bans = bans,
            )
        }
    }
}

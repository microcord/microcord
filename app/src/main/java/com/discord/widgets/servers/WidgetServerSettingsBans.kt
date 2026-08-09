package com.discord.widgets.servers

import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.app.AppFragment
import com.discord.databinding.WidgetServerSettingsBanListItemBinding
import com.discord.databinding.WidgetServerSettingsBansBinding
import com.discord.models.domain.ModelBan
import com.discord.models.guild.Guild
import com.discord.models.user.CoreUser
import com.discord.stores.StoreStream
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.locale.LocaleManager
import com.discord.utilities.mg_recycler.MGRecyclerAdapter
import com.discord.utilities.mg_recycler.MGRecyclerAdapterSimple
import com.discord.utilities.mg_recycler.MGRecyclerDataPayload
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder
import com.discord.api.permission.Permission
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.search.fuzzyMatches
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.view.extensions.getTextOrEmpty
import com.discord.utilities.view.extensions.setTextAndVisibilityBy
import com.discord.utilities.view.text.addBindedTextWatcher
import com.discord.widgets.settings.SettingsNavigation
import com.discord.widgets.settings.SettingsStrings
import com.discord.widgets.notice.WidgetNoticeDialog
import java.util.concurrent.TimeUnit
import rx.Observable
import rx.subjects.BehaviorSubject

/**
 * The server's ban list, searchable by username, with unban behind a confirmation.
 *
 * Reference: `com.discord.widgets.servers.WidgetServerSettingsBans`. Four states share one
 * `AppViewFlipper`: loading, the list, "nobody is banned", and "nothing matched the search" — the
 * last two are different situations and say different things.
 */
class WidgetServerSettingsBans : AppFragment(R.layout.widget_server_settings_bans) {

    private var _binding: WidgetServerSettingsBansBinding? = null
    private val binding get() = checkNotNull(_binding)

    private val guildId: Long
        get() = arguments?.getLong(INTENT_EXTRA_GUILD_ID, INVALID_GUILD_ID)
            ?.takeIf { it != INVALID_GUILD_ID }
            ?: mostRecentIntent.getLongExtra(INTENT_EXTRA_GUILD_ID, INVALID_GUILD_ID)

    private var adapter: Adapter? = null
    private val filterPublisher = BehaviorSubject.create("")
    private val isSearching get() = filterPublisher.value.isNotBlank()

    /**
     * The current search, held in a hot subject.
     *
     * [Model.get] re-subscribes to whatever it is given every time the ban list changes, so the
     * request cannot live in that chain — it would re-fire on its own results. The requests run in
     * their own subscription and only publish here.
     */
    private val searchState = BehaviorSubject.create(Model.Search())

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        _binding = WidgetServerSettingsBansBinding.bind(view)
        setActionBarDisplayHomeAsUpEnabled()
        setActionBarTitle(R.string.bans)

        val recycler = binding.serverSettingsBansRecycler
        adapter = MGRecyclerAdapter.configure<Model.BanItem, Adapter>(Adapter(recycler))
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(view: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || isSearching) return
                val layout = view.layoutManager as? LinearLayoutManager ?: return
                if (layout.findLastVisibleItemPosition() >= layout.itemCount - LOAD_MORE_THRESHOLD) {
                    StoreStream.getBans().loadMoreBans(guildId)
                }
            }
        })
        StoreStream.getBans().loadMoreBans(guildId)
    }

    override fun onDestroyView() {
        binding.serverSettingsBansRecycler.adapter = null
        adapter = null
        _binding = null
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        filterPublisher.onNext(binding.serverSettingsBansSearch.getTextOrEmpty())
        binding.serverSettingsBansSearch.editText
            ?.addBindedTextWatcher(this) { filterPublisher.onNext(it.toString()) }
        searchPublisher().ui(this).appSubscribe(javaClass) { searchState.onNext(it) }
        Model.get(guildId, searchState).ui(this).appSubscribe(javaClass) { configureUI(it) }
    }

    /**
     * Turns what was typed into a search.
     *
     * The local match is the same fuzzy subsequence the reference uses, but only the pages already
     * pulled are in memory, so `bans/search` is asked as well to reach anyone further down the
     * list. Its answer is a substring match over usernames and display names and is added to the
     * local one rather than replacing it — the two disagree, and the server's stricter answer must
     * not take rows away. A run of snowflakes also goes out as `user_ids`, which is how a moderator
     * looks up someone whose name they do not have.
     */
    private fun searchPublisher(): Observable<Model.Search> = filterPublisher
        .debounce(SEARCH_DEBOUNCE_MS, TimeUnit.MILLISECONDS)
        .switchMap { typed ->
            val query = typed.trim()
            if (query.isEmpty()) return@switchMap Observable.just(Model.Search())
            val userIds = SNOWFLAKE.findAll(query).mapNotNull { it.value.toLongOrNull() }.toList()
            Observable.just(Model.Search(query))
                .concatWith(
                    StoreStream.getBans().searchBans(guildId, query, userIds)
                        .map { Model.Search(query, it) }
                        // A failed search leaves the local matches on screen rather than blanking it.
                        .onErrorResumeNext(Observable.empty()),
                )
        }

    private fun configureUI(model: Model?) {
        if (_binding == null) return
        val bans = model?.filteredBannedUsers
        if (bans == null) {
            appActivity?.onBackPressed()
            return
        }
        setActionBarSubtitle(model.guildName)
        binding.serverSettingsBansViewFlipper.displayedChild = when {
            model.isLoading -> VIEW_INDEX_LOADING
            model.totalBannedUsers == 0 -> VIEW_INDEX_NO_BANS
            bans.isEmpty() -> VIEW_INDEX_NO_RESULTS
            else -> VIEW_INDEX_BAN_LIST
        }
        adapter?.onBanSelected = { showConfirmUnbanDialog(model.guildId, it) }
        adapter?.setData(bans)
    }

    /** Shows the ban reason before the moderator confirms unbanning the user. */
    private fun showConfirmUnbanDialog(guildId: Long, ban: ModelBan) {
        val context = requireContext()
        val locale = LocaleManager().getPrimaryLocale(context)
        val reason = ban.reason ?: getString(R.string.no_ban_reason)
        WidgetNoticeDialog.Builder(context)
            .setTitle(
                SettingsStrings.render(context, R.string.unban_user_title, "user" to ban.user.username),
            )
            .setMessage("${getString(R.string.ban_reason).uppercase(locale)}\n$reason")
            .setNegativeButton(R.string.cancel)
            .setPositiveButton(R.string.unban) {
                RestAPI.api.unbanUser(guildId, ban.user.id)
                    .restSubscribeOn(retry = false)
                    .ui(this)
                    .appSubscribe(javaClass) { }
            }
            .setDialogAttrTheme(R.attr.notice_theme_positive_red)
            .show(parentFragmentManager)
    }

    private class Adapter(recycler: RecyclerView) : MGRecyclerAdapterSimple<Model.BanItem>(recycler) {

        var onBanSelected: ((ModelBan) -> Unit)? = null

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BanListItem =
            if (viewType == Model.TYPE_BANNED_USER) BanListItem(this) else throw invalidViewTypeException(viewType)

        inner class BanListItem(
            adapter: Adapter,
        ) : MGRecyclerViewHolder<Adapter, Model.BanItem>(R.layout.widget_server_settings_ban_list_item, adapter) {

            private val binding = WidgetServerSettingsBanListItemBinding.bind(itemView)

            override fun onConfigure(position: Int, data: Model.BanItem) {
                super.onConfigure(position, data)
                val ban = data.ban
                val user = ban?.user
                val displayName = user?.globalName?.takeUnless(String::isBlank)
                binding.banListItemName.text = displayName ?: user?.username
                // The username only earns a second line when it is not already the label.
                binding.banListItemUsername.setTextAndVisibilityBy(
                    user?.username?.takeIf { displayName != null },
                )
                IconUtils.setIcon(
                    binding.banListItemAvatar,
                    user?.let(::CoreUser),
                    R.dimen.avatar_size_standard,
                )
                binding.banContainer.setOnClickListener {
                    ban?.let { adapter.onBanSelected?.invoke(it) }
                }
            }
        }
    }

    data class Model(
        val totalBannedUsers: Int,
        val filteredBannedUsers: List<BanItem>?,
        val guildId: Long,
        val guildName: String?,
        val isLoading: Boolean,
    ) {
        data class BanItem(var ban: ModelBan? = null) : MGRecyclerDataPayload {
            override val type: Int = TYPE_BANNED_USER
            override val key: String = ban?.user?.id.toString()

            fun create(ban: ModelBan) = BanItem().also { it.ban = ban }
        }

        /**
         * What is being looked for, and whatever the server has said about it so far. An empty
         * [query] is the unfiltered list.
         */
        data class Search(val query: String = "", val serverMatches: List<ModelBan> = emptyList())

        companion object {
            const val TYPE_BANNED_USER = 1
            private val emptyBansList = arrayListOf<BanItem>()

            /**
             * Matches the query against both the username and the display name, the way the
             * reference does — a fuzzy subsequence rather than a substring, so a query can skip
             * over characters.
             */
            internal fun matchesSearch(ban: ModelBan, filter: String): Boolean {
                val needle = filter.trim().lowercase()
                if (needle.isEmpty()) return true
                return fuzzyMatches(needle, ban.user.username.lowercase()) ||
                    ban.user.globalName?.lowercase()?.let { fuzzyMatches(needle, it) } == true
            }

            internal fun create(guild: Guild?, total: Int?, bans: List<BanItem>?): Model? =
                if (guild == null || total == null || bans == null) null
                else Model(total, bans, guild.id, guild.name, false)

            fun get(guildId: Long, searchPublisher: Observable<Search>): Observable<Model?> =
                Observable.combineLatest(
                    StoreStream.getPermissions().observePermissionsForGuild(guildId),
                    StoreStream.getGuilds().observeGuild(guildId),
                    StoreStream.getUsers().observeMe(),
                ) { permissions, guild, me ->
                    guild != null && permissions != null && PermissionUtils.canAndIsElevated(
                        Permission.BAN_MEMBERS,
                        permissions,
                        me.mfaEnabled,
                        guild.mfaLevel,
                    )
                }.distinctUntilChanged().flatMap { canManage ->
                    if (!canManage) return@flatMap Observable.just(null)
                    StoreStream.getBans().observeBans(guildId).flatMap { bans ->
                        if (bans == null) {
                            StoreStream.getGuilds().observeGuild(guildId).map { guild ->
                                guild?.let { Model(0, emptyBansList, it.id, it.name, true) }
                            }
                        } else {
                            Observable.combineLatest(
                                StoreStream.getGuilds().observeGuild(guildId),
                                searchPublisher.map { search ->
                                    if (search.query.isEmpty()) {
                                        bans.values
                                    } else {
                                        bans.values.filter { matchesSearch(it, search.query) } +
                                            search.serverMatches
                                    }
                                        .distinctBy { it.user.id }
                                        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.user.username })
                                        .map(::BanItem)
                                },
                            ) { guild, filtered -> create(guild, bans.size, filtered) }
                        }
                    }
                }.computationLatest().distinctUntilChanged()
        }
    }

    companion object {
        private const val INTENT_EXTRA_GUILD_ID = "GUILD_ID"
        private const val INVALID_GUILD_ID = -1L

        private const val VIEW_INDEX_LOADING = 0
        private const val VIEW_INDEX_BAN_LIST = 1
        private const val VIEW_INDEX_NO_BANS = 2
        private const val VIEW_INDEX_NO_RESULTS = 3

        private const val SEARCH_DEBOUNCE_MS = 300L

        /** Rows from the end at which the next page is requested. */
        private const val LOAD_MORE_THRESHOLD = 10

        /** A run of digits long enough to be a user id. */
        private val SNOWFLAKE = Regex("\\d{17,20}")

        fun create(context: Context, guildId: Long) {
            StoreStream.getAnalytics().onGuildSettingsPaneViewed("BANS", guildId)
            SettingsNavigation.launch(
                context,
                WidgetServerSettingsBans::class.java,
                Intent().putExtra(INTENT_EXTRA_GUILD_ID, guildId),
            )
        }

        fun launch(context: Context, guildId: Long) = create(context, guildId)
    }
}

/** Compatibility projection retained for the existing bans state unit test. */
object ServerSettingsBansViewModel {
    data class BanItem(val ban: ModelBan)

    data class StoreState(
        val guild: Guild?,
        val canManageBans: Boolean,
        val bans: Map<Long, ModelBan>?,
    )

    sealed class ViewState {
        object Unauthorized : ViewState()

        data class Loaded(
            val guildName: String,
            val isLoading: Boolean,
            val totalBannedUsers: Int,
            val bans: List<BanItem>,
        ) : ViewState()
    }

    internal fun toViewState(storeState: StoreState, filter: String): ViewState {
        val guild = storeState.guild
        if (guild == null || !storeState.canManageBans) return ViewState.Unauthorized
        val bans = storeState.bans
        return ViewState.Loaded(
            guildName = guild.name,
            isLoading = bans == null,
            totalBannedUsers = bans?.size ?: 0,
            bans = bans.orEmpty().values
                .filter { WidgetServerSettingsBans.Model.matchesSearch(it, filter) }
                .sortedBy { it.user.username.lowercase() }
                .map(::BanItem),
        )
    }
}

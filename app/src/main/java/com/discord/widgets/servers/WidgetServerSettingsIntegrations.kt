package com.discord.widgets.servers

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.permission.Permission
import com.discord.app.AppFragment
import com.discord.app.AppViewFlipper
import com.discord.app.AppViewModel
import com.discord.app.appViewModels
import com.discord.models.domain.ModelGuildIntegration
import com.discord.models.guild.Guild
import com.discord.stores.StoreStream
import com.discord.utilities.mg_recycler.MGRecyclerAdapter
import com.discord.utilities.mg_recycler.MGRecyclerAdapterSimple
import com.discord.utilities.mg_recycler.MGRecyclerDataPayload
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.ui
import com.discord.widgets.settings.SettingsNavigation
import rx.Observable

/**
 * The guild's Twitch/YouTube integrations.
 *
 * Reference: `com.discord.widgets.servers.WidgetServerSettingsIntegrations`. The list is owned by
 * `StoreGuildIntegrations`, which only fetches while a screen has declared itself open — hence the
 * [onResume]/[onPause] calls to `onIntegrationScreenOpened`/`onIntegrationScreenClosed`. Forgetting
 * either leaves the list permanently empty or permanently polling.
 *
 * Only the two types in `ModelGuildIntegration.SUPPORTED_TYPES` are rendered; the guild may carry
 * others (bot applications appear as integrations too) and the reference silently drops them rather
 * than showing a row it has no UI for.
 */
class WidgetServerSettingsIntegrations : AppFragment(R.layout.widget_server_settings_integrations) {

    private val guildId: Long
        get() = arguments?.getLong(INTENT_EXTRA_GUILD_ID, INVALID_GUILD_ID)
            ?.takeIf { it != INVALID_GUILD_ID }
            ?: mostRecentIntent.getLongExtra(INTENT_EXTRA_GUILD_ID, INVALID_GUILD_ID)

    private val viewModel: ServerSettingsIntegrationsViewModel by appViewModels {
        ServerSettingsIntegrationsViewModel(guildId)
    }

    private lateinit var viewFlipper: AppViewFlipper
    private var adapter: Adapter? = null

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarDisplayHomeAsUpEnabled()
        setActionBarTitle(R.string.integrations)
        viewFlipper = view.findViewById(R.id.integrations_view_flipper)
        val recycler = view.findViewById<RecyclerView>(R.id.integrations_recycler)
        adapter = MGRecyclerAdapter.configure<IntegrationItem, Adapter>(Adapter(recycler))
    }

    override fun onResume() {
        super.onResume()
        // Declares this guild as the one the store should keep fetched.
        StoreStream.getGuildIntegrations().onIntegrationScreenOpened(guildId)
    }

    override fun onPause() {
        StoreStream.getGuildIntegrations().onIntegrationScreenClosed()
        super.onPause()
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState().ui(this).subscribe(::configureUI)
    }

    private fun configureUI(viewState: ServerSettingsIntegrationsViewModel.ViewState) {
        when (viewState) {
            is ServerSettingsIntegrationsViewModel.ViewState.Uninitialized ->
                viewFlipper.displayedChild = VIEW_INDEX_LOADING

            is ServerSettingsIntegrationsViewModel.ViewState.Unauthorized ->
                appActivity?.onBackPressed()

            is ServerSettingsIntegrationsViewModel.ViewState.Loaded -> {
                setActionBarSubtitle(viewState.guildName)
                viewFlipper.displayedChild = when {
                    viewState.isLoading -> VIEW_INDEX_LOADING
                    viewState.integrations.isEmpty() -> VIEW_INDEX_NO_INTEGRATIONS
                    else -> VIEW_INDEX_INTEGRATION_LIST
                }
                adapter?.configure(viewState.integrations) { integrationId ->
                    WidgetServerSettingsEditIntegration.launch(
                        requireContext(),
                        viewState.guildId,
                        integrationId,
                    )
                }
            }
        }
    }

    /** One integration, plus the guild it belongs to so its row can act on it. */
    data class IntegrationItem(
        val integration: ModelGuildIntegration,
        val guildId: Long,
    ) : MGRecyclerDataPayload {
        override val type: Int get() = TYPE_INTEGRATION
        override val key: String get() = integration.id.toString()
    }

    // `deferredDiffs = false` matches the reference: the list is short and each row owns a live
    // sync state, so a diff computed a frame later would rebind against stale data.
    class Adapter(recycler: RecyclerView) :
        MGRecyclerAdapterSimple<IntegrationItem>(recycler, deferredDiffs = false) {

        private var onIntegrationSelectedListener: ((Long) -> Unit)? = null

        fun configure(data: List<IntegrationItem>, onIntegrationSelected: ((Long) -> Unit)?) {
            onIntegrationSelectedListener = onIntegrationSelected
            setData(data)
        }

        internal fun onIntegrationSelected(id: Long) {
            onIntegrationSelectedListener?.invoke(id)
        }

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): WidgetServerSettingsIntegrationsListItem =
            if (viewType == TYPE_INTEGRATION) {
                WidgetServerSettingsIntegrationsListItem(this)
            } else {
                throw invalidViewTypeException(viewType)
            }
    }

    companion object {
        private const val INTENT_EXTRA_GUILD_ID = "INTENT_EXTRA_GUILD_ID"
        private const val INVALID_GUILD_ID = -1L

        internal const val TYPE_INTEGRATION = 0

        // The recovered layout has two children; "loading" reuses the list child, which is empty
        // until the store answers. The reference has no separate loading state here either.
        private const val VIEW_INDEX_INTEGRATION_LIST = 0
        private const val VIEW_INDEX_LOADING = 0
        private const val VIEW_INDEX_NO_INTEGRATIONS = 1

        fun newInstance(guildId: Long) = WidgetServerSettingsIntegrations().apply {
            arguments = Bundle().apply { putLong(INTENT_EXTRA_GUILD_ID, guildId) }
        }

        fun launch(context: Context, guildId: Long) {
            SettingsNavigation.launch(
                context,
                WidgetServerSettingsIntegrations::class.java,
                Intent().putExtra(INTENT_EXTRA_GUILD_ID, guildId),
            )
        }
    }
}

/**
 * State for [WidgetServerSettingsIntegrations].
 *
 * `StoreGuildIntegrations.get` emits `null` until the fetch triggered by `onIntegrationScreenOpened`
 * completes, so "not fetched yet" and "this guild has no integrations" stay distinguishable.
 */
class ServerSettingsIntegrationsViewModel(
    guildId: Long,
    storeStateObservable: Observable<StoreState> = observeStoreState(guildId),
) : AppViewModel<ServerSettingsIntegrationsViewModel.ViewState>(ViewState.Uninitialized) {

    data class StoreState(
        val guild: Guild?,
        val canManageServer: Boolean,
        val integrations: Map<Long, ModelGuildIntegration>?,
    )

    sealed class ViewState {
        object Uninitialized : ViewState()
        object Unauthorized : ViewState()

        data class Loaded(
            val guildId: Long,
            val guildName: String,
            val isLoading: Boolean,
            val integrations: List<WidgetServerSettingsIntegrations.IntegrationItem>,
        ) : ViewState()
    }

    init {
        storeStateObservable.computationLatest().distinctUntilChanged().ui(this).subscribe {
            updateViewState(toViewState(it))
        }
    }

    companion object {
        internal fun toViewState(storeState: StoreState): ViewState {
            val guild = storeState.guild
            if (guild == null || !storeState.canManageServer) return ViewState.Unauthorized
            return ViewState.Loaded(
                guildId = guild.id,
                guildName = guild.name,
                isLoading = storeState.integrations == null,
                integrations = storeState.integrations.orEmpty().values
                    // A guild carries integrations this screen has no row for; drop them rather
                    // than render an unlabelled entry.
                    .filter { it.type in ModelGuildIntegration.SUPPORTED_TYPES }
                    .map { WidgetServerSettingsIntegrations.IntegrationItem(it, guild.id) },
            )
        }

        fun observeStoreState(guildId: Long): Observable<StoreState> = Observable.combineLatest(
            StoreStream.getGuildIntegrations().get(guildId),
            StoreStream.getUsers().observeMe(),
            StoreStream.getPermissions().observePermissionsForGuild(guildId),
            StoreStream.getGuilds().observeGuild(guildId),
        ) { integrations, meUser, guildPermissions, guild ->
            StoreState(
                guild = guild,
                canManageServer = guild != null && (
                    guild.isOwner(meUser.id) ||
                        PermissionUtils.canAndIsElevated(
                            Permission.MANAGE_GUILD,
                            guildPermissions,
                            meUser.mfaEnabled,
                            guild.mfaLevel,
                        )
                    ),
                integrations = integrations,
            )
        }
    }
}

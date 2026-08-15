package com.discord.widgets.servers

import android.content.Context
import android.content.Intent
import android.text.method.LinkMovementMethod
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.permission.Permission
import com.discord.app.AppFragment
import com.discord.models.domain.ModelGuildIntegration
import com.discord.models.guild.Guild
import com.discord.models.user.MeUser
import com.discord.stores.StoreStream
import com.discord.utilities.mg_recycler.MGRecyclerAdapter
import com.discord.utilities.mg_recycler.MGRecyclerAdapterSimple
import com.discord.utilities.mg_recycler.MGRecyclerDataPayload
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.ui
import com.discord.utilities.uri.UriHandler
import com.discord.widgets.settings.SettingsNavigation
import com.discord.widgets.settings.SettingsStrings
import com.discord.widgets.settings.connections.WidgetSettingsUserConnections
import rx.Observable

/** The connected accounts of one provider, each with its own role-sync switch. */
class WidgetServerSettingsIntegrationPlatform :
    AppFragment(R.layout.widget_server_settings_integration_platform) {

    private val guildId: Long get() = mostRecentIntent.getLongExtra(ARG_GUILD_ID, -1L)
    private val platform: String
        get() = mostRecentIntent.getStringExtra(ARG_PLATFORM) ?: ModelGuildIntegration.TYPE_TWITCH

    private var adapter: Adapter? = null

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarDisplayHomeAsUpEnabled()
        setActionBarTitle(platformNameRes())
        adapter = MGRecyclerAdapter.configure(
            Adapter(view.findViewById(R.id.integration_platform_recycler)),
        )
        val help = view.findViewById<TextView>(R.id.integration_platform_help)
        help.movementMethod = LinkMovementMethod.getInstance()
        help.text = SettingsStrings.render(
            requireContext(),
            if (platform == ModelGuildIntegration.TYPE_YOUTUBE) {
                R.string.integrations_youtube_help
            } else {
                R.string.integrations_twitch_help
            },
            "helpdeskArticle" to if (platform == ModelGuildIntegration.TYPE_YOUTUBE) {
                YOUTUBE_ARTICLE_URL
            } else {
                TWITCH_ARTICLE_URL
            },
            onLink = { destination ->
                if (destination == CONNECT_ACTION) {
                    WidgetSettingsUserConnections.launch(requireContext())
                } else {
                    UriHandler.handle(requireContext(), destination)
                }
            },
        )
    }

    override fun onResume() {
        super.onResume()
        StoreStream.getGuildIntegrations().onIntegrationScreenOpened(guildId)
        Model.observe(guildId, platform).ui(this)
            .appSubscribe(WidgetServerSettingsIntegrationPlatform::class.java) { model ->
                if (model == null) {
                    appActivity?.finish()
                } else {
                    setActionBarSubtitle(model.guildName)
                    adapter?.configure(model.integrations) { integrationId ->
                        WidgetServerSettingsEditIntegration.launch(requireContext(), guildId, integrationId)
                    }
                }
            }
    }

    override fun onPause() {
        StoreStream.getGuildIntegrations().onIntegrationScreenClosed()
        super.onPause()
    }

    private fun platformNameRes() = if (platform == ModelGuildIntegration.TYPE_YOUTUBE) {
        R.string.integrations_youtube
    } else {
        R.string.integrations_twitch
    }

    class Adapter(recycler: RecyclerView) :
        MGRecyclerAdapterSimple<IntegrationItem>(recycler, deferredDiffs = false) {

        private var onIntegrationSelectedListener: ((Long) -> Unit)? = null

        fun configure(data: List<IntegrationItem>, listener: ((Long) -> Unit)?) {
            onIntegrationSelectedListener = listener
            setData(data)
        }

        internal fun onIntegrationSelected(id: Long) {
            onIntegrationSelectedListener?.invoke(id)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            if (viewType == IntegrationItem.TYPE_INTEGRATION) {
                WidgetServerSettingsIntegrationsListItem(this)
            } else {
                throw invalidViewTypeException(viewType)
            }
    }

    data class IntegrationItem(
        val integration: ModelGuildIntegration,
        val guildId: Long,
    ) : MGRecyclerDataPayload {
        override val type = TYPE_INTEGRATION
        override val key = integration.id.toString()

        companion object {
            internal const val TYPE_INTEGRATION = 0
        }
    }

    private data class Model(val guildName: String, val integrations: List<IntegrationItem>) {
        companion object {
            fun observe(guildId: Long, platform: String): Observable<Model?> = Observable.combineLatest(
                StoreStream.getGuilds().observeGuild(guildId),
                StoreStream.getUsers().observeMe(),
                StoreStream.getPermissions().observePermissionsForGuild(guildId),
                StoreStream.getGuildIntegrations().get(guildId),
            ) { guild: Guild?, me: MeUser, permissions: Long?, integrations: Map<Long, ModelGuildIntegration>? ->
                if (guild == null) return@combineLatest null
                val state = ServerSettingsIntegrationsViewModel.StoreState(
                    guild = guild,
                    canManageServer = guild.isOwner(me.id) || PermissionUtils.canAndIsElevated(
                        Permission.MANAGE_GUILD, permissions, me.mfaEnabled, guild.mfaLevel,
                    ),
                    integrations = integrations,
                    platform = platform,
                )
                when (val viewState = ServerSettingsIntegrationsViewModel.toViewState(state)) {
                    is ServerSettingsIntegrationsViewModel.ViewState.Unauthorized -> null
                    is ServerSettingsIntegrationsViewModel.ViewState.Loaded ->
                        Model(viewState.guildName, viewState.integrations)
                }
            }.computationLatest().distinctUntilChanged()
        }
    }

    companion object {
        private const val ARG_GUILD_ID = "INTENT_EXTRA_GUILD_ID"
        private const val ARG_PLATFORM = "INTENT_EXTRA_PLATFORM"
        private const val CONNECT_ACTION = "connectAction"
        private const val TWITCH_ARTICLE_URL = "https://support.discord.com/hc/en-us/articles/212112068"
        private const val YOUTUBE_ARTICLE_URL = "https://support.discord.com/hc/en-us/articles/215162978"

        fun launch(context: Context, guildId: Long, platform: String) = SettingsNavigation.launch(
            context,
            WidgetServerSettingsIntegrationPlatform::class.java,
            Intent().putExtra(ARG_GUILD_ID, guildId).putExtra(ARG_PLATFORM, platform),
        )
    }
}

internal class ServerSettingsIntegrationsViewModel {
    data class StoreState(
        val guild: Guild?,
        val canManageServer: Boolean,
        val integrations: Map<Long, ModelGuildIntegration>?,
        val platform: String? = null,
    )

    sealed class ViewState {
        object Unauthorized : ViewState()
        data class Loaded(
            val guildId: Long,
            val guildName: String,
            val isLoading: Boolean,
            val integrations: List<WidgetServerSettingsIntegrationPlatform.IntegrationItem>,
        ) : ViewState()
    }

    companion object {
        fun toViewState(state: StoreState): ViewState {
            val guild = state.guild
            if (guild == null || !state.canManageServer) return ViewState.Unauthorized
            return ViewState.Loaded(
                guild.id,
                guild.name,
                state.integrations == null,
                state.integrations.orEmpty().values
                    .filter { it.type in ModelGuildIntegration.SUPPORTED_TYPES }
                    .filter { state.platform == null || it.type == state.platform }
                    .map { WidgetServerSettingsIntegrationPlatform.IntegrationItem(it, guild.id) },
            )
        }
    }
}

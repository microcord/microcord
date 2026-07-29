package com.discord.widgets.servers

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.permission.Permission
import com.discord.api.role.GuildRole
import com.discord.app.AppFragment
import com.discord.app.AppViewFlipper
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
import com.discord.widgets.settings.SettingsNavigation
import rx.Observable

class WidgetServerSettingsIntegrations : AppFragment(R.layout.widget_server_settings_integrations) {

    private val guildId: Long
        get() = arguments?.getLong(INTENT_EXTRA_GUILD_ID, INVALID_GUILD_ID)
            ?.takeIf { it != INVALID_GUILD_ID }
            ?: mostRecentIntent.getLongExtra(INTENT_EXTRA_GUILD_ID, INVALID_GUILD_ID)

    private lateinit var viewFlipper: AppViewFlipper
    private var adapter: Adapter? = null

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarDisplayHomeAsUpEnabled()
        viewFlipper = view.findViewById(R.id.integrations_view_flipper)
        adapter = MGRecyclerAdapter.configure<Model.IntegrationItem, Adapter>(
            Adapter(view.findViewById(R.id.integrations_recycler)),
        )
    }

    override fun onResume() {
        super.onResume()
        StoreStream.getGuildIntegrations().onIntegrationScreenOpened(guildId)
        Model.get(guildId)
            .ui(this)
            .appSubscribe(WidgetServerSettingsIntegrations::class.java, context, onNext = ::configureUI)
    }

    override fun onPause() {
        StoreStream.getGuildIntegrations().onIntegrationScreenClosed()
        super.onPause()
    }

    private fun configureUI(model: Model?) {
        model ?: return
        setActionBarTitle(R.string.integrations)
        setActionBarSubtitle(model.guildName)
        viewFlipper.displayedChild =
            if (model.integrations.isEmpty()) VIEW_INDEX_NO_INTEGRATIONS else VIEW_INDEX_INTEGRATION_LIST
        adapter?.configure(model.integrations) { integrationId ->
            WidgetServerSettingsEditIntegration.launch(requireContext(), model.guildId, integrationId)
        }
    }

    class Adapter(recycler: RecyclerView) :
        MGRecyclerAdapterSimple<Model.IntegrationItem>(recycler, deferredDiffs = false) {

        private var onIntegrationSelectedListener: ((Long) -> Unit)? = null

        fun configure(data: List<Model.IntegrationItem>, listener: ((Long) -> Unit)?) {
            onIntegrationSelectedListener = listener
            setData(data)
        }

        internal fun onIntegrationSelected(id: Long) {
            onIntegrationSelectedListener?.invoke(id)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            if (viewType == Model.IntegrationItem.TYPE_INTEGRATION) {
                WidgetServerSettingsIntegrationsListItem(this)
            } else {
                throw invalidViewTypeException(viewType)
            }
    }

    data class Model(
        val guildId: Long,
        val guildName: String,
        val integrations: List<IntegrationItem>,
    ) {
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

        companion object {
            fun get(guildId: Long): Observable<Model?> = Observable.combineLatest(
                StoreStream.getGuildIntegrations().get(guildId),
                StoreStream.getUsers().observeMe(),
                StoreStream.getPermissions().observePermissionsForGuild(guildId),
                StoreStream.getGuilds().observeGuild(guildId),
                StoreStream.getGuilds().observeRoles(guildId),
            ) { integrations: Map<Long, ModelGuildIntegration>?, meUser: MeUser, permissions: Long?, guild: Guild?, roles: Map<Long, GuildRole>? ->
                if (permissions == null || guild == null || roles == null || integrations == null ||
                    !(guild.isOwner(meUser.id) || PermissionUtils.canAndIsElevated(
                        Permission.MANAGE_GUILD,
                        permissions,
                        meUser.mfaEnabled,
                        guild.mfaLevel,
                    ))
                ) {
                    null
                } else {
                    Model(
                        guild.id,
                        guild.name,
                        integrations.values.mapNotNull { integration ->
                            integration.takeIf { it.type in ModelGuildIntegration.SUPPORTED_TYPES }
                                ?.let { IntegrationItem(it, guild.id) }
                        },
                    )
                }
            }.computationLatest().distinctUntilChanged()
        }
    }

    companion object {
        private const val INTENT_EXTRA_GUILD_ID = "INTENT_EXTRA_GUILD_ID"
        private const val INVALID_GUILD_ID = -1L
        private const val VIEW_INDEX_INTEGRATION_LIST = 0
        private const val VIEW_INDEX_NO_INTEGRATIONS = 1

        fun newInstance(guildId: Long) = WidgetServerSettingsIntegrations().apply {
            arguments = Bundle().apply { putLong(INTENT_EXTRA_GUILD_ID, guildId) }
        }

        @JvmStatic
        fun create(context: Context, guildId: Long) = launch(context, guildId)

        fun launch(context: Context, guildId: Long) {
            StoreStream.getAnalytics().onGuildSettingsPaneViewed("INTEGRATIONS", guildId)
            SettingsNavigation.launch(
                context,
                WidgetServerSettingsIntegrations::class.java,
                Intent().putExtra(INTENT_EXTRA_GUILD_ID, guildId),
            )
        }
    }
}

internal class ServerSettingsIntegrationsViewModel {
    data class StoreState(
        val guild: Guild?,
        val canManageServer: Boolean,
        val integrations: Map<Long, ModelGuildIntegration>?,
    )

    sealed class ViewState {
        object Unauthorized : ViewState()
        data class Loaded(
            val guildId: Long,
            val guildName: String,
            val isLoading: Boolean,
            val integrations: List<WidgetServerSettingsIntegrations.Model.IntegrationItem>,
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
                    .map { WidgetServerSettingsIntegrations.Model.IntegrationItem(it, guild.id) },
            )
        }
    }
}

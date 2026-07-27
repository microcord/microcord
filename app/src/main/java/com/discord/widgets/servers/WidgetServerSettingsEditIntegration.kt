package com.discord.widgets.servers

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.discord.R
import com.discord.api.permission.Permission
import com.discord.api.role.GuildRole
import com.discord.app.AppFragment
import com.discord.app.AppViewModel
import com.discord.app.appViewModels
import com.discord.i18n.i18nPlural
import com.discord.models.domain.ModelGuildIntegration
import com.discord.models.guild.Guild
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreStream
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.dimmer.DimmerView
import com.discord.utilities.guilds.RoleUtils
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.time.TimeUtils
import com.discord.views.CheckedSetting
import com.discord.views.RadioManager
import com.discord.widgets.settings.SettingsNavigation
import com.discord.widgets.settings.SettingsStrings
import com.discord.widgets.settings.StatefulViews
import com.google.android.material.floatingactionbutton.FloatingActionButton
import rx.Observable

/**
 * Settings for one Twitch/YouTube integration: what happens when a subscription lapses, how long the
 * grace period is, and (Twitch only) whether subscriber emotes sync into the guild.
 *
 * Reference: `com.discord.widgets.servers.WidgetServerSettingsEditIntegration`. Unlike the
 * integration list, this screen batches its edits behind a save FAB — the three controls feed
 * [StatefulViews], which shows the FAB only once something actually differs and warns on Back.
 *
 * The screen also drives `StoreGuildIntegrations`' fetch lifecycle itself, because it can be entered
 * directly and must not depend on the list screen still being resumed.
 */
class WidgetServerSettingsEditIntegration :
    AppFragment(R.layout.widget_server_settings_edit_integration) {

    private val guildId: Long
        get() = arguments?.getLong(INTENT_EXTRA_GUILD_ID, INVALID_ID)
            ?.takeIf { it != INVALID_ID }
            ?: mostRecentIntent.getLongExtra(INTENT_EXTRA_GUILD_ID, INVALID_ID)

    private val integrationId: Long
        get() = arguments?.getLong(INTENT_EXTRA_INTEGRATION_ID, INVALID_ID)
            ?.takeIf { it != INVALID_ID }
            ?: mostRecentIntent.getLongExtra(INTENT_EXTRA_INTEGRATION_ID, INVALID_ID)

    private val viewModel: ServerSettingsEditIntegrationViewModel by appViewModels {
        ServerSettingsEditIntegrationViewModel(guildId, integrationId)
    }

    private val state = StatefulViews(
        R.id.edit_integration_expired_sub_container,
        R.id.edit_integration_grace_period_container,
        R.id.edit_integration_custom_emotes_toggle,
    )

    private lateinit var dimmer: DimmerView
    private lateinit var icon: ImageView
    private lateinit var name: TextView
    private lateinit var ownerName: TextView
    private lateinit var syncContainer: View
    private lateinit var lastSyncTime: TextView
    private lateinit var subscriberCount: TextView
    private lateinit var syncedRoleContainer: View
    private lateinit var syncedRole: TextView
    private lateinit var customEmotesContainer: LinearLayout
    private lateinit var customEmotesToggle: CheckedSetting
    private lateinit var save: FloatingActionButton

    private lateinit var expiryBehaviorRadios: List<CheckedSetting>
    private lateinit var gracePeriodRadios: List<CheckedSetting>
    private lateinit var expiryBehaviorRadioManager: RadioManager
    private lateinit var gracePeriodRadioManager: RadioManager

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarDisplayHomeAsUpEnabled()
        setActionBarTitle(R.string.integration_settings)

        dimmer = view.findViewById(R.id.dimmer_view)
        icon = view.findViewById(R.id.edit_integration_icon)
        name = view.findViewById(R.id.edit_integration_name)
        ownerName = view.findViewById(R.id.edit_integration_owner_name)
        syncContainer = view.findViewById(R.id.edit_integration_sync_container)
        lastSyncTime = view.findViewById(R.id.edit_integration_last_sync_time)
        subscriberCount = view.findViewById(R.id.edit_integration_subscriber_count)
        syncedRoleContainer = view.findViewById(R.id.edit_integration_synced_role_container)
        syncedRole = view.findViewById(R.id.edit_integration_synced_role)
        customEmotesContainer = view.findViewById(R.id.edit_integration_custom_emotes_container)
        customEmotesToggle = view.findViewById(R.id.edit_integration_custom_emotes_toggle)
        save = view.findViewById(R.id.edit_integration_save)

        expiryBehaviorRadios = listOf(
            view.findViewById(R.id.edit_integration_expire_radio_remove_role),
            view.findViewById(R.id.edit_integration_expire_radio_kick_user),
        )
        gracePeriodRadios = listOf(
            view.findViewById(R.id.edit_integration_grace_period_radio_1_day),
            view.findViewById(R.id.edit_integration_grace_period_radio_3_days),
            view.findViewById(R.id.edit_integration_grace_period_radio_7_days),
            view.findViewById(R.id.edit_integration_grace_period_radio_14_days),
            view.findViewById(R.id.edit_integration_grace_period_radio_30_days),
        )
        // The grace-period labels are the day counts themselves, so they are built rather than
        // declared in the layout.
        gracePeriodRadios.forEachIndexed { index, radio ->
            radio.setText(
                SettingsStrings.render(
                    radio.context,
                    radio.context.i18nPlural(
                        R.plurals.n_days_days,
                        GRACE_PERIOD_DAYS[index],
                        "days" to GRACE_PERIOD_DAYS[index],
                    ),
                ),
            )
        }

        expiryBehaviorRadioManager =
            setupRadioManager(expiryBehaviorRadios, R.id.edit_integration_expired_sub_container)
        gracePeriodRadioManager =
            setupRadioManager(gracePeriodRadios, R.id.edit_integration_grace_period_container)

        // The toggle is driven by hand rather than through `setupTextWatcherWithSaveAction` because
        // the reference toggles it from the row container, not the switch itself.
        customEmotesToggle.setContainerClickListener {
            customEmotesToggle.toggle()
            state.put(R.id.edit_integration_custom_emotes_toggle, customEmotesToggle.isChecked())
            state.configureSaveActionView(save)
        }

        state.setupUnsavedChangesConfirmation(this)
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        StoreStream.getGuildIntegrations().onIntegrationScreenOpened(guildId)
        viewModel.observeViewState().ui(this).subscribe(::configureUI)
    }

    override fun onPause() {
        super.onPause()
        StoreStream.getGuildIntegrations().onIntegrationScreenClosed()
    }

    private fun configureUI(viewState: ServerSettingsEditIntegrationViewModel.ViewState) {
        when (viewState) {
            is ServerSettingsEditIntegrationViewModel.ViewState.Uninitialized ->
                dimmer.setDimmed(true)

            // The integration was removed, or Manage Server was lost.
            is ServerSettingsEditIntegrationViewModel.ViewState.Unavailable ->
                appActivity?.onBackPressed()

            is ServerSettingsEditIntegrationViewModel.ViewState.Loaded -> configureLoaded(viewState)
        }
    }

    private fun configureLoaded(viewState: ServerSettingsEditIntegrationViewModel.ViewState.Loaded) {
        val integration = viewState.integration
        val isTwitch = integration.type == ModelGuildIntegration.TYPE_TWITCH
        setActionBarSubtitle(viewState.guild.name)

        name.text = integration.displayName
        ownerName.text = integration.user?.username
        icon.setImageResource(
            if (isTwitch) R.drawable.asset_account_sync_twitch else R.drawable.asset_account_sync_youtube,
        )

        syncContainer.setOnClickListener { syncNow(viewState.guild.id, integration.id) }
        // A sync in progress blocks the whole form, since its outcome changes what is displayed.
        dimmer.setDimmed(integration.syncing)

        lastSyncTime.text =
            TimeUtils.toReadableTimeString(requireContext(), TimeUtils.parseUTCDate(integration.syncedAt))
        subscriberCount.text = SettingsStrings.render(
            requireContext(),
            requireContext().i18nPlural(
                if (isTwitch) R.plurals.num_subscribers_subscribers else R.plurals.num_members_subscribers,
                integration.subscriberCount,
                "subscribers" to integration.subscriberCount,
            ),
        )

        val role = viewState.role
        // The reference opens `WidgetServerSettingsEditRole` from this row. That screen has not been
        // reconstructed, so the row shows the synced role but does not navigate; restore the
        // listener when it lands (recorded in docs/parity-audit/followups.md).
        syncedRoleContainer.setOnClickListener(null)
        syncedRoleContainer.isClickable = false
        syncedRole.text = role?.name ?: getString(R.string.none)
        syncedRole.setTextColor(
            RoleUtils.getOpaqueColor(role, ColorCompat.getColor(this, R.color.primary_300)),
        )

        // `state.get` records the server value and hands back the user's pending pick if there is
        // one, so a store emission mid-edit does not reset the radios.
        val expiryPosition = state.get(
            R.id.edit_integration_expired_sub_container,
            integration.expireBehavior,
        )
        expiryBehaviorRadioManager.setChecked(
            expiryBehaviorRadios[expiryPosition.coerceIn(expiryBehaviorRadios.indices)],
        )
        val gracePeriodPosition = state.get(
            R.id.edit_integration_grace_period_container,
            gracePeriodPositionOf(integration.expireGracePeriod),
        )
        gracePeriodRadioManager.setChecked(
            gracePeriodRadios[gracePeriodPosition.coerceIn(gracePeriodRadios.indices)],
        )

        // Emote sync is a Twitch-only concept.
        customEmotesContainer.visibility = if (isTwitch) View.VISIBLE else View.GONE
        customEmotesToggle.setChecked(
            state.get(customEmotesToggle.id, integration.enableEmoticons),
            false,
        )

        state.configureSaveActionView(save)
        save.setOnClickListener { saveChanges(viewState.guild.id, integration.id) }
    }

    private fun syncNow(guildId: Long, integrationId: Long) {
        val api = RestAPI.apiOrNull ?: return
        api.syncIntegration(guildId, integrationId)
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(WidgetServerSettingsEditIntegration::class.java, context) {
                // The gateway's integration update carries `syncing = true` a moment later; dim now
                // so the row cannot be tapped twice.
                dimmer.setDimmed(true)
            }
    }

    private fun saveChanges(guildId: Long, integrationId: Long) {
        val api = RestAPI.apiOrNull ?: return
        api.updateGuildIntegration(
            guildId,
            integrationId,
            RestAPIParams.GuildIntegration(
                expireBehavior = expiryBehaviorRadioManager.checkedButtonIndex,
                expireGracePeriod = GRACE_PERIOD_DAYS[gracePeriodRadioManager.checkedButtonIndex],
                enableEmoticons = customEmotesToggle.isChecked(),
            ),
        )
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(WidgetServerSettingsEditIntegration::class.java, context) {}
    }

    /**
     * Builds a [RadioManager] over [radios] and records the picked index into [state] under
     * [stateKey], so the save action appears as soon as the selection differs from the server's.
     */
    private fun setupRadioManager(radios: List<CheckedSetting>, stateKey: Int): RadioManager {
        val manager = RadioManager(radios)
        radios.forEachIndexed { index, radio ->
            radio.setContainerClickListener {
                manager.setChecked(radio)
                state.put(stateKey, index)
                state.configureSaveActionView(save)
            }
        }
        return manager
    }

    /** Index of [gracePeriodDays] in [GRACE_PERIOD_DAYS]; an unknown value falls back to 1 day. */
    private fun gracePeriodPositionOf(gracePeriodDays: Int): Int =
        GRACE_PERIOD_DAYS.indexOf(gracePeriodDays).takeIf { it >= 0 } ?: 0

    companion object {
        private const val INTENT_EXTRA_GUILD_ID = "INTENT_EXTRA_GUILD_ID"
        private const val INTENT_EXTRA_INTEGRATION_ID = "INTENT_EXTRA_INTEGRATION_ID"
        private const val INVALID_ID = -1L

        /** The grace periods Discord offers, in the radio order the layout declares. */
        private val GRACE_PERIOD_DAYS = listOf(1, 3, 7, 14, 30)

        fun newInstance(guildId: Long, integrationId: Long) =
            WidgetServerSettingsEditIntegration().apply {
                arguments = Bundle().apply {
                    putLong(INTENT_EXTRA_GUILD_ID, guildId)
                    putLong(INTENT_EXTRA_INTEGRATION_ID, integrationId)
                }
            }

        fun launch(context: Context, guildId: Long, integrationId: Long) {
            SettingsNavigation.launch(
                context,
                WidgetServerSettingsEditIntegration::class.java,
                Intent()
                    .putExtra(INTENT_EXTRA_GUILD_ID, guildId)
                    .putExtra(INTENT_EXTRA_INTEGRATION_ID, integrationId),
            )
        }
    }
}

/**
 * State for [WidgetServerSettingsEditIntegration].
 *
 * Reference: `WidgetServerSettingsEditIntegration.Model`. Note it requires the integration to be
 * **enabled** as well as the user to hold Manage Server — a disabled integration has no settings to
 * edit, so the screen closes rather than showing a dead form.
 */
class ServerSettingsEditIntegrationViewModel(
    guildId: Long,
    integrationId: Long,
    storeStateObservable: Observable<StoreState> = observeStoreState(guildId, integrationId),
) : AppViewModel<ServerSettingsEditIntegrationViewModel.ViewState>(ViewState.Uninitialized) {

    data class StoreState(
        val guild: Guild?,
        val integration: ModelGuildIntegration?,
        val role: GuildRole?,
        val canManageServer: Boolean,
    )

    sealed class ViewState {
        object Uninitialized : ViewState()

        /** No such integration, it is disabled, or the user may not manage the guild. */
        object Unavailable : ViewState()

        data class Loaded(
            val guild: Guild,
            val integration: ModelGuildIntegration,
            val role: GuildRole?,
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
            val integration = storeState.integration
            return if (guild == null || integration == null ||
                !integration.enabled || !storeState.canManageServer
            ) {
                ViewState.Unavailable
            } else {
                ViewState.Loaded(guild, integration, storeState.role)
            }
        }

        fun observeStoreState(guildId: Long, integrationId: Long): Observable<StoreState> =
            Observable.combineLatest(
                StoreStream.getUsers().observeMe(),
                StoreStream.getGuilds().observeGuild(guildId),
                StoreStream.getGuilds().observeRoles(guildId),
                StoreStream.getPermissions().observePermissionsForGuild(guildId),
                StoreStream.getGuildIntegrations().get(guildId, integrationId),
            ) { meUser, guild, roles, guildPermissions, integration ->
                StoreState(
                    guild = guild,
                    integration = integration,
                    role = integration?.let { roles[it.roleId] },
                    canManageServer = guild != null && PermissionUtils.canAndIsElevated(
                        Permission.MANAGE_GUILD,
                        guildPermissions,
                        meUser.mfaEnabled,
                        guild.mfaLevel,
                    ),
                )
            }
    }
}

package com.discord.widgets.servers

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import com.discord.R
import com.discord.api.guild.GuildExplicitContentFilter
import com.discord.api.guild.GuildFeature
import com.discord.api.guild.GuildVerificationLevel
import com.discord.api.permission.Permission
import com.discord.app.AppFragment
import com.discord.app.AppToast
import com.discord.app.AppViewModel
import com.discord.app.appViewModels
import com.discord.models.guild.Guild
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreStream
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.views.CheckedSetting
import com.discord.views.RadioManager
import com.discord.widgets.settings.SettingsNavigation
import com.discord.widgets.settings.SettingsStrings
import rx.Observable

/**
 * "Moderation" server settings: the verification level and the explicit-content filter.
 *
 * Reference: `com.discord.widgets.servers.WidgetServerSettingsModeration`. Both settings are radio
 * groups that write straight through to `PATCH /guilds/{id}`; there is no local pending state and no
 * save button, so the guild store's own update is what moves the selection.
 *
 * A **Community** guild has a floor on both settings — it may not drop verification to `NONE`, and
 * it may not set the explicit-content filter below `ALL`. The reference does not hide those rows; it
 * leaves them present and gives them a disabled tooltip
 * (`R.string.tooltip_community_feature_disabled`) so the moderator can see the option exists and why
 * it cannot be picked. That is reproduced here.
 */
class WidgetServerSettingsModeration : AppFragment(R.layout.widget_server_settings_moderation) {

    private val guildId: Long
        get() = arguments?.getLong(INTENT_EXTRA_GUILD_ID, INVALID_GUILD_ID)
            ?.takeIf { it != INVALID_GUILD_ID }
            ?: mostRecentIntent.getLongExtra(INTENT_EXTRA_GUILD_ID, INVALID_GUILD_ID)

    private val viewModel: ServerSettingsModerationViewModel by appViewModels {
        ServerSettingsModerationViewModel(guildId)
    }

    private lateinit var verificationRows: List<CheckedSetting>
    private lateinit var explicitContentRows: List<CheckedSetting>
    private lateinit var verificationRadios: RadioManager
    private lateinit var explicitContentRadios: RadioManager

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarDisplayHomeAsUpEnabled()
        setActionBarTitle(R.string.moderation)

        verificationRows = listOf(
            view.findViewById(R.id.server_settings_moderation_verification_1),
            view.findViewById(R.id.server_settings_moderation_verification_2),
            view.findViewById(R.id.server_settings_moderation_verification_3),
            view.findViewById(R.id.server_settings_moderation_verification_4),
            view.findViewById(R.id.server_settings_moderation_verification_5),
        )
        explicitContentRows = listOf(
            view.findViewById(R.id.server_settings_moderation_explicit_1),
            view.findViewById(R.id.server_settings_moderation_explicit_2),
            view.findViewById(R.id.server_settings_moderation_explicit_3),
        )

        view.findViewById<TextView>(R.id.server_settings_moderation_verification_help).text =
            SettingsStrings.render(view.context, R.string.form_help_verification_level)
        view.findViewById<TextView>(R.id.server_settings_moderation_explicit_help).text =
            SettingsStrings.render(view.context, R.string.form_help_explicit_content_filter)

        verificationRadios = RadioManager(verificationRows)
        explicitContentRadios = RadioManager(explicitContentRows)

        // The two middle criteria are the only ones with a duration in them, and the duration is a
        // formatted argument rather than part of the string.
        verificationRows[MEDIUM_ROW].setSubtext(
            SettingsStrings.render(
                view.context,
                R.string.verification_level_medium_criteria,
                "min" to VERIFICATION_LEVEL_MEDIUM_MINUTES,
            ),
        )
        verificationRows[HIGH_ROW].setSubtext(
            SettingsStrings.render(
                view.context,
                R.string.verification_level_high_criteria,
                "min" to VERIFICATION_LEVEL_HIGH_MINUTES,
            ),
        )
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState().ui(this).subscribe(::configureUI)
    }

    private fun configureUI(viewState: ServerSettingsModerationViewModel.ViewState) {
        when (viewState) {
            is ServerSettingsModerationViewModel.ViewState.Uninitialized -> Unit
            // Losing Manage Server while the screen is open is the same as never having reached it.
            is ServerSettingsModerationViewModel.ViewState.Unauthorized -> appActivity?.onBackPressed()
            is ServerSettingsModerationViewModel.ViewState.Loaded -> {
                setActionBarSubtitle(viewState.guild.name)
                GuildVerificationLevel.values().forEachIndexed { index, level ->
                    configureVerificationRow(viewState, verificationRows[index], level)
                }
                GuildExplicitContentFilter.values().forEachIndexed { index, filter ->
                    configureExplicitContentRow(viewState, explicitContentRows[index], filter)
                }
            }
        }
    }

    private fun configureVerificationRow(
        viewState: ServerSettingsModerationViewModel.ViewState.Loaded,
        row: CheckedSetting,
        level: GuildVerificationLevel,
    ) {
        // A Community guild has to keep at least the lowest verification level.
        if (viewState.isCommunity && level == GuildVerificationLevel.NONE) {
            row.setDisabledTooltip(R.string.tooltip_community_feature_disabled)
        } else {
            row.setContainerClickListener {
                update(RestAPIParams.UpdateGuild.createForVerificationLevel(level))
            }
        }
        if (viewState.guild.verificationLevel == level) verificationRadios.setChecked(row)
    }

    private fun configureExplicitContentRow(
        viewState: ServerSettingsModerationViewModel.ViewState.Loaded,
        row: CheckedSetting,
        filter: GuildExplicitContentFilter,
    ) {
        // A Community guild must scan every message, so anything below ALL is unavailable.
        if (viewState.isCommunity && filter < GuildExplicitContentFilter.ALL) {
            row.setDisabledTooltip(R.string.tooltip_community_feature_disabled)
        } else {
            row.setContainerClickListener {
                update(RestAPIParams.UpdateGuild.createForExplicitContentFilter(filter))
            }
        }
        if (viewState.guild.explicitContentFilter == filter) explicitContentRadios.setChecked(row)
    }

    /**
     * The radio does not move on tap: the guild store's own update moves it, so a rejected change
     * simply leaves the previous row selected instead of snapping back.
     */
    private fun update(body: RestAPIParams.UpdateGuild) {
        val api = RestAPI.apiOrNull ?: return
        api.updateGuild(guildId, body)
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(WidgetServerSettingsModeration::class.java, context) {
                AppToast.show(this, R.string.server_settings_updated)
            }
    }

    companion object {
        private const val INTENT_EXTRA_GUILD_ID = "INTENT_EXTRA_GUILD_ID"
        private const val INVALID_GUILD_ID = -1L

        private const val VERIFICATION_LEVEL_MEDIUM_MINUTES = "5"
        private const val VERIFICATION_LEVEL_HIGH_MINUTES = "10"

        private const val MEDIUM_ROW = 2
        private const val HIGH_ROW = 3

        fun newInstance(guildId: Long) = WidgetServerSettingsModeration().apply {
            arguments = Bundle().apply { putLong(INTENT_EXTRA_GUILD_ID, guildId) }
        }

        fun launch(context: Context, guildId: Long) {
            SettingsNavigation.launch(
                context,
                WidgetServerSettingsModeration::class.java,
                Intent().putExtra(INTENT_EXTRA_GUILD_ID, guildId),
            )
        }
    }
}

/**
 * State for [WidgetServerSettingsModeration].
 *
 * Reference: `WidgetServerSettingsModeration.Model`, whose `get` emits `null` — closing the screen —
 * unless the user owns the guild or holds Manage Server *at the guild's required MFA elevation*.
 */
class ServerSettingsModerationViewModel(
    guildId: Long,
    storeStateObservable: Observable<StoreState> = observeStoreState(guildId),
) : AppViewModel<ServerSettingsModerationViewModel.ViewState>(ViewState.Uninitialized) {

    data class StoreState(val guild: Guild?, val canManageServer: Boolean)

    sealed class ViewState {
        object Uninitialized : ViewState()

        /** The guild is gone, or the user may not manage it. The screen closes itself. */
        object Unauthorized : ViewState()

        data class Loaded(val guild: Guild) : ViewState() {
            val isCommunity: Boolean get() = guild.hasFeature(GuildFeature.COMMUNITY)
        }
    }

    init {
        storeStateObservable.computationLatest().distinctUntilChanged().ui(this).subscribe {
            updateViewState(toViewState(it))
        }
    }

    companion object {
        internal fun toViewState(storeState: StoreState): ViewState {
            val guild = storeState.guild
            return if (guild == null || !storeState.canManageServer) {
                ViewState.Unauthorized
            } else {
                ViewState.Loaded(guild)
            }
        }

        fun observeStoreState(guildId: Long): Observable<StoreState> = Observable.combineLatest(
            StoreStream.getGuilds().observeGuild(guildId),
            StoreStream.getUsers().observeMe(),
            StoreStream.getPermissions().observePermissionsForGuild(guildId),
        ) { guild, meUser, guildPermissions ->
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
            )
        }
    }
}

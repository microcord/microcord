package com.discord.widgets.servers

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.channel.isGuildTextChannel
import com.discord.api.channel.isGuildVocalChannel
import com.discord.api.permission.Permission
import com.discord.app.AppBottomSheet
import com.discord.app.AppFragment
import com.discord.app.AppToast
import com.discord.app.AppViewModel
import com.discord.app.appViewModels
import com.discord.i18n.i18nPlural
import com.discord.models.guild.Guild
import com.discord.restapi.RestAPIInterface
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreStream
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.view.extensions.getTextOrEmpty
import com.discord.utilities.view.extensions.setText
import com.discord.views.CheckedSetting
import com.discord.views.RadioManager
import com.discord.widgets.settings.SettingsNavigation
import com.discord.widgets.settings.SettingsStrings
import com.discord.widgets.settings.StatefulViews
import com.facebook.drawee.view.SimpleDraweeView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputLayout
import rx.Observable

/**
 * "Overview" server settings: name and icon, AFK channel and timeout, system channel and its
 * message flags, and the guild's default notification level.
 *
 * Reference: `com.discord.widgets.servers.WidgetServerSettingsOverview`. Every control writes into
 * [StatefulViews] rather than straight to the API, and one `PATCH /guilds/{id}` at the save FAB
 * commits the lot — so the screen has to keep rendering from the store without discarding pending
 * edits, which is exactly what `StatefulViews.get(id, serverValue)` provides.
 *
 * Two behaviours worth naming:
 * - **System-channel flags are stored inverted.** `systemChannelFlags` is a *suppression* bitmask, so
 *   a checked "send join messages" switch means the bit is **clear**. The save path rebuilds the
 *   mask from the three switches.
 * - **"All messages" is disabled on a large guild.** Above Discord's member threshold the default
 *   notification level is forced to mentions-only, and the radio carries a tooltip saying why.
 *
 * ### Deliberate degradations
 * - **Image upload is unavailable.** Guild icon, banner and splash uploads need
 *   `MGImages.requestImageCrop`/`prepareImageUpload`, which this project has not reconstructed
 *   (`docs/parity-audit/followups.md`). The icon renders read-only and the banner/splash sections are
 *   hidden rather than shown with a dead upload button.
 * - **Channel pickers are a plain chooser.** The reference opens `WidgetChannelSelector`, a
 *   searchable full screen that does not exist here; the eligible channels are listed in a dialog
 *   instead. Same result, less polish.
 */
class WidgetServerSettingsOverview : AppFragment(R.layout.widget_server_settings_overview) {

    private val guildId: Long
        get() = arguments?.getLong(INTENT_EXTRA_GUILD_ID, INVALID_GUILD_ID)
            ?.takeIf { it != INVALID_GUILD_ID }
            ?: mostRecentIntent.getLongExtra(INTENT_EXTRA_GUILD_ID, INVALID_GUILD_ID)

    private val viewModel: ServerSettingsOverviewViewModel by appViewModels {
        ServerSettingsOverviewViewModel(guildId)
    }

    private val state = StatefulViews(
        R.id.overview_name,
        R.id.afk_channel,
        R.id.afk_timeout_wrap,
        R.id.system_channel,
        R.id.system_channel_join,
        R.id.system_channel_join_replies,
        R.id.system_channel_boost,
        STATE_ID_NOTIFICATION_DEFAULT,
    )

    private lateinit var scroll: ScrollView
    private lateinit var icon: SimpleDraweeView
    private lateinit var iconText: TextView
    private lateinit var iconLabel: TextView
    private lateinit var iconRemove: TextView
    private lateinit var nameInput: TextInputLayout
    private lateinit var afkChannel: TextView
    private lateinit var afkChannelWrap: View
    private lateinit var afkTimeout: TextView
    private lateinit var afkTimeoutWrap: View
    private lateinit var systemChannel: TextView
    private lateinit var systemChannelWrap: View
    private lateinit var systemChannelJoin: CheckedSetting
    private lateinit var systemChannelJoinReplies: CheckedSetting
    private lateinit var systemChannelBoost: CheckedSetting
    private lateinit var notificationAll: CheckedSetting
    private lateinit var notificationOnlyMentions: CheckedSetting
    private lateinit var notificationRadios: RadioManager
    private lateinit var save: FloatingActionButton

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarDisplayHomeAsUpEnabled()
        setActionBarTitle(R.string.overview)

        scroll = view.findViewById(R.id.server_settings_overview_scroll)
        icon = view.findViewById(R.id.server_settings_overview_icon)
        iconText = view.findViewById(R.id.server_settings_overview_icon_text)
        iconLabel = view.findViewById(R.id.icon_label)
        iconRemove = view.findViewById(R.id.server_settings_overview_icon_remove)
        nameInput = view.findViewById(R.id.overview_name)
        afkChannel = view.findViewById(R.id.afk_channel)
        afkChannelWrap = view.findViewById(R.id.afk_channel_wrap)
        afkTimeout = view.findViewById(R.id.afk_timeout)
        afkTimeoutWrap = view.findViewById(R.id.afk_timeout_wrap)
        systemChannel = view.findViewById(R.id.system_channel)
        systemChannelWrap = view.findViewById(R.id.system_channel_wrap)
        systemChannelJoin = view.findViewById(R.id.system_channel_join)
        systemChannelJoinReplies = view.findViewById(R.id.system_channel_join_replies)
        systemChannelBoost = view.findViewById(R.id.system_channel_boost)
        notificationAll = view.findViewById(R.id.server_settings_overview_notification_all)
        notificationOnlyMentions =
            view.findViewById(R.id.server_settings_overview_notification_only_mentions)
        save = view.findViewById(R.id.save)

        iconLabel.text = SettingsStrings.render(
            view.context,
            R.string.minimum_size,
            "size" to MINIMUM_ICON_SIZE_PX,
        )
        // No upload path exists, so the "remove icon" affordance would be one-way. See class KDoc.
        iconRemove.visibility = View.GONE
        view.findViewById<View>(R.id.uploadBanner).visibility = View.GONE
        view.findViewById<View>(R.id.uploadSplash).visibility = View.GONE

        notificationRadios = RadioManager(listOf(notificationAll, notificationOnlyMentions))

        save.hide()
        state.setupUnsavedChangesConfirmation(this)
        state.setupTextWatcherWithSaveAction(this, save, nameInput)
        // The two channel rows and the timeout row are set by a picker, not typed into, so they are
        // registered as optional: a guild legitimately has no AFK or system channel.
        state.addOptionalFields(afkChannel, systemChannel)
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState().ui(this).subscribe(::configureUI)
    }

    private fun configureUI(viewState: ServerSettingsOverviewViewModel.ViewState) {
        when (viewState) {
            is ServerSettingsOverviewViewModel.ViewState.Uninitialized -> Unit
            is ServerSettingsOverviewViewModel.ViewState.Unauthorized -> appActivity?.onBackPressed()
            is ServerSettingsOverviewViewModel.ViewState.Loaded -> configureLoaded(viewState)
        }
    }

    private fun configureLoaded(viewState: ServerSettingsOverviewViewModel.ViewState.Loaded) {
        val guild = viewState.guild
        setActionBarSubtitle(guild.name)

        configureIcon(guild)
        nameInput.setText(state.get(nameInput.id, guild.name))

        configureAfkChannel(state.get(afkChannel.id, viewState.afkChannel))
        afkChannelWrap.setOnClickListener {
            pickChannel(
                title = R.string.form_label_afk_channel,
                emptyLabel = R.string.no_afk_channel,
                channels = viewState.voiceChannels,
            ) { picked ->
                state.put(afkChannel.id, picked)
                configureAfkChannel(picked)
                state.configureSaveActionView(save)
            }
        }

        val afkTimeoutSeconds = state.get(afkTimeoutWrap.id, guild.afkTimeout)
        afkTimeout.text = afkTimeoutLabel(requireContext(), afkTimeoutSeconds)
        afkTimeoutWrap.setOnClickListener {
            AfkTimeoutBottomSheet()
                .show(childFragmentManager, AfkTimeoutBottomSheet::class.java.name)
        }

        val currentSystemChannel = state.get(systemChannel.id, viewState.systemChannel)
        configureSystemChannel(currentSystemChannel)
        systemChannelWrap.setOnClickListener {
            pickChannel(
                title = R.string.form_label_system_channel,
                emptyLabel = R.string.no_system_channel,
                channels = viewState.textChannels,
            ) { picked ->
                state.put(systemChannel.id, picked)
                configureSystemChannel(picked)
                configureSystemChannelToggles(guild, picked != null)
                state.configureSaveActionView(save)
            }
        }
        configureSystemChannelToggles(guild, currentSystemChannel != null)

        configureNotificationRadios(
            selected = state.get(STATE_ID_NOTIFICATION_DEFAULT, guild.defaultMessageNotifications),
            isAboveNotifyAllSize = viewState.isAboveNotifyAllSize,
        )

        state.configureSaveActionView(save)
        save.setOnClickListener { saveChanges(viewState) }
    }

    private fun configureIcon(guild: Guild) {
        val iconUrl = IconUtils.getForGuild(guild, allowAnimation = true)
        IconUtils.setIcon(icon, iconUrl, R.dimen.avatar_size_xxlarge)
        // The lettered fallback is a sibling of the image, not a placeholder inside it.
        iconText.visibility = if (iconUrl == null) View.VISIBLE else View.GONE
        iconText.text = guild.shortName
    }

    private fun configureAfkChannel(channel: Channel?) {
        afkChannel.text = channel?.name ?: getString(R.string.no_afk_channel)
    }

    private fun configureSystemChannel(channel: Channel?) {
        systemChannel.text = channel?.name ?: getString(R.string.no_system_channel)
    }

    /**
     * Binds the three system-channel switches.
     *
     * `systemChannelFlags` suppresses messages, so each switch shows the **inverse** of its bit.
     * With no system channel there is nowhere to send them, and the reference disables all three.
     */
    private fun configureSystemChannelToggles(guild: Guild, hasSystemChannel: Boolean) {
        val toggles = listOf(
            systemChannelJoin to SYSTEM_CHANNEL_SUPPRESS_JOIN_NOTIFICATIONS,
            systemChannelJoinReplies to SYSTEM_CHANNEL_SUPPRESS_JOIN_NOTIFICATION_REPLIES,
            systemChannelBoost to SYSTEM_CHANNEL_SUPPRESS_PREMIUM_SUBSCRIPTIONS,
        )
        toggles.forEach { (toggle, flag) ->
            val enabledOnServer = (guild.systemChannelFlags and flag) == 0
            toggle.setChecked(state.get(toggle.id, enabledOnServer), false)
            if (hasSystemChannel) {
                toggle.setContainerClickListener {
                    toggle.toggle()
                    state.put(toggle.id, toggle.isChecked())
                    state.configureSaveActionView(save)
                }
            } else {
                toggle.setContainerClickListener(null)
                toggle.setDisabledTooltip(null)
            }
        }
    }

    private fun configureNotificationRadios(selected: Int, isAboveNotifyAllSize: Boolean) {
        val radios = listOf(
            notificationAll to NOTIFICATION_ALL_MESSAGES,
            notificationOnlyMentions to NOTIFICATION_ONLY_MENTIONS,
        )
        radios.forEach { (radio, level) ->
            // A guild past the member threshold may not default everyone to "all messages"; the
            // tooltip is the only thing that explains the greyed-out row.
            if (level == NOTIFICATION_ALL_MESSAGES && isAboveNotifyAllSize) {
                radio.setDisabledTooltip(R.string.large_guild_notify_all_messages_description)
            } else {
                radio.setContainerClickListener {
                    notificationRadios.setChecked(radio)
                    state.put(STATE_ID_NOTIFICATION_DEFAULT, level)
                    state.configureSaveActionView(save)
                }
            }
            if (selected == level) notificationRadios.setChecked(radio)
        }
    }

    /**
     * Commits every pending edit in one PATCH.
     *
     * Fields the screen does not own are echoed back unchanged, exactly as the reference does —
     * `PATCH /guilds` replaces what it is given, and omitting `features` in particular would drop
     * them.
     */
    private fun saveChanges(viewState: ServerSettingsOverviewViewModel.ViewState.Loaded) {
        val guild = viewState.guild
        val api = serializeNullsApi ?: RestAPI.apiOrNull ?: return

        var systemChannelFlags = 0
        if (!state.get(systemChannelJoin.id, systemChannelJoin.isChecked())) {
            systemChannelFlags = systemChannelFlags or SYSTEM_CHANNEL_SUPPRESS_JOIN_NOTIFICATIONS
        }
        if (!state.get(systemChannelJoinReplies.id, systemChannelJoinReplies.isChecked())) {
            systemChannelFlags =
                systemChannelFlags or SYSTEM_CHANNEL_SUPPRESS_JOIN_NOTIFICATION_REPLIES
        }
        if (!state.get(systemChannelBoost.id, systemChannelBoost.isChecked())) {
            systemChannelFlags = systemChannelFlags or SYSTEM_CHANNEL_SUPPRESS_PREMIUM_SUBSCRIPTIONS
        }

        api.updateGuild(
            guild.id,
            RestAPIParams.UpdateGuild(
                afkChannelId = state.get(afkChannel.id, viewState.afkChannel)?.id,
                afkTimeout = state.get(afkTimeoutWrap.id, guild.afkTimeout),
                systemChannelId = state.get(systemChannel.id, viewState.systemChannel)?.id,
                defaultMessageNotifications = state.get(
                    STATE_ID_NOTIFICATION_DEFAULT,
                    guild.defaultMessageNotifications,
                ),
                // Icon/banner/splash are echoed unchanged: this screen cannot upload them.
                icon = guild.icon,
                name = nameInput.getTextOrEmpty().trim(),
                verificationLevel = guild.verificationLevel,
                explicitContentFilter = guild.explicitContentFilter,
                splash = guild.splash,
                banner = guild.banner,
                systemChannelFlags = systemChannelFlags,
                features = guild.features.toList(),
                rulesChannelId = guild.rulesChannelId,
                publicUpdatesChannelId = guild.publicUpdatesChannelId,
                preferredLocale = guild.preferredLocale,
            ),
        )
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(WidgetServerSettingsOverview::class.java, context) {
                // The saved values become the new baseline, so the save FAB hides again.
                state.clear(clearDefaults = true)
                hideKeyboard()
                scroll.fullScroll(View.FOCUS_UP)
                AppToast.show(this, R.string.server_settings_updated)
            }
    }

    /**
     * Stand-in for `WidgetChannelSelector`; see the class KDoc.
     *
     * "None" is always offered because both settings are legitimately unset.
     */
    private fun pickChannel(
        title: Int,
        emptyLabel: Int,
        channels: List<Channel>,
        onPicked: (Channel?) -> Unit,
    ) {
        val options: List<Channel?> = listOf<Channel?>(null) + channels
        val labels: Array<CharSequence> = options
            .map { it?.name ?: getString(emptyLabel) }
            .toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setItems(labels) { _, which -> onPicked(options[which]) }
            .show()
    }

    /** The AFK timeout picker. Every option is a fixed value Discord accepts. */
    class AfkTimeoutBottomSheet : AppBottomSheet() {

        override fun getContentViewResId(): Int =
            R.layout.widget_server_settings_overview_afk_timeout

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            ROW_IDS.forEachIndexed { index, rowId ->
                val seconds = AFK_TIMEOUT_SECONDS[index]
                val row = view.findViewById<TextView>(rowId)
                row.text = afkTimeoutLabel(view.context, seconds)
                row.setOnClickListener {
                    // The host owns the pending value, so the sheet only reports the pick.
                    (parentFragment as? WidgetServerSettingsOverview)?.onAfkTimeoutPicked(seconds)
                    dismiss()
                }
            }
        }

        companion object {
            private val ROW_IDS = listOf(
                R.id.server_settings_overview_afk_timeout_01,
                R.id.server_settings_overview_afk_timeout_05,
                R.id.server_settings_overview_afk_timeout_15,
                R.id.server_settings_overview_afk_timeout_30,
                R.id.server_settings_overview_afk_timeout_60,
            )
        }
    }

    /**
     * Records a pick from [AfkTimeoutBottomSheet].
     *
     * The sheet reports back through the child fragment manager rather than a captured lambda, so a
     * sheet that survives a configuration change still reaches the recreated screen.
     */
    internal fun onAfkTimeoutPicked(seconds: Int) {
        state.put(afkTimeoutWrap.id, seconds)
        afkTimeout.text = afkTimeoutLabel(requireContext(), seconds)
        state.configureSaveActionView(save)
    }

    companion object {
        private const val INTENT_EXTRA_GUILD_ID = "INTENT_EXTRA_GUILD_ID"
        private const val INVALID_GUILD_ID = -1L

        /**
         * The default-notification radios are not a single view, so [StatefulViews] needs a key that
         * is not a view id. The reference uses this exact literal.
         */
        private const val STATE_ID_NOTIFICATION_DEFAULT = 90001

        private const val MINIMUM_ICON_SIZE_PX = "128"

        /** `systemChannelFlags` bits — each one *suppresses* a kind of system message. */
        private const val SYSTEM_CHANNEL_SUPPRESS_JOIN_NOTIFICATIONS = 1
        private const val SYSTEM_CHANNEL_SUPPRESS_PREMIUM_SUBSCRIPTIONS = 2
        private const val SYSTEM_CHANNEL_SUPPRESS_JOIN_NOTIFICATION_REPLIES = 8

        private const val NOTIFICATION_ALL_MESSAGES = 0
        private const val NOTIFICATION_ONLY_MENTIONS = 1

        /** The AFK timeouts Discord offers, in the row order the sheet's layout declares. */
        private val AFK_TIMEOUT_SECONDS = listOf(60, 300, 900, 1800, 3600)

        /**
         * Clearing the AFK or system channel means sending an explicit `null`, which only happens on
         * a Gson built with `serializeNulls`. The reference keeps a second REST client for it
         * (`RestAPI.apiSerializeNulls`); this project publishes one client whose Gson drops nulls, so
         * the PATCH would silently leave the old channel in place.
         *
         * `RestAPIBuilder.build(serializeNulls = true)` already supports it — the store graph only
         * has to publish the second client into this seam. The same stopgap exists at
         * `widgets/guildcommunicationdisabled/start/WidgetEnableGuildCommunication.serializeNullsApi`;
         * both should collapse into one `RestAPI.apiSerializeNulls`. Recorded in
         * `docs/parity-audit/followups.md`.
         */
        @JvmStatic
        var serializeNullsApi: RestAPIInterface? = null

        /** The label for an AFK timeout, or `""` for a value Discord does not offer. */
        internal fun afkTimeoutLabel(context: Context, seconds: Int): CharSequence = when (seconds) {
            60 -> context.i18nPlural(R.plurals.duration_minutes_minutes, 1, "minutes" to 1)
            300 -> context.i18nPlural(R.plurals.duration_minutes_minutes, 5, "minutes" to 5)
            900 -> context.i18nPlural(R.plurals.duration_minutes_minutes, 15, "minutes" to 15)
            1800 -> context.i18nPlural(R.plurals.duration_minutes_minutes, 30, "minutes" to 30)
            3600 -> context.i18nPlural(R.plurals.duration_hours_hours, 1, "hours" to 1)
            else -> ""
        }

        fun newInstance(guildId: Long) = WidgetServerSettingsOverview().apply {
            arguments = Bundle().apply { putLong(INTENT_EXTRA_GUILD_ID, guildId) }
        }

        fun launch(context: Context, guildId: Long) {
            SettingsNavigation.launch(
                context,
                WidgetServerSettingsOverview::class.java,
                Intent().putExtra(INTENT_EXTRA_GUILD_ID, guildId),
            )
        }
    }
}

/**
 * State for [WidgetServerSettingsOverview].
 *
 * Reference: `WidgetServerSettingsOverview.Model`, which resolves the AFK and system channels from
 * `StoreChannels` rather than trusting the ids on the guild, and reads the member count from
 * `StoreGuildProfiles` to decide whether "All messages" may be offered.
 */
class ServerSettingsOverviewViewModel(
    guildId: Long,
    storeStateObservable: Observable<StoreState> = observeStoreState(guildId),
) : AppViewModel<ServerSettingsOverviewViewModel.ViewState>(ViewState.Uninitialized) {

    data class StoreState(
        val guild: Guild?,
        val canManage: Boolean,
        val guildChannels: List<Channel>,
        val approximateMemberCount: Int?,
    )

    sealed class ViewState {
        object Uninitialized : ViewState()
        object Unauthorized : ViewState()

        data class Loaded(
            val guild: Guild,
            val afkChannel: Channel?,
            val systemChannel: Channel?,
            val voiceChannels: List<Channel>,
            val textChannels: List<Channel>,
            val isAboveNotifyAllSize: Boolean,
        ) : ViewState()
    }

    init {
        storeStateObservable.computationLatest().distinctUntilChanged().ui(this).subscribe {
            updateViewState(toViewState(it))
        }
    }

    companion object {
        /**
         * Above this many members Discord forces the default notification level to mentions-only.
         * Reference: `WidgetServerSettingsOverview.Model`'s `isAboveNotifyAllSize`.
         */
        internal const val NOTIFY_ALL_MEMBER_LIMIT = 2500

        internal fun toViewState(storeState: StoreState): ViewState {
            val guild = storeState.guild
            if (guild == null || !storeState.canManage) return ViewState.Unauthorized
            val byId = storeState.guildChannels.associateBy { it.id }
            return ViewState.Loaded(
                guild = guild,
                afkChannel = guild.afkChannelId?.let { byId[it] },
                systemChannel = guild.systemChannelId?.let { byId[it] },
                voiceChannels = storeState.guildChannels
                    .filter { it.isGuildVocalChannel() }
                    .sortedBy { it.position },
                textChannels = storeState.guildChannels
                    .filter { it.isGuildTextChannel() }
                    .sortedBy { it.position },
                // An unknown member count is treated as small, matching the reference's null branch.
                isAboveNotifyAllSize =
                    (storeState.approximateMemberCount ?: 0) > NOTIFY_ALL_MEMBER_LIMIT,
            )
        }

        fun observeStoreState(guildId: Long): Observable<StoreState> = Observable.combineLatest(
            StoreStream.getGuilds().observeGuild(guildId),
            StoreStream.getUsers().observeMe(),
            StoreStream.getPermissions().observePermissionsForGuild(guildId),
            StoreStream.getChannels().observeChannelsForGuild(guildId),
            StoreStream.getGuildProfiles().observeGuildProfile(guildId),
        ) { guild, meUser, guildPermissions, channels, guildProfile ->
            StoreState(
                guild = guild,
                canManage = guild != null && (
                    guild.isOwner(meUser.id) ||
                        PermissionUtils.canAndIsElevated(
                            Permission.MANAGE_GUILD,
                            guildPermissions,
                            meUser.mfaEnabled,
                            guild.mfaLevel,
                        )
                    ),
                guildChannels = channels.values.toList(),
                approximateMemberCount = guildProfile?.data?.approximateMemberCount,
            )
        }
    }
}

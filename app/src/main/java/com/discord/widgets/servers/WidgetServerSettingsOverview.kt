package com.discord.widgets.servers

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.permission.Permission
import com.discord.app.AppBottomSheet
import com.discord.app.AppFragment
import com.discord.app.AppToast
import com.discord.app.appViewModels
import com.discord.dialogs.ImageUploadDialog
import com.discord.i18n.i18nPlural
import com.discord.media_picker.MediaPicker
import com.discord.models.guild.Guild
import com.discord.models.user.MeUser
import com.discord.restapi.RestAPIInterface
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreGuildProfiles
import com.discord.stores.StoreStream
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.view.extensions.getTextOrEmpty
import com.discord.utilities.view.extensions.setText
import com.discord.views.CheckedSetting
import com.discord.views.RadioManager
import com.discord.widgets.channels.WidgetChannelSelector
import com.discord.widgets.settings.SettingsNavigation
import com.discord.widgets.settings.SettingsStrings
import com.discord.widgets.settings.StatefulViews
import com.facebook.drawee.view.SimpleDraweeView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputLayout

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
        R.id.server_settings_overview_icon,
        R.id.afk_channel,
        R.id.afk_timeout_wrap,
        R.id.system_channel,
        R.id.system_channel_join,
        R.id.system_channel_join_replies,
        R.id.system_channel_boost,
        R.id.upload_banner,
        R.id.upload_splash,
        STATE_ID_NOTIFICATION_DEFAULT,
    )

    private var hasOpenedAvatarPicker = false
    private var imageSelectedResult: ((String) -> Unit)? = null

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
        retainInstance = true

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
        systemChannelWrap = view.findViewById(R.id.systemChannel)
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
        notificationRadios = RadioManager(listOf(notificationAll, notificationOnlyMentions))

        save.hide()
        state.setupUnsavedChangesConfirmation(this)
        state.setupTextWatcherWithSaveAction(this, save, nameInput)
        // The two channel rows and the timeout row are set by a picker, not typed into, so they are
        // registered as optional: a guild legitimately has no AFK or system channel.
        state.addOptionalFields(afkChannel, systemChannel)
        WidgetChannelSelector.registerForResult(this, REQUEST_KEY_AFK_CHANNEL, allowNullChannel = true) { id, _ ->
            val channel = StoreStream.getChannels().getChannel(id)
            state.put(afkChannel.id, channel)
            configureAfkChannel(channel)
            state.configureSaveActionView(save)
        }
        WidgetChannelSelector.registerForResult(this, REQUEST_KEY_SYSTEM_CHANNEL, allowNullChannel = true) { id, _ ->
            val channel = StoreStream.getChannels().getChannel(id)
            state.put(systemChannel.id, channel)
            configureSystemChannel(channel)
            state.configureSaveActionView(save)
        }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState().ui(this).appSubscribe(
            WidgetServerSettingsOverview::class.java,
            onNext = ::configureUI,
        )
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
        configureSplash(guild)
        configureBanner(guild)
        nameInput.setText(state.get(nameInput.id, guild.name))

        configureAfkChannel(state.get(afkChannel.id, viewState.afkChannel))
        afkChannelWrap.setOnClickListener {
            WidgetChannelSelector.launchForVoice(
                this,
                guild.id,
                REQUEST_KEY_AFK_CHANNEL,
                includeNoChannel = true,
                noChannelStringId = R.string.no_afk_channel,
            )
        }

        val afkTimeoutSeconds = state.get(afkTimeoutWrap.id, guild.afkTimeout)
        afkTimeout.text = afkTimeoutLabel(requireContext(), afkTimeoutSeconds)
        afkTimeoutWrap.setOnClickListener {
            AfkBottomSheet()
                .show(childFragmentManager, AfkBottomSheet::class.java.name)
        }

        val currentSystemChannel = state.get(systemChannel.id, viewState.systemChannel)
        configureSystemChannel(currentSystemChannel)
        systemChannelWrap.setOnClickListener {
            WidgetChannelSelector.launchForText(
                this,
                guild.id,
                REQUEST_KEY_SYSTEM_CHANNEL,
                includeNoChannel = true,
                noChannelStringId = R.string.no_system_channel,
            )
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
        val original = IconUtils.getForGuild(guild, allowAnimation = true)
        val current = state.get(icon.id, original)
        if (!hasOpenedAvatarPicker && mostRecentIntent.getBooleanExtra(INTENT_EXTRA_OPEN_AVATAR_PICKER, false)) {
            openImagePicker { configureIcon(guild, it) }
        }
        icon.setOnClickListener { openImagePicker { configureIcon(guild, it) } }
        IconUtils.setIcon(icon, current, R.dimen.avatar_size_xxlarge)
        // The lettered fallback is a sibling of the image, not a placeholder inside it.
        iconText.visibility = if (current.isNullOrBlank()) View.VISIBLE else View.GONE
        iconText.text = guild.shortName
        iconRemove.visibility = if (current.isNullOrBlank()) View.GONE else View.VISIBLE
        iconRemove.setOnClickListener { configureIcon(guild, null) }
    }

    private fun configureIcon(guild: Guild, updated: String?) {
        state.put(icon.id, updated.orEmpty())
        configureIcon(guild)
        state.configureSaveActionView(save)
    }

    private fun configureSplash(guild: Guild) {
        val image = requireView().findViewById<SimpleDraweeView>(R.id.upload_splash)
        val container = requireView().findViewById<View>(R.id.server_settings_overview_upload_splash_container)
        val remove = requireView().findViewById<TextView>(R.id.server_settings_overview_upload_splash_remove)
        val unlock = requireView().findViewById<TextView>(R.id.upload_splash_unlock)
        val nitroTier = requireView().findViewById<TextView>(R.id.upload_splash_nitro_tier)
        val current = state.get(image.id, IconUtils.getGuildSplashUrl(guild, resources.displayMetrics.widthPixels))
        IconUtils.setIcon(image, current)
        val enabled = guild.canHaveSplash()
        container.isEnabled = enabled
        container.setOnClickListener(if (enabled) View.OnClickListener {
            openImagePicker { value ->
                state.put(image.id, value)
                configureSplash(guild)
                state.configureSaveActionView(save)
            }
        } else null)
        remove.visibility = if (enabled && !current.isNullOrBlank()) View.VISIBLE else View.GONE
        remove.setOnClickListener {
            state.put(image.id, "")
            configureSplash(guild)
            state.configureSaveActionView(save)
        }
        unlock.text = SettingsStrings.render(
            requireContext(),
            if (enabled) R.string.guild_settings_overview_boost_unlocked else R.string.guild_settings_overview_tier_info,
            "tier" to 1,
            "tierStatusUrl" to "",
        )
        unlock.visibility = if (current.isNullOrBlank()) View.VISIBLE else View.GONE
        image.alpha = if (enabled) 1f else 0.3f
        nitroTier.visibility = if (enabled) View.GONE else View.VISIBLE
        nitroTier.text = SettingsStrings.render(
            requireContext(),
            R.string.guild_settings_overview_tier_info,
            "tier" to 1,
            "tierStatusUrl" to "",
        )
    }

    private fun configureBanner(guild: Guild) {
        val image = requireView().findViewById<SimpleDraweeView>(R.id.upload_banner)
        val container = requireView().findViewById<View>(R.id.server_settings_overview_upload_banner_container)
        val remove = requireView().findViewById<TextView>(R.id.server_settings_overview_upload_banner_remove)
        val unlock = requireView().findViewById<TextView>(R.id.upload_banner_unlock)
        val nitroTier = requireView().findViewById<TextView>(R.id.upload_banner_nitro_tier)
        val current = state.get(
            image.id,
            IconUtils.getBannerForGuild(guild, resources.displayMetrics.widthPixels, false),
        )
        IconUtils.setIcon(image, current)
        val enabled = guild.canHaveBanner()
        container.isEnabled = enabled
        container.setOnClickListener(if (enabled) View.OnClickListener {
            openImagePicker { value ->
                state.put(image.id, value)
                configureBanner(guild)
                state.configureSaveActionView(save)
            }
        } else null)
        remove.visibility = if (enabled && !current.isNullOrBlank()) View.VISIBLE else View.GONE
        remove.setOnClickListener {
            state.put(image.id, "")
            configureBanner(guild)
            state.configureSaveActionView(save)
        }
        unlock.text = SettingsStrings.render(
            requireContext(),
            if (enabled) R.string.guild_settings_overview_boost_unlocked else R.string.guild_settings_overview_tier_info,
            "tier" to 2,
            "tierStatusUrl" to "",
        )
        unlock.visibility = if (current.isNullOrBlank()) View.VISIBLE else View.GONE
        image.alpha = if (enabled) 1f else 0.3f
        nitroTier.visibility = if (enabled) View.GONE else View.VISIBLE
        nitroTier.text = SettingsStrings.render(
            requireContext(),
            R.string.guild_settings_overview_tier_info,
            "tier" to 2,
            "tierStatusUrl" to "",
        )

        val animatedUpsell = requireView().findViewById<View>(R.id.animated_banner_upsell)
        animatedUpsell.visibility =
            if (guild.canHaveAnimatedBanner()) View.VISIBLE else View.GONE
        requireView().findViewById<TextView>(R.id.animated_banner_upsell_text).setText(
            if (guild.canHaveAnimatedBanner()) {
                R.string.guild_settings_animated_banner_try_it_mobile
            } else R.string.guild_settings_animated_banner_upsell,
        )
        requireView().findViewById<View>(R.id.animated_banner_upsell_button).visibility = View.GONE
    }

    private fun openImagePicker(onSelected: (String) -> Unit) {
        hasOpenedAvatarPicker = true
        imageSelectedResult = onSelected
        openMediaChooser()
    }

    override fun onImageChosen(uri: Uri, mimeType: String) {
        super.onImageChosen(uri, mimeType)
        ImageUploadDialog.show(
            parentFragmentManager,
            uri,
            mediaPickerProvider(),
            mimeType,
            imageSelectedResult,
            ImageUploadDialog.PreviewType.GUILD_AVATAR,
        )
    }

    override fun onImageCropped(uri: Uri, mimeType: String) {
        super.onImageCropped(uri, mimeType)
        MGImages.requestDataUrl(context, uri, mimeType, imageSelectedResult)
    }

    private fun mediaPickerProvider() = object : MediaPicker.Provider {
        override fun requireContext() = this@WidgetServerSettingsOverview.requireContext()
        override fun getImageFile() = imageFile
        @Suppress("DEPRECATION")
        override fun startActivityForResult(intent: Intent, requestCode: Int) =
            this@WidgetServerSettingsOverview.startActivityForResult(intent, requestCode)
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
                toggle.alpha = 1f
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
                radio.alpha = 1f
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
                icon = state.get(icon.id, IconUtils.getForGuild(guild, allowAnimation = false)),
                name = nameInput.getTextOrEmpty().trim(),
                verificationLevel = guild.verificationLevel,
                explicitContentFilter = guild.explicitContentFilter,
                splash = state.get(
                    R.id.upload_splash,
                    guild.splash,
                ),
                banner = state.get(
                    R.id.upload_banner,
                    guild.banner,
                ),
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

    /** The AFK timeout picker. Every option is a fixed value Discord accepts. */
    internal class AfkBottomSheet : AppBottomSheet() {

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
     * Records a pick from [AfkBottomSheet].
     *
     * The sheet reports back through the child fragment manager rather than a captured lambda, so a
     * sheet that survives a configuration change still reaches the recreated screen.
     */
    internal fun onAfkTimeoutPicked(seconds: Int) {
        state.put(afkTimeoutWrap.id, seconds)
        afkTimeout.text = afkTimeoutLabel(requireContext(), seconds)
        state.configureSaveActionView(save)
    }

    /** Exact 126.21 state shape retained for the nested JVM contract. */
    internal class Model(
        val guild: Guild,
        me: MeUser,
        val afkChannelModel: Channel?,
        val systemChannelModel: Channel?,
        permissions: Long?,
        guildProfile: StoreGuildProfiles.GuildProfileData?,
    ) {
        val isOwner = guild.isOwner(me.id)
        val canManage = isOwner || PermissionUtils.canAndIsElevated(
            Permission.MANAGE_GUILD,
            permissions,
            me.mfaEnabled,
            guild.mfaLevel,
        )
        private val approximateMemberCount = guildProfile?.data?.approximateMemberCount
        val isAboveNotifyAllSize =
            approximateMemberCount != null &&
                approximateMemberCount > ServerSettingsOverviewViewModel.NOTIFY_ALL_MEMBER_LIMIT

        companion object
    }

    companion object {
        private const val INTENT_EXTRA_GUILD_ID = "INTENT_EXTRA_GUILD_ID"
        private const val INTENT_EXTRA_OPEN_AVATAR_PICKER = "INTENT_EXTRA_OPEN_AVATAR_PICKER"
        private const val INVALID_GUILD_ID = -1L
        private const val REQUEST_KEY_AFK_CHANNEL = "REQUEST_KEY_AFK_CHANNEL"
        private const val REQUEST_KEY_SYSTEM_CHANNEL = "REQUEST_KEY_SYSTEM_CHANNEL"

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

        @JvmOverloads
        fun launch(context: Context, guildId: Long, openAvatarPicker: Boolean = false) {
            StoreStream.getAnalytics().onGuildSettingsPaneViewed("OVERVIEW", guildId)
            SettingsNavigation.launch(
                context,
                WidgetServerSettingsOverview::class.java,
                Intent()
                    .putExtra(INTENT_EXTRA_GUILD_ID, guildId)
                    .putExtra(INTENT_EXTRA_OPEN_AVATAR_PICKER, openAvatarPicker),
            )
        }
    }
}

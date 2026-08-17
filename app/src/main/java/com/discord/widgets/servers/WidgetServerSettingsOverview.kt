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
import com.discord.databinding.WidgetServerSettingsOverviewBinding
import com.discord.dialogs.ImageUploadDialog
import com.discord.i18n.i18nPlural
import com.discord.media_picker.MediaPicker
import com.discord.models.guild.Guild
import com.discord.models.user.MeUser
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
import com.discord.views.VitoImageView
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
 *   a checked "send join messages" switch means the bit is **clear**.
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
        R.id.system_channel_guild_reminders,
        R.id.system_channel_role_subscription_purchase,
        R.id.system_channel_role_subscription_purchase_replies,
        R.id.system_channel_voice_hangout,
        R.id.premium_progress_bar,
        R.id.overview_description,
        R.id.upload_banner,
        R.id.upload_splash,
        STATE_ID_NOTIFICATION_DEFAULT,
    )

    private var hasOpenedAvatarPicker = false
    private var imageSelectedResult: ((String) -> Unit)? = null
    private var _binding: WidgetServerSettingsOverviewBinding? = null
    private val binding get() = requireNotNull(_binding)

    private lateinit var scroll: ScrollView
    private lateinit var icon: VitoImageView
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
    private lateinit var systemChannelRoleSubscriptionPurchase: CheckedSetting
    private lateinit var systemChannelRoleSubscriptionPurchaseReplies: CheckedSetting
    private lateinit var premiumProgressBar: CheckedSetting
    private lateinit var descriptionInput: TextInputLayout
    private lateinit var notificationAll: CheckedSetting
    private lateinit var notificationOnlyMentions: CheckedSetting
    private lateinit var notificationRadios: RadioManager
    private lateinit var save: FloatingActionButton

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        _binding = WidgetServerSettingsOverviewBinding.bind(view)
        setActionBarDisplayHomeAsUpEnabled()
        setActionBarTitle(R.string.overview)
        retainInstance = true

        scroll = binding.serverSettingsOverviewScroll
        icon = binding.header.serverSettingsOverviewIcon
        iconText = binding.header.serverSettingsOverviewIconText
        iconLabel = binding.header.iconLabel
        iconRemove = binding.header.serverSettingsOverviewIconRemove
        nameInput = binding.header.overviewName
        afkChannel = binding.afk.afkChannel
        afkChannelWrap = binding.afk.afkChannelWrap
        afkTimeout = binding.afk.afkTimeout
        afkTimeoutWrap = binding.afk.afkTimeoutWrap
        systemChannel = binding.systemChannel.systemChannel
        systemChannelWrap = binding.systemChannel.systemChannelWrap
        systemChannelRoleSubscriptionPurchase = binding.systemChannel.systemChannelRoleSubscriptionPurchase
        systemChannelRoleSubscriptionPurchaseReplies =
            binding.systemChannel.systemChannelRoleSubscriptionPurchaseReplies
        premiumProgressBar = binding.premiumProgressBar.premiumProgressBar
        descriptionInput = binding.description.overviewDescription
        notificationAll = binding.notifications.serverSettingsOverviewNotificationAll
        notificationOnlyMentions = binding.notifications.serverSettingsOverviewNotificationOnlyMentions
        save = binding.save

        iconLabel.text = SettingsStrings.render(
            view.context,
            R.string.minimum_size,
            "size" to MINIMUM_ICON_SIZE_PX,
        )
        notificationRadios = RadioManager(listOf(notificationAll, notificationOnlyMentions))

        save.hide()
        state.setupUnsavedChangesConfirmation(this)
        state.setupTextWatcherWithSaveAction(this, save, nameInput, descriptionInput)
        // The two channel rows and the timeout row are set by a picker, not typed into, so they are
        // registered as optional: a guild legitimately has no AFK or system channel.
        state.addOptionalFields(afkChannel, systemChannel, descriptionInput)
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
        descriptionInput.setText(state.get(descriptionInput.id, guild.description.orEmpty()))
        configurePremiumProgressBar(guild)

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
        configureSystemChannelToggles(guild, currentSystemChannel != null, viewState.isCreatorMonetizable)

        configureNotificationRadios(
            selected = state.get(STATE_ID_NOTIFICATION_DEFAULT, guild.defaultMessageNotifications),
            isAboveNotifyAllSize = viewState.isAboveNotifyAllSize,
        )

        state.configureSaveActionView(save)
        save.setOnClickListener { saveChanges(viewState) }
    }

    private fun configureIcon(guild: Guild) {
        val original = IconUtils.getForGuild(guild, allowAnimation = true, size = ICON_SIZE_DP)
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

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun configureIcon(guild: Guild, updated: String?) {
        state.put(icon.id, updated.orEmpty())
        configureIcon(guild)
        state.configureSaveActionView(save)
    }

    /** iOS drops the whole section on an ineligible guild rather than showing a locked tile. */
    private fun configureSplash(guild: Guild) {
        val section = binding.uploadSplash.root
        if (!guild.canHaveSplash()) {
            section.visibility = View.GONE
            return
        }
        section.visibility = View.VISIBLE
        val image = requireView().findViewById<VitoImageView>(R.id.upload_splash)
        val container = requireView().findViewById<View>(R.id.server_settings_overview_upload_splash_container)
        val remove = requireView().findViewById<TextView>(R.id.server_settings_overview_upload_splash_remove)
        val current = state.get(image.id, IconUtils.getGuildSplashUrl(guild, resources.displayMetrics.widthPixels))
        IconUtils.setIcon(image, current)
        container.setOnClickListener {
            openImagePicker { value ->
                state.put(image.id, value)
                configureSplash(guild)
                state.configureSaveActionView(save)
            }
        }
        remove.visibility = if (current.isNullOrBlank()) View.GONE else View.VISIBLE
        remove.setOnClickListener {
            state.put(image.id, "")
            configureSplash(guild)
            state.configureSaveActionView(save)
        }
        requireView().findViewById<View>(R.id.upload_splash_unlock).visibility = View.GONE
        requireView().findViewById<View>(R.id.upload_splash_nitro_tier).visibility = View.GONE
    }

    private fun configureBanner(guild: Guild) {
        val section = binding.uploadBanner.root
        if (!guild.canHaveBanner()) {
            section.visibility = View.GONE
            return
        }
        section.visibility = View.VISIBLE
        val image = requireView().findViewById<VitoImageView>(R.id.upload_banner)
        val container = requireView().findViewById<View>(R.id.server_settings_overview_upload_banner_container)
        val remove = requireView().findViewById<TextView>(R.id.server_settings_overview_upload_banner_remove)
        val current = state.get(
            image.id,
            IconUtils.getBannerForGuild(guild, resources.displayMetrics.widthPixels, false),
        )
        IconUtils.setIcon(image, current)
        container.setOnClickListener {
            openImagePicker { value ->
                state.put(image.id, value)
                configureBanner(guild)
                state.configureSaveActionView(save)
            }
        }
        remove.visibility = if (current.isNullOrBlank()) View.GONE else View.VISIBLE
        remove.setOnClickListener {
            state.put(image.id, "")
            configureBanner(guild)
            state.configureSaveActionView(save)
        }
        requireView().findViewById<View>(R.id.upload_banner_unlock).visibility = View.GONE
        requireView().findViewById<View>(R.id.upload_banner_nitro_tier).visibility = View.GONE
        requireView().findViewById<View>(R.id.animated_banner_upsell).visibility = View.GONE
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
     * Binds the system-channel switches.
     *
     * `systemChannelFlags` suppresses messages, so each switch shows the **inverse** of its bit.
     * With no system channel there is nowhere to send them, and the reference disables all of them.
     * The two role-subscription rows only appear on a monetizable guild, or on one that already has
     * the bit set.
     */
    private fun configureSystemChannelToggles(
        guild: Guild,
        hasSystemChannel: Boolean,
        isCreatorMonetizable: Boolean,
    ) {
        fun show(toggle: CheckedSetting, flag: Int) {
            toggle.visibility =
                if (isCreatorMonetizable || (guild.systemChannelFlags and flag) != 0) View.VISIBLE
                else View.GONE
        }
        show(systemChannelRoleSubscriptionPurchase, SYSTEM_CHANNEL_SUPPRESS_ROLE_SUBSCRIPTION_PURCHASE)
        show(
            systemChannelRoleSubscriptionPurchaseReplies,
            SYSTEM_CHANNEL_SUPPRESS_ROLE_SUBSCRIPTION_PURCHASE_REPLIES,
        )

        SYSTEM_CHANNEL_TOGGLES.forEach { (viewId, flag) ->
            val toggle = requireView().findViewById<CheckedSetting>(viewId)
            val enabledOnServer = (guild.systemChannelFlags and flag) == 0
            toggle.setChecked(state.get(viewId, enabledOnServer), false)
            if (hasSystemChannel) {
                toggle.alpha = 1f
                toggle.setContainerClickListener {
                    toggle.toggle()
                    state.put(viewId, toggle.isChecked())
                    state.configureSaveActionView(save)
                }
            } else {
                toggle.setContainerClickListener(null)
                toggle.setDisabledTooltip(null)
            }
        }
    }

    private fun configurePremiumProgressBar(guild: Guild) {
        premiumProgressBar.setChecked(
            state.get(premiumProgressBar.id, guild.premiumProgressBarEnabled),
            false,
        )
        premiumProgressBar.setContainerClickListener {
            premiumProgressBar.toggle()
            state.put(premiumProgressBar.id, premiumProgressBar.isChecked())
            state.configureSaveActionView(save)
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
        val api = RestAPI.apiSerializeNulls

        // Only the bits this screen renders are rewritten; anything else the server set stays as it
        // was, which a rebuild-from-zero would silently clear.
        var systemChannelFlags = guild.systemChannelFlags
        SYSTEM_CHANNEL_TOGGLES.forEach { (viewId, flag) ->
            val toggle = requireView().findViewById<CheckedSetting>(viewId)
            systemChannelFlags = if (state.get(viewId, toggle.isChecked())) {
                systemChannelFlags and flag.inv()
            } else {
                systemChannelFlags or flag
            }
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
                icon = state.get(icon.id, IconUtils.getForGuild(guild, allowAnimation = false, size = ICON_SIZE_DP)),
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
                description = descriptionInput.getTextOrEmpty().trim(),
                premiumProgressBarEnabled = state.get(
                    premiumProgressBar.id,
                    guild.premiumProgressBarEnabled,
                ),
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
        /** Matches `avatar_size_xxlarge`, which is what the editor draws it at. */
        private const val ICON_SIZE_DP = 80

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
        private const val SYSTEM_CHANNEL_SUPPRESS_GUILD_REMINDERS = 4
        private const val SYSTEM_CHANNEL_SUPPRESS_JOIN_NOTIFICATION_REPLIES = 8
        private const val SYSTEM_CHANNEL_SUPPRESS_ROLE_SUBSCRIPTION_PURCHASE = 16
        private const val SYSTEM_CHANNEL_SUPPRESS_ROLE_SUBSCRIPTION_PURCHASE_REPLIES = 32
        private const val SYSTEM_CHANNEL_SUPPRESS_VOICE_SESSION = 512

        private val SYSTEM_CHANNEL_TOGGLES = listOf(
            R.id.system_channel_join to SYSTEM_CHANNEL_SUPPRESS_JOIN_NOTIFICATIONS,
            R.id.system_channel_join_replies to SYSTEM_CHANNEL_SUPPRESS_JOIN_NOTIFICATION_REPLIES,
            R.id.system_channel_boost to SYSTEM_CHANNEL_SUPPRESS_PREMIUM_SUBSCRIPTIONS,
            R.id.system_channel_guild_reminders to SYSTEM_CHANNEL_SUPPRESS_GUILD_REMINDERS,
            R.id.system_channel_role_subscription_purchase to
                SYSTEM_CHANNEL_SUPPRESS_ROLE_SUBSCRIPTION_PURCHASE,
            R.id.system_channel_role_subscription_purchase_replies to
                SYSTEM_CHANNEL_SUPPRESS_ROLE_SUBSCRIPTION_PURCHASE_REPLIES,
            R.id.system_channel_voice_hangout to SYSTEM_CHANNEL_SUPPRESS_VOICE_SESSION,
        )

        private const val NOTIFICATION_ALL_MESSAGES = 0
        private const val NOTIFICATION_ONLY_MENTIONS = 1

        /** The AFK timeouts Discord offers, in the row order the sheet's layout declares. */
        private val AFK_TIMEOUT_SECONDS = listOf(60, 300, 900, 1800, 3600)

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

package com.discord.widgets.servers

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.appViewModels
import com.discord.databinding.WidgetServerNotificationsBinding
import com.discord.models.domain.ModelNotificationSettings
import com.discord.utilities.rx.ui
import com.discord.views.CheckedSetting
import androidx.core.view.isVisible
import com.discord.api.notification.UnreadSetting
import com.discord.stores.StoreStream
import com.discord.views.RadioManager
import com.discord.widgets.channels.settings.WidgetChannelNotificationSettings
import com.discord.widgets.settings.SettingsNavigation
import com.discord.widgets.settings.SettingsStrings
import com.discord.widgets.settings.WidgetMuteSettingsSheet
import kotlin.math.abs
import kotlin.math.min
import rx.functions.Action1

/**
 * Per-server notification settings: mute, message frequency, `@everyone`/role suppression, mobile
 * push, highlights, and the list of per-channel overrides.
 *
 * Reference: `com.discord.widgets.servers.WidgetServerNotifications`. State comes from
 * [WidgetServerNotificationsViewModel]; every write goes straight to `StoreUserGuildSettings`,
 * which applies it optimistically and reverts on failure — the screen holds no pending state of its
 * own.
 *
 * Muting opens [WidgetMuteSettingsSheet] for a duration; unmuting is immediate. Swiping an override
 * row away clears that channel's override.
 */
class WidgetServerNotifications : AppFragment(R.layout.widget_server_notifications) {

    private var _binding: WidgetServerNotificationsBinding? = null
    private val binding get() = checkNotNull(_binding)

    private val guildId: Long
        get() = arguments?.getLong(EXTRA_GUILD_ID, INVALID_GUILD_ID)
            ?.takeIf { it != INVALID_GUILD_ID }
            ?: mostRecentIntent.getLongExtra(EXTRA_GUILD_ID, INVALID_GUILD_ID)

    private val viewModel: WidgetServerNotificationsViewModel by appViewModels {
        WidgetServerNotificationsViewModel(guildId)
    }

    private var frequencyRadios: List<CheckedSetting> = emptyList()
    private var radioManager: RadioManager? = null
    private var unreadRadioManager: RadioManager? = null
    private var presetRadioManager: RadioManager? = null

    /** Set once the viewer opens Custom, so the detail sections stay put while they use them. */
    private var customExpanded = false
    private var overrideAdapter: NotificationsOverridesAdapter? = null

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        _binding = WidgetServerNotificationsBinding.bind(view)
        frequencyRadios = listOf(
            binding.serverNotificationsFrequency0Radio,
            binding.serverNotificationsFrequency1Radio,
            binding.serverNotificationsFrequency2Radio,
        )
        radioManager = RadioManager(frequencyRadios)
        presetRadioManager = RadioManager(
            listOf(
                binding.serverNotificationsPresetAllRadio,
                binding.serverNotificationsPresetMentionsRadio,
                binding.serverNotificationsPresetNothingRadio,
                binding.serverNotificationsPresetCustomRadio,
            ),
        )
        unreadRadioManager = RadioManager(
            listOf(
                binding.serverNotificationsUnreadAllRadio,
                binding.serverNotificationsUnreadMentionsRadio,
            ),
        )

        overrideAdapter = NotificationsOverridesAdapter { itemView, item ->
            SettingsNavigation.launch(
                itemView.context,
                WidgetChannelNotificationSettings.newInstance(item.channel.id),
            )
        }
        // The list lives inside the screen's NestedScrollView, so it must never scroll itself.
        binding.guildNotificationsOverrideList.isNestedScrollingEnabled = false
        binding.guildNotificationsOverrideList.layoutManager = LinearLayoutManager(view.context)
        binding.guildNotificationsOverrideList.adapter = overrideAdapter
        createSwipeableItemTouchHelper().attachToRecyclerView(binding.guildNotificationsOverrideList)

        binding.addOverride.root.setOnClickListener {
            WidgetServerNotificationsOverrideSelector.launch(it.context, guildId)
        }
    }

    override fun onDestroyView() {
        binding.guildNotificationsOverrideList.adapter = null
        overrideAdapter = null
        radioManager = null
        frequencyRadios = emptyList()
        _binding = null
        super.onDestroyView()
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState().ui(this).subscribe { configureUI(it) }
    }

    private fun configureUI(viewState: WidgetServerNotificationsViewModel.ViewState) {
        if (_binding == null) return
        when (viewState) {
            is WidgetServerNotificationsViewModel.ViewState.Uninitialized -> Unit
            is WidgetServerNotificationsViewModel.ViewState.Invalid -> appActivity?.onBackPressed()
            is WidgetServerNotificationsViewModel.ViewState.Loaded -> configureLoaded(viewState)
        }
    }

    private fun configureLoaded(model: WidgetServerNotificationsViewModel.ViewState.Loaded) {
        setActionBarDisplayHomeAsUpEnabled()
        setActionBarTitle(R.string.notification_settings)
        setActionBarSubtitle(model.guild.name)

        configureMuteSettings(model)

        val visibleWhileUnmuted = if (model.isMuted) View.GONE else View.VISIBLE
        binding.serverNotificationsFrequencyDivider.visibility = visibleWhileUnmuted
        binding.serverNotificationsFrequencyWrap.visibility = visibleWhileUnmuted

        if (model.isHub) {
            configureForHub()
            return
        }

        configureHighlights(model)
        configureToggles(model)
        configureRadios(model)
        configureShowAllChannels(model)
        configurePresetRadios(model)
        configureUnreadRadios(model)
        configureDetailVisibility(model)
        configureOverrides(model)
    }

    private fun configureMuteSettings(model: WidgetServerNotificationsViewModel.ViewState.Loaded) {
        val context = requireContext()
        binding.serverNotificationsMuteSettingsView.updateView(
            NotificationMuteSettingsView.ViewState(
                isMuted = model.isMuted,
                muteEndTime = model.guildSettings.muteEndTime,
                rawMuteText = SettingsStrings.render(
                    context,
                    R.string.form_label_mute_server,
                    "name" to model.guild.name,
                ),
                rawUnmuteText = SettingsStrings.render(
                    context,
                    R.string.form_label_unmute_server,
                    "name" to model.guild.name,
                ),
                rawMutedStatusText = getString(R.string.form_label_mobile_server_muted),
                rawMutedUntilStatusResId = R.string.form_label_mobile_server_muted_until,
                muteDescriptionText = getString(R.string.form_label_mute_server_description),
            ),
            onMute = { WidgetMuteSettingsSheet.showForGuild(guildId, parentFragmentManager) },
            onUnmute = { viewModel.unmute(context.applicationContext) },
        )
    }

    private fun configureHighlights(model: WidgetServerNotificationsViewModel.ViewState.Loaded) {
        val visibility = if (model.highlightsEnabled) View.VISIBLE else View.GONE
        binding.serverNotificationsHighlights.visibility = visibility
        binding.serverNotificationsHighlightsDivider.visibility = visibility
        if (!model.highlightsEnabled) return
        binding.serverNotificationsHighlights.updateView(
            NotificationHighlightsSettingsView.ViewState(
                isMuted = model.isMuted,
                notifyHighlights = model.guildSettings.notifyHighlights,
                serverTooLarge = model.isAboveHighlightsSize,
            ),
        ) { notifyHighlights ->
            viewModel.setNotifyHighlights(
                binding.serverNotificationsHighlights.context.applicationContext,
                notifyHighlights,
            )
        }
    }

    private fun configureToggles(model: WidgetServerNotificationsViewModel.ViewState.Loaded) {
        val everyoneSwitch = binding.serverNotificationsEveryoneSwitch
        val rolesSwitch = binding.serverNotificationsRolesSwitch
        val pushSwitch = binding.serverNotificationsPushSwitch
        everyoneSwitch.setText(
            SettingsStrings.render(everyoneSwitch.context, R.string.form_label_suppress_everyone),
        )
        everyoneSwitch.setOnCheckedListener(null)
        everyoneSwitch.setChecked(model.guildSettings.isSuppressEveryone, false)
        everyoneSwitch.setOnCheckedListener(
            Action1 { viewModel.toggleSuppressEveryone(everyoneSwitch.context.applicationContext) },
        )

        rolesSwitch.setText(
            SettingsStrings.render(rolesSwitch.context, R.string.form_label_suppress_roles),
        )
        rolesSwitch.setOnCheckedListener(null)
        rolesSwitch.setChecked(model.guildSettings.isSuppressRoles, false)
        rolesSwitch.setOnCheckedListener(
            Action1 { viewModel.toggleSuppressRoles(rolesSwitch.context.applicationContext) },
        )

        val muteEventsSwitch = binding.serverNotificationsMuteEventsSwitch
        muteEventsSwitch.setOnCheckedListener(null)
        muteEventsSwitch.setChecked(model.guildSettings.isMuteScheduledEvents, false)
        muteEventsSwitch.setOnCheckedListener(
            Action1 { viewModel.toggleMuteScheduledEvents(muteEventsSwitch.context.applicationContext) },
        )

        pushSwitch.setChecked(model.guildSettings.isMobilePush && !model.isMuted, false)
        if (model.isMuted) {
            // Muted means no push regardless, so the row is greyed out and inert. The reference
            // passes no message either, so the tap is silent rather than explanatory.
            pushSwitch.setDisabledTooltip(null)
        } else {
            pushSwitch.setContainerClickListener {
                pushSwitch.toggle()
                viewModel.toggleMobilePush(pushSwitch.context.applicationContext)
            }
        }
    }

    private fun configureRadios(model: WidgetServerNotificationsViewModel.ViewState.Loaded) {
        frequencyRadios[1].setText(
            SettingsStrings.render(frequencyRadios[1].context, R.string.form_label_only_mentions),
        )
        configureRadio(frequencyRadios[0], ModelNotificationSettings.FREQUENCY_ALL, model)
        configureRadio(frequencyRadios[1], ModelNotificationSettings.FREQUENCY_MENTIONS, model)
        configureRadio(frequencyRadios[2], ModelNotificationSettings.FREQUENCY_NOTHING, model)
    }

    private fun configureRadio(
        radio: CheckedSetting,
        frequency: Int,
        model: WidgetServerNotificationsViewModel.ViewState.Loaded,
    ) {
        if (model.notificationsSetting == frequency) radioManager?.setChecked(radio)
        // A large server does not push non-@mention messages at all, so "All messages" says so
        // rather than quietly not working. The row stays selectable.
        radio.setSubtext(
            if (model.isAboveNotifyAllSize && frequency == ModelNotificationSettings.FREQUENCY_ALL) {
                getString(R.string.large_guild_notify_all_messages_description)
            } else {
                null
            },
            false,
        )
        radio.setContainerClickListener { view ->
            viewModel.setFrequency(view.context.applicationContext, frequency)
        }
    }

    private fun configureShowAllChannels(model: WidgetServerNotificationsViewModel.ViewState.Loaded) {
        val switch = binding.serverNotificationsShowAllChannelsSwitch
        switch.isVisible = model.supportsChannelOptIn
        if (!model.supportsChannelOptIn) return
        switch.setOnCheckedListener(null)
        switch.setChecked(model.showAllChannels, false)
        switch.setOnCheckedListener(
            Action1<Boolean> { showAll ->
                StoreStream.getUserGuildSettings().setGuildOptInChannels(
                    switch.context.applicationContext,
                    viewModel.guildId,
                    optInOnly = !showAll,
                )
            },
        )
    }

    private fun configurePresetRadios(model: WidgetServerNotificationsViewModel.ViewState.Loaded) {
        binding.serverNotificationsPresetsWrap.isVisible = model.useNewNotifications
        if (!model.useNewNotifications) return
        configurePresetRadio(binding.serverNotificationsPresetAllRadio, model, Preset.ALL_MESSAGES)
        configurePresetRadio(binding.serverNotificationsPresetMentionsRadio, model, Preset.MENTIONS)
        configurePresetRadio(binding.serverNotificationsPresetNothingRadio, model, Preset.NOTHING)
        binding.serverNotificationsPresetMentionsRadio.setText(
            SettingsStrings.render(requireContext(), R.string.form_label_only_mentions),
        )
        binding.serverNotificationsPresetCustomRadio.apply {
            setContainerClickListener {
                customExpanded = true
                presetRadioManager?.setChecked(this)
                configureDetailVisibility(model)
            }
            if (isCustom(model)) presetRadioManager?.setChecked(this)
        }
    }

    /**
     * The notification and unread sections are the contents of Custom. Without
     * `USE_NEW_NOTIFICATIONS` there are no presets, so the notification section stays put.
     */
    private fun configureDetailVisibility(model: WidgetServerNotificationsViewModel.ViewState.Loaded) {
        val expanded = !model.useNewNotifications || isCustom(model)
        binding.serverNotificationsFrequencyWrap.isVisible = expanded
        binding.serverNotificationsUnreadWrap.isVisible = model.useNewNotifications && isCustom(model)
    }

    private fun isCustom(model: WidgetServerNotificationsViewModel.ViewState.Loaded) =
        presetOf(model) == null || customExpanded

    private fun configurePresetRadio(
        radio: CheckedSetting,
        model: WidgetServerNotificationsViewModel.ViewState.Loaded,
        preset: Preset,
    ) {
        radio.setContainerClickListener { view ->
            customExpanded = false
            StoreStream.getUserGuildSettings().setGuildPreset(
                view.context.applicationContext,
                viewModel.guildId,
                preset.notifications,
                preset.unread,
            )
        }
        if (presetOf(model) == preset) presetRadioManager?.setChecked(radio)
    }

    private fun presetOf(model: WidgetServerNotificationsViewModel.ViewState.Loaded): Preset? =
        Preset.entries.firstOrNull {
            it.notifications == model.notificationsSetting && it.unread == model.unreadSetting
        }

    private enum class Preset(val notifications: Int, val unread: UnreadSetting) {
        ALL_MESSAGES(ModelNotificationSettings.FREQUENCY_ALL, UnreadSetting.ALL_MESSAGES),
        MENTIONS(ModelNotificationSettings.FREQUENCY_MENTIONS, UnreadSetting.ONLY_MENTIONS),
        NOTHING(ModelNotificationSettings.FREQUENCY_NOTHING, UnreadSetting.ONLY_MENTIONS),
    }

    private fun configureUnreadRadios(model: WidgetServerNotificationsViewModel.ViewState.Loaded) {
        binding.serverNotificationsUnreadWrap.isVisible = model.useNewNotifications
        if (!model.useNewNotifications) return
        configureUnreadRadio(binding.serverNotificationsUnreadAllRadio, UnreadSetting.ALL_MESSAGES, model, false)
        configureUnreadRadio(
            binding.serverNotificationsUnreadMentionsRadio,
            UnreadSetting.ONLY_MENTIONS,
            model,
            model.notificationsSetting == ModelNotificationSettings.FREQUENCY_ALL,
        )
        binding.serverNotificationsUnreadMentionsRadio.setText(
            SettingsStrings.render(requireContext(), R.string.form_label_only_mentions),
        )
    }

    private fun configureUnreadRadio(
        radio: CheckedSetting,
        setting: UnreadSetting,
        model: WidgetServerNotificationsViewModel.ViewState.Loaded,
        disabled: Boolean,
    ) {
        radio.setContainerClickListener(null)
        radio.setDisabledTooltip(null)
        if (disabled) {
            radio.setDisabledTooltip(R.string.notification_settings_unread_mention_only_disabled)
        } else {
            radio.setContainerClickListener { view ->
                StoreStream.getUserGuildSettings()
                    .setGuildUnreadSetting(view.context.applicationContext, viewModel.guildId, setting)
            }
        }
        if (model.unreadSetting == setting) unreadRadioManager?.setChecked(radio)
    }

    private fun configureOverrides(model: WidgetServerNotificationsViewModel.ViewState.Loaded) {
        overrideAdapter?.data = model.overrides
    }

    /** Hubs have no roles, no `@everyone`, no per-channel overrides and no highlights. */
    private fun configureForHub() {
        binding.serverNotificationsFrequencyDivider.visibility = View.GONE
        binding.serverNotificationsFrequencyWrap.visibility = View.GONE
        binding.serverNotificationsEveryoneSwitch.visibility = View.GONE
        binding.serverNotificationsRolesDivider.visibility = View.GONE
        binding.serverNotificationsRolesSwitch.visibility = View.GONE
        binding.serverNotificationsPushSwitch.visibility = View.GONE
        binding.serverNotificationsOverrideTitle.visibility = View.GONE
        binding.addOverride.root.visibility = View.GONE
        binding.serverNotificationsOverrideDivider.visibility = View.GONE
        binding.guildNotificationsOverrideList.visibility = View.GONE
        binding.serverNotificationsHighlights.visibility = View.GONE
        binding.serverNotificationsHighlightsDivider.visibility = View.GONE
    }

    private fun createSwipeableItemTouchHelper(): ItemTouchHelper {
        val deleteConfig = SwipeRevealConfiguration(
            backgroundColor = ContextCompat.getColor(requireContext(), R.color.status_red_500),
            icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_delete_white_24dp),
            padding = (SWIPE_ICON_PADDING_DP * resources.displayMetrics.density).toInt(),
        )
        return ItemTouchHelper(
            object : SwipeableItemTouchHelper(deleteConfig, deleteConfig) {
                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                    val item = overrideAdapter?.data?.getOrNull(viewHolder.adapterPosition) ?: return
                    viewModel.deleteOverride(requireContext().applicationContext, item)
                }
            },
        )
    }

    companion object {
        private const val EXTRA_GUILD_ID = "com.discord.intent.extra.EXTRA_GUILD_ID"
        private const val INVALID_GUILD_ID = -1L
        private const val SWIPE_ICON_PADDING_DP = 8

        fun newInstance(guildId: Long) = WidgetServerNotifications().apply {
            arguments = Bundle().apply { putLong(EXTRA_GUILD_ID, guildId) }
        }

        fun launch(context: Context, guildId: Long) {
            SettingsNavigation.launch(
                context,
                WidgetServerNotifications::class.java,
                Intent().putExtra(EXTRA_GUILD_ID, guildId),
            )
        }
    }
}

/**
 * `ItemTouchHelper.SimpleCallback` that paints a coloured panel with an icon behind the row as it is
 * dragged aside.
 *
 * Port of `com.discord.utilities.views.SwipeableItemTouchHelper`, which this project has not
 * reconstructed. It is kept here rather than in `utilities/views` so two concurrently-running slices
 * cannot both create it; fold it into `utilities/views` when that package is next touched, since the
 * reference also uses it for the friends list and the connections list.
 */
internal abstract class SwipeableItemTouchHelper(
    private val startConfiguration: SwipeRevealConfiguration? = null,
    private val endConfiguration: SwipeRevealConfiguration? = null,
    dragDirs: Int = 0,
    swipeDirs: Int = ItemTouchHelper.START or ItemTouchHelper.END,
) : ItemTouchHelper.SimpleCallback(dragDirs, swipeDirs) {

    private val background = ColorDrawable()

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder,
    ): Boolean = false

    override fun onChildDraw(
        c: Canvas,
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        dX: Float,
        dY: Float,
        actionState: Int,
        isCurrentlyActive: Boolean,
    ) {
        val itemView = viewHolder.itemView
        if (dX > 0 && startConfiguration != null) {
            val revealEnd = itemView.left + dX.toInt()
            background.color = startConfiguration.backgroundColor
            background.setBounds(itemView.left, itemView.top, revealEnd, itemView.bottom)
            background.draw(c)
            startConfiguration.icon?.let { icon ->
                val iconEnd = min(
                    itemView.left + startConfiguration.padding + icon.intrinsicWidth,
                    revealEnd,
                )
                val iconTop = itemView.top + (itemView.height - icon.intrinsicHeight) / 2
                icon.setBounds(
                    iconEnd - icon.intrinsicWidth,
                    iconTop,
                    iconEnd,
                    iconTop + icon.intrinsicHeight,
                )
                icon.draw(c)
            }
        } else if (dX < 0 && endConfiguration != null) {
            background.color = endConfiguration.backgroundColor
            background.setBounds(
                itemView.right + dX.toInt(),
                itemView.top,
                itemView.right,
                itemView.bottom,
            )
            background.draw(c)
            endConfiguration.icon?.let { icon ->
                val iconStart = itemView.right - min(
                    abs(dX).toInt(),
                    endConfiguration.padding + icon.intrinsicWidth,
                )
                val iconTop = itemView.top + (itemView.height - icon.intrinsicHeight) / 2
                icon.setBounds(
                    iconStart,
                    iconTop,
                    iconStart + icon.intrinsicWidth,
                    iconTop + icon.intrinsicHeight,
                )
                icon.draw(c)
            }
        }
        super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
    }
}

/** What is revealed behind a row swiped in one direction. */
internal class SwipeRevealConfiguration(
    val backgroundColor: Int = 0,
    val icon: Drawable? = null,
    val padding: Int = 0,
)

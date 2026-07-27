package com.discord.widgets.servers

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.appViewModels
import com.discord.models.domain.ModelNotificationSettings
import com.discord.utilities.rx.ui
import com.discord.views.CheckedSetting
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

    private val guildId: Long
        get() = arguments?.getLong(EXTRA_GUILD_ID, INVALID_GUILD_ID)
            ?.takeIf { it != INVALID_GUILD_ID }
            ?: mostRecentIntent.getLongExtra(EXTRA_GUILD_ID, INVALID_GUILD_ID)

    private val viewModel: WidgetServerNotificationsViewModel by appViewModels {
        WidgetServerNotificationsViewModel(guildId)
    }

    private lateinit var muteSettings: NotificationMuteSettingsView
    private lateinit var frequencyDivider: View
    private lateinit var frequencyWrap: LinearLayout
    private lateinit var frequencyRadios: List<CheckedSetting>
    private lateinit var radioManager: RadioManager
    private lateinit var highlights: NotificationHighlightsSettingsView
    private lateinit var highlightsDivider: View
    private lateinit var everyoneSwitch: CheckedSetting
    private lateinit var rolesSwitch: CheckedSetting
    private lateinit var rolesDivider: View
    private lateinit var pushSwitch: CheckedSetting
    private lateinit var overrideTitle: TextView
    private lateinit var overrideDivider: View
    private lateinit var addOverride: View
    private lateinit var overrideList: RecyclerView
    private lateinit var overrideAdapter: NotificationsOverridesAdapter

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        muteSettings = view.findViewById(R.id.server_notifications_mute_settings_view)
        frequencyDivider = view.findViewById(R.id.server_notifications_frequency_divider)
        frequencyWrap = view.findViewById(R.id.server_notifications_frequency_wrap)
        frequencyRadios = listOf(
            view.findViewById(R.id.server_notifications_frequency_0_radio),
            view.findViewById(R.id.server_notifications_frequency_1_radio),
            view.findViewById(R.id.server_notifications_frequency_2_radio),
        )
        highlights = view.findViewById(R.id.server_notifications_highlights)
        highlightsDivider = view.findViewById(R.id.server_notifications_highlights_divider)
        everyoneSwitch = view.findViewById(R.id.server_notifications_everyone_switch)
        rolesSwitch = view.findViewById(R.id.server_notifications_roles_switch)
        rolesDivider = view.findViewById(R.id.server_notifications_roles_divider)
        pushSwitch = view.findViewById(R.id.server_notifications_push_switch)
        overrideTitle = view.findViewById(R.id.server_notifications_override_title)
        overrideDivider = view.findViewById(R.id.server_notifications_override_divider)
        addOverride = view.findViewById(R.id.add_override)
        overrideList = view.findViewById(R.id.guild_notifications_override_list)

        radioManager = RadioManager(frequencyRadios)

        overrideAdapter = NotificationsOverridesAdapter { itemView, item ->
            SettingsNavigation.launch(
                itemView.context,
                WidgetChannelNotificationSettings.newInstance(item.channel.id),
            )
        }
        // The list lives inside the screen's NestedScrollView, so it must never scroll itself.
        overrideList.isNestedScrollingEnabled = false
        overrideList.layoutManager = LinearLayoutManager(view.context)
        overrideList.adapter = overrideAdapter
        createSwipeableItemTouchHelper().attachToRecyclerView(overrideList)

        addOverride.setOnClickListener {
            WidgetServerNotificationsOverrideSelector.launch(it.context, guildId)
        }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState().ui(this).subscribe { configureUI(it) }
    }

    private fun configureUI(viewState: WidgetServerNotificationsViewModel.ViewState) {
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
        frequencyDivider.visibility = visibleWhileUnmuted
        frequencyWrap.visibility = visibleWhileUnmuted

        if (model.isHub) {
            configureForHub()
            return
        }

        configureHighlights(model)
        configureToggles(model)
        configureRadios(model)
        configureOverrides(model)
    }

    private fun configureMuteSettings(model: WidgetServerNotificationsViewModel.ViewState.Loaded) {
        val context = requireContext()
        muteSettings.updateView(
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
        highlights.visibility = visibility
        highlightsDivider.visibility = visibility
        if (!model.highlightsEnabled) return
        highlights.updateView(
            NotificationHighlightsSettingsView.ViewState(
                isMuted = model.isMuted,
                notifyHighlights = model.guildSettings.notifyHighlights,
                serverTooLarge = model.isAboveHighlightsSize,
            ),
        ) { notifyHighlights ->
            viewModel.setNotifyHighlights(highlights.context.applicationContext, notifyHighlights)
        }
    }

    private fun configureToggles(model: WidgetServerNotificationsViewModel.ViewState.Loaded) {
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
        if (model.notificationsSetting == frequency) radioManager.setChecked(radio)
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

    private fun configureOverrides(model: WidgetServerNotificationsViewModel.ViewState.Loaded) {
        overrideAdapter.data = model.overrides
    }

    /** Hubs have no roles, no `@everyone`, no per-channel overrides and no highlights. */
    private fun configureForHub() {
        frequencyDivider.visibility = View.GONE
        frequencyWrap.visibility = View.GONE
        everyoneSwitch.visibility = View.GONE
        rolesDivider.visibility = View.GONE
        rolesSwitch.visibility = View.GONE
        pushSwitch.visibility = View.GONE
        overrideTitle.visibility = View.GONE
        addOverride.visibility = View.GONE
        overrideDivider.visibility = View.GONE
        overrideList.visibility = View.GONE
        highlights.visibility = View.GONE
        highlightsDivider.visibility = View.GONE
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
                    val item = overrideAdapter.data.getOrNull(viewHolder.adapterPosition) ?: return
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

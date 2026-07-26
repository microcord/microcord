package com.discord.widgets.servers

import android.content.Context
import android.content.Intent
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.channel.getDisplayName
import com.discord.api.channel.isCategory
import com.discord.api.channel.isTextChannel
import com.discord.app.AppFragment
import com.discord.app.AppViewModel
import com.discord.app.appViewModels
import com.discord.stores.StoreStream
import com.discord.utilities.mg_recycler.CategoricalDragAndDropAdapter
import com.discord.utilities.mg_recycler.MGRecyclerAdapter
import com.discord.utilities.rx.computationBuffered
import com.discord.utilities.rx.leadingEdgeThrottle
import com.discord.utilities.rx.ui
import com.discord.utilities.view.text.addBindedTextWatcher
import com.discord.widgets.channels.settings.WidgetChannelNotificationSettings
import com.discord.widgets.settings.SettingsNavigation
import com.google.android.material.textfield.TextInputLayout
import java.util.concurrent.TimeUnit
import rx.Observable
import rx.subjects.BehaviorSubject

/**
 * "Add a Channel or Category" — picks which channel gets a notification override, then hands off to
 * that channel's notification settings, which is where the override is actually made.
 *
 * Reference: `com.discord.widgets.servers.WidgetServerNotificationsOverrideSelector`. Only channels
 * the user can see are listed, categories included, filtered live by the search field.
 */
class WidgetServerNotificationsOverrideSelector :
    AppFragment(R.layout.widget_server_notification_override_selector) {

    private val guildId: Long
        get() = arguments?.getLong(EXTRA_GUILD_ID, INVALID_GUILD_ID)
            ?.takeIf { it != INVALID_GUILD_ID }
            ?: mostRecentIntent.getLongExtra(EXTRA_GUILD_ID, INVALID_GUILD_ID)

    private val viewModel: WidgetServerNotificationsOverrideSelectorViewModel by appViewModels {
        WidgetServerNotificationsOverrideSelectorViewModel(guildId)
    }

    private lateinit var channelsAdapter: SettingsChannelListAdapter

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarDisplayHomeAsUpEnabled()
        setActionBarTitle(R.string.notification_overrides)
        setActionBarSubtitle(R.string.select_channel_or_category)

        view.findViewById<TextInputLayout>(R.id.guild_notifications_channel_search)
            .editText
            ?.addBindedTextWatcher(this) { editable -> viewModel.setFilter(editable.toString()) }

        val recycler = view.findViewById<RecyclerView>(R.id.guild_notifications_channel_list)
        channelsAdapter = MGRecyclerAdapter
            .configure<CategoricalDragAndDropAdapter.Payload, SettingsChannelListAdapter>(
                SettingsChannelListAdapter(recycler, isDraggable = false),
            )
        channelsAdapter.onClickListener = { channelId ->
            SettingsNavigation.launch(
                requireContext(),
                WidgetChannelNotificationSettings.newInstance(channelId),
            )
        }
        recycler.isNestedScrollingEnabled = false
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState().ui(this).subscribe { viewState ->
            if (viewState is WidgetServerNotificationsOverrideSelectorViewModel.ViewState.Loaded) {
                channelsAdapter.setData(viewState.items)
            }
        }
    }

    companion object {
        private const val EXTRA_GUILD_ID = "com.discord.intent.extra.EXTRA_GUILD_ID"
        private const val INVALID_GUILD_ID = -1L

        fun launch(context: Context, guildId: Long) {
            SettingsNavigation.launch(
                context,
                WidgetServerNotificationsOverrideSelector::class.java,
                Intent().putExtra(EXTRA_GUILD_ID, guildId),
            )
        }
    }
}

/**
 * Filtered channel list for [WidgetServerNotificationsOverrideSelector].
 *
 * The reference keeps this in the fragment's companion as a bare observable and holds the filter in
 * a fragment field, which loses the query on rotation. Holding it here keeps it.
 */
class WidgetServerNotificationsOverrideSelectorViewModel(
    guildId: Long,
    storeStateObservable: Observable<StoreState> = observeStoreState(guildId),
) : AppViewModel<WidgetServerNotificationsOverrideSelectorViewModel.ViewState>(ViewState.Uninitialized) {

    data class StoreState(
        val guildChannels: Map<Long, Channel>,
        val channelPermissions: Map<Long, Long>,
    )

    sealed class ViewState {
        object Uninitialized : ViewState()

        data class Loaded(
            val items: List<CategoricalDragAndDropAdapter.Payload>,
            val filter: String,
        ) : ViewState() {
            val isEmpty: Boolean get() = items.isEmpty()
        }
    }

    private val filterSubject = BehaviorSubject.create("")

    init {
        Observable.combineLatest(
            storeStateObservable,
            // The list is rebuilt per keystroke, so the first character shows immediately and the
            // rest of a fast burst is coalesced.
            filterSubject.leadingEdgeThrottle(FILTER_THROTTLE_MS, TimeUnit.MILLISECONDS),
        ) { storeState, filter -> buildItems(storeState, filter) to filter }
            .computationBuffered()
            .distinctUntilChanged()
            .ui(this)
            .subscribe { (items, filter) -> updateViewState(ViewState.Loaded(items, filter)) }
    }

    fun setFilter(filter: String) = filterSubject.onNext(filter)

    companion object {
        private const val FILTER_THROTTLE_MS = 100L

        internal fun buildItems(
            storeState: StoreState,
            filter: String,
        ): List<CategoricalDragAndDropAdapter.Payload> =
            GuildChannelsSort
                .sortedVisibleChannels(storeState.guildChannels, storeState.channelPermissions)
                .filter { channel ->
                    (channel.isTextChannel() || channel.isCategory()) &&
                        channel.getDisplayName().contains(filter, ignoreCase = true)
                }
                .mapNotNull { channel ->
                    when {
                        // Nothing here is reorderable: the screen only picks a channel.
                        channel.isCategory() -> SettingsChannelListAdapter.CategoryItem(
                            name = channel.getDisplayName(),
                            id = channel.id,
                            pos = channel.position,
                            isDraggable = false,
                            canManageCategory = false,
                            canManageChannelsOfCategory = false,
                        )

                        channel.isTextChannel() -> SettingsChannelListAdapter.ChannelItem(
                            channel = channel,
                            isDraggable = false,
                            parentId = channel.parentId,
                            canManageCategoryOfChannel = false,
                        )

                        else -> null
                    }
                }

        fun observeStoreState(guildId: Long): Observable<StoreState> = Observable.combineLatest(
            StoreStream.getChannels().observeChannelsForGuild(guildId),
            StoreStream.getPermissions().observeChannelPermissionsForGuild(guildId),
        ) { channels, permissions -> StoreState(channels, permissions) }
    }
}

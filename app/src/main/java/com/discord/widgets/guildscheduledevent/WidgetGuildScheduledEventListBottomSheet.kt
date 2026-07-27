package com.discord.widgets.guildscheduledevent

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.app.AppBottomSheet
import com.discord.app.AppToast
import com.discord.app.appViewModels
import com.discord.i18n.I18nHooks
import com.discord.utilities.recycler.SpaceBetweenItemDecoration
import com.discord.utilities.rx.ui
import com.discord.widgets.voice.VoiceUtils
import java.lang.ref.WeakReference
import rx.subscriptions.CompositeSubscription

/**
 * The guild's list of scheduled events.
 *
 * Reached from the guild header, and — with [EXTRA_CHANNEL_ID] — from a voice channel, in which
 * case only that channel's events are listed. The empty state and the "create event" button are
 * independent: a guild can have no events and no permission to add one.
 */
class WidgetGuildScheduledEventListBottomSheet : AppBottomSheet() {

    private val guildId: Long get() = argumentsOrDefault.getLong(EXTRA_GUILD_ID)

    private val channelId: Long?
        get() = argumentsOrDefault.takeIf { it.containsKey(EXTRA_CHANNEL_ID) }?.getLong(EXTRA_CHANNEL_ID)

    private val viewModel: GuildScheduledEventListViewModel by appViewModels {
        GuildScheduledEventListViewModel(guildId, channelId)
    }

    private lateinit var adapter: GuildScheduledEventListAdapter
    private lateinit var toolbarTitle: TextView
    private lateinit var createEventButton: TextView
    private lateinit var recycler: RecyclerView
    private lateinit var emptyState: ConstraintLayout

    override fun getContentViewResId(): Int = R.layout.widget_guild_scheduled_event_list_bottom_sheet

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbarTitle = view.findViewById(R.id.guild_scheduled_event_list_toolbar_title)
        createEventButton = view.findViewById(R.id.create_event_button)
        recycler = view.findViewById(R.id.guild_scheduled_event_list_recycler_view)
        emptyState = view.findViewById(R.id.guild_scheduled_event_list_empty_state)

        adapter = GuildScheduledEventListAdapter(
            recyclerView = recycler,
            onRsvpClicked = ::onRsvpClicked,
            onEventStartClicked = ::onEventStartClicked,
            onCardClicked = ::onEventCardClicked,
            onJoinClicked = ::onJoinClicked,
            onShareClicked = ::onShareClicked,
        )
        recycler.adapter = adapter
        val layoutManager = LinearLayoutManager(recycler.context)
        recycler.layoutManager = layoutManager
        recycler.addItemDecoration(
            SpaceBetweenItemDecoration(recycler.context, layoutManager.orientation, adapter),
        )
        // The cards re-bind on every RSVP toggle; the default change animation would make the
        // whole list flash on each one.
        recycler.itemAnimator = null

        createEventButton.setOnClickListener {
            WidgetGuildScheduledEventLocationSelect.launch(requireContext(), guildId, channelId)
        }
    }

    override fun bindSubscriptions(compositeSubscription: CompositeSubscription) {
        viewModel.observeViewState()
            .ui(this)
            .subscribe(::configureUi)
    }

    private fun configureUi(viewState: GuildScheduledEventListViewModel.ViewState) {
        val state = viewState as? GuildScheduledEventListViewModel.ViewState.Loaded ?: return
        toolbarTitle.text = I18nHooks.plural(
            requireContext(),
            R.plurals.guild_events_plural_number,
            state.guildScheduledEvents.size,
        )
        adapter.setData(state.guildScheduledEvents)
        createEventButton.visibility = if (state.canCreateEvents) View.VISIBLE else View.GONE
        emptyState.visibility = if (state.guildScheduledEvents.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun onEventCardClicked(item: GuildScheduledEventListItem.Event) =
        WidgetGuildScheduledEventDetailsBottomSheet.showForGuild(parentFragmentManager, item.event.id)

    private fun onEventStartClicked(item: GuildScheduledEventListItem.Event) {
        if (!item.canStartEvent) return
        WidgetPreviewGuildScheduledEvent.launch(
            requireContext(),
            item.event.toModel(),
            WidgetPreviewGuildScheduledEvent.ExistingEventData(
                item.event.id,
                WidgetPreviewGuildScheduledEvent.Action.START_EVENT,
            ),
        )
    }

    private fun onJoinClicked(item: GuildScheduledEventListItem.Event) {
        val channel = item.channel ?: return
        dismiss()
        VoiceUtils.handleCallChannel(channel, this)
    }

    /** The confirmation toast is shown on the way *into* the RSVP, not out of it. */
    private fun onRsvpClicked(item: GuildScheduledEventListItem.Event) {
        if (!item.isRsvped) {
            AppToast.show(context, R.string.guild_event_interested_notification)
        }
        viewModel.toggleRsvp(item.event)
    }

    private fun onShareClicked(item: GuildScheduledEventListItem.Event) =
        viewModel.onShareClicked(item.event, WeakReference(requireContext()), WeakReference(this))

    companion object {
        private const val EXTRA_GUILD_ID = "com.discord.intent.extra.EXTRA_GUILD_ID"
        private const val EXTRA_CHANNEL_ID = "com.discord.intent.extra.EXTRA_CHANNEL_ID"

        @JvmStatic
        @JvmOverloads
        fun show(fragmentManager: FragmentManager, guildId: Long, channelId: Long? = null) {
            WidgetGuildScheduledEventListBottomSheet().apply {
                arguments = Bundle().apply {
                    putLong(EXTRA_GUILD_ID, guildId)
                    if (channelId != null) putLong(EXTRA_CHANNEL_ID, channelId)
                }
            }.show(fragmentManager, WidgetGuildScheduledEventListBottomSheet::class.java.name)
        }
    }
}

package com.discord.widgets.guildscheduledevent

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.app.AppBottomSheet
import com.discord.app.AppToast
import com.discord.app.appViewModels
import com.discord.i18n.Hook
import com.discord.i18n.I18nHooks
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.rx.ui
import com.discord.utilities.view.extensions.setTextAndVisibilityBy
import com.discord.utilities.view.text.LinkifiedTextView
import com.discord.views.segmentedcontrol.CardSegment
import com.discord.views.segmentedcontrol.SegmentedControlContainer
import com.discord.widgets.guildscheduledevent.buttonconfiguration.ButtonConfiguration
import com.discord.widgets.guildscheduledevent.buttonconfiguration.DirectoryButtonConfiguration
import com.discord.widgets.guildscheduledevent.buttonconfiguration.GuildButtonConfiguration
import com.discord.widgets.voice.VoiceUtils
import com.discord.views.VitoImageView
import java.lang.ref.WeakReference
import rx.subscriptions.CompositeSubscription

/**
 * The event details sheet: everything about one scheduled event, plus its attendee list.
 *
 * Two segments share the sheet. The segmented control itself is hidden for a viewer who is not in
 * the guild — they have no business browsing the attendee list of a server they have not joined —
 * which is why [GuildScheduledEventDetailsViewModel.ViewState.Initialized.isInGuild] drives its
 * visibility rather than any permission.
 *
 * A `ViewState` that is not [GuildScheduledEventDetailsViewModel.ViewState.Initialized] means the
 * event vanished while the sheet was open, and the sheet dismisses itself.
 */
class WidgetGuildScheduledEventDetailsBottomSheet : AppBottomSheet() {

    private val args: GuildScheduledEventDetailsArgs
        get() = requireNotNull(
            argumentsOrDefault.getParcelable(GuildScheduledEventDetailsArgs.INTENT_ARGS_KEY),
        )

    private val viewModel: GuildScheduledEventDetailsViewModel by appViewModels {
        GuildScheduledEventDetailsViewModel(args)
    }

    private lateinit var adapter: GuildScheduledEventRsvpUserListAdapter
    private val descriptionParser: GuildScheduledEventDescriptionParser by lazy {
        GuildScheduledEventDescriptionParser(description)
    }
    private val locationParser = GuildScheduledEventExternalLocationParser()

    private lateinit var segmentedControl: SegmentedControlContainer
    private lateinit var segmentEventInfo: CardSegment
    private lateinit var segmentRsvpList: CardSegment
    private lateinit var infoContainer: ConstraintLayout
    private lateinit var rsvpListContainer: ConstraintLayout
    private lateinit var emptyOrErrorContainer: ConstraintLayout
    private lateinit var emptyOrErrorTitle: TextView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var recycler: RecyclerView
    private lateinit var dateView: GuildScheduledEventDateView
    private lateinit var title: TextView
    private lateinit var guildIcon: VitoImageView
    private lateinit var guildName: TextView
    private lateinit var channelIcon: ImageView
    private lateinit var channelName: LinkifiedTextView
    private lateinit var rsvpIcon: ImageView
    private lateinit var rsvpCount: TextView
    private lateinit var creatorAvatar: VitoImageView
    private lateinit var createdBy: TextView
    private lateinit var description: LinkifiedTextView
    private lateinit var buttons: GuildScheduledEventBottomButtonView

    override fun getContentViewResId(): Int = R.layout.widget_guild_scheduled_event_details_bottom_sheet

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        segmentedControl = view.findViewById(R.id.segmented_control)
        segmentEventInfo = view.findViewById(R.id.segmented_control_event_info)
        segmentRsvpList = view.findViewById(R.id.segmented_control_rsvp_list)
        infoContainer = view.findViewById(R.id.guild_scheduled_event_info_container)
        rsvpListContainer = view.findViewById(R.id.guild_scheduled_event_rsvp_list_container)
        emptyOrErrorContainer = view.findViewById(R.id.guild_scheduled_event_empty_or_error_container)
        emptyOrErrorTitle = view.findViewById(R.id.guild_scheduled_event_empty_or_error_title)
        loadingIndicator = view.findViewById(R.id.loading_indicator)
        recycler = view.findViewById(R.id.guild_scheduled_event_rsvp_list_recycler_view)
        dateView = view.findViewById(R.id.guild_scheduled_event_details_date_view)
        title = view.findViewById(R.id.guild_scheduled_event_details_title_text)
        guildIcon = view.findViewById(R.id.guild_scheduled_event_details_guild_icon)
        guildName = view.findViewById(R.id.guild_scheduled_event_details_guild_name)
        channelIcon = view.findViewById(R.id.guild_scheduled_event_details_channel_icon)
        channelName = view.findViewById(R.id.guild_scheduled_event_details_channel_name)
        rsvpIcon = view.findViewById(R.id.guild_scheduled_event_details_rsvp_icon)
        rsvpCount = view.findViewById(R.id.guild_scheduled_event_details_rsvp_count)
        creatorAvatar = view.findViewById(R.id.guild_scheduled_event_details_creator_avatar)
        createdBy = view.findViewById(R.id.guild_scheduled_event_details_created_by)
        description = view.findViewById(R.id.guild_scheduled_event_details_desc_text)
        buttons = view.findViewById(R.id.guild_scheduled_event_details_button_view)

        adapter = GuildScheduledEventRsvpUserListAdapter(recycler) { dismiss() }
        recycler.adapter = adapter
        recycler.layoutManager = LinearLayoutManager(recycler.context)
    }

    override fun bindSubscriptions(compositeSubscription: CompositeSubscription) {
        viewModel.observeViewState()
            .ui(this)
            .subscribe(::configureUi)
    }

    private fun configureUi(viewState: GuildScheduledEventDetailsViewModel.ViewState) {
        val state = viewState as? GuildScheduledEventDetailsViewModel.ViewState.Initialized ?: run {
            dismiss()
            return
        }
        configureSegmentControl(state.segmentedControlIndex, hidden = !state.isInGuild)
        configureForSection(state.section, state.rsvpUsersFetchState)

        dateView.configure(state.guildScheduledEvent, truncateDateTime = false)
        title.text = state.guildScheduledEvent.name
        IconUtils.setIcon(guildIcon, IconUtils.getForGuild(state.guild))
        guildName.text = state.guild.name
        guildName.setOnClickListener {
            viewModel.onGuildNameClicked()
            dismiss()
        }

        state.locationInfo?.let { channelIcon.setImageResource(it.locationIcon) }
        channelIcon.visibility = if (state.locationInfo != null) View.VISIBLE else View.GONE
        channelName.setTextAndVisibilityBy(
            state.locationInfo?.let { locationParser.getTextFromLocation(requireContext(), it) },
        )

        val userCount = state.guildScheduledEvent.userCount
        rsvpIcon.visibility = if (userCount != null) View.VISIBLE else View.GONE
        rsvpCount.setTextAndVisibilityBy(
            userCount?.let {
                requireContext().getString(R.string.guild_event_interested_count).replace(
                    "{count}",
                    I18nHooks.plural(requireContext(), R.plurals.guild_event_interested_count_count, it),
                )
            },
        )

        configureCreator(state)
        descriptionParser.configureDescription(
            state.guildScheduledEvent.description,
            state.guildScheduledEvent.guildId,
        )

        buttons.visibility = View.VISIBLE
        buttons.configureForDetails(getButtonConfiguration(state))

        adapter.setData(state.rsvpUsers)
    }

    private fun configureCreator(state: GuildScheduledEventDetailsViewModel.ViewState.Initialized) {
        val creator = state.creator
        if (creator == null) {
            createdBy.visibility = View.GONE
            creatorAvatar.visibility = View.GONE
            return
        }
        createdBy.visibility = View.VISIBLE
        creatorAvatar.visibility = View.VISIBLE
        createdBy.text = I18nHooks.format(
            requireContext(),
            R.string.guild_event_created_by_hook,
            hooks = mapOf("usernameHook" to Hook(text = creator.getNickOrUserName())),
        )
        IconUtils.setIcon(
            creatorAvatar,
            IconUtils.getForGuildMemberOrUser(creator.user, creator.guildMember),
            R.dimen.avatar_size_standard,
        )
    }

    /** The segmented control is meaningless with only one segment available, so it is hidden. */
    private fun configureSegmentControl(selectedIndex: Int, hidden: Boolean) {
        segmentedControl.visibility = if (hidden) View.GONE else View.VISIBLE
        if (hidden) return
        segmentedControl.setup()
        segmentedControl.setOnSegmentSelectedChangeListener(viewModel::setSegmentedControlIndex)
        segmentedControl.setSelectedIndex(selectedIndex)
        segmentEventInfo.setText(getString(R.string.guild_event_details_info_tab_title))
        segmentRsvpList.setText(getString(R.string.indicate_rsvp))
    }

    private fun configureForSection(
        section: EventDetailsSection,
        rsvpUsersFetchState: EventDetailsRsvpUsersFetchState,
    ) {
        if (section == EventDetailsSection.RSVP_LIST) configureForRsvpUsersFetchState(rsvpUsersFetchState)
        infoContainer.visibility =
            if (section == EventDetailsSection.EVENT_INFO) View.VISIBLE else View.GONE
        rsvpListContainer.visibility =
            if (section == EventDetailsSection.RSVP_LIST) View.VISIBLE else View.GONE
    }

    /** All three panels are hidden first so exactly one is ever visible. */
    private fun configureForRsvpUsersFetchState(state: EventDetailsRsvpUsersFetchState) {
        loadingIndicator.visibility = View.GONE
        emptyOrErrorContainer.visibility = View.GONE
        recycler.visibility = View.GONE
        when (state) {
            EventDetailsRsvpUsersFetchState.LOADING -> loadingIndicator.visibility = View.VISIBLE
            EventDetailsRsvpUsersFetchState.ERROR -> {
                emptyOrErrorContainer.visibility = View.VISIBLE
                emptyOrErrorTitle.setText(R.string.guild_event_details_user_list_fetch_error)
            }
            EventDetailsRsvpUsersFetchState.EMPTY -> {
                emptyOrErrorContainer.visibility = View.VISIBLE
                emptyOrErrorTitle.setText(R.string.guild_event_details_user_list_empty_title)
            }
            EventDetailsRsvpUsersFetchState.SUCCESS -> recycler.visibility = View.VISIBLE
        }
    }

    /**
     * A directory-sourced sheet offers a different primary action — join the server and go to the
     * event — so the two sources build different configurations from the same state.
     */
    private fun getButtonConfiguration(
        state: GuildScheduledEventDetailsViewModel.ViewState.Initialized,
    ): ButtonConfiguration {
        if (args.source == GuildScheduledEventDetailsSource.Directory) {
            return DirectoryButtonConfiguration(
                guildScheduledEvent = state.guildScheduledEvent,
                isInGuild = state.isInGuild,
                isRsvped = state.isRsvped,
                isDetailView = true,
                // The reference routes this through `DirectoryUtils.maybeJoinAndGoToGuild`, which
                // joins the guild if needed and then RSVPs. That utility does not exist in this
                // target, so an already-joined viewer navigates and an outsider is left with the
                // RSVP toggle rather than a broken join.
                primaryButtonOnClickListener = {
                    viewModel.onGuildNameClicked()
                    dismiss()
                },
                secondaryButtonOnClickListener = { viewModel.onRsvpButtonClicked() },
                shareButtonOnClickListener = { viewModel.onShareButtonClicked(WeakReference(this)) },
                extrasButtonOnClickListener = { showExtras() },
            )
        }
        return GuildButtonConfiguration(
            guildScheduledEvent = state.guildScheduledEvent,
            canRsvp = true,
            canStartEvent = state.canStartEvent,
            isConnected = state.isConnected,
            canConnect = state.canConnect,
            isInGuild = state.isInGuild,
            isRsvped = state.isRsvped,
            isDetailView = true,
            onRsvpButtonClicked = { viewModel.onRsvpButtonClicked() },
            onJoinButtonClicked = {
                dismiss()
                state.channel?.let { VoiceUtils.handleCallChannel(it, this) }
            },
            onEndEventButtonClicked = { viewModel.endEventClicked(requireContext(), ::dismiss) },
            onStartEventButtonClicked = {
                WidgetPreviewGuildScheduledEvent.launch(
                    requireContext(),
                    state.guildScheduledEvent.toModel(),
                    WidgetPreviewGuildScheduledEvent.ExistingEventData(
                        state.guildScheduledEvent.id,
                        WidgetPreviewGuildScheduledEvent.Action.START_EVENT,
                    ),
                )
            },
            onShareButtonClicked = { viewModel.onShareButtonClicked(WeakReference(this)) },
            onExtraButtonClicked = { showExtras() },
        )
    }

    private fun showExtras() {
        dismiss()
        WidgetGuildScheduledEventDetailsExtrasBottomSheet.show(parentFragmentManager, args)
    }

    companion object {

        fun show(fragmentManager: FragmentManager, args: GuildScheduledEventDetailsArgs) {
            WidgetGuildScheduledEventDetailsBottomSheet().apply {
                arguments = Bundle().apply {
                    putParcelable(GuildScheduledEventDetailsArgs.INTENT_ARGS_KEY, args)
                }
            }.show(fragmentManager, WidgetGuildScheduledEventDetailsBottomSheet::class.java.name)
        }

        /** The guild-side entry point: an event id is enough, the store finds the rest. */
        @JvmStatic
        fun showForGuild(fragmentManager: FragmentManager, eventId: Long) =
            show(fragmentManager, GuildScheduledEventDetailsArgs(eventId))

        /** The hub entry point; the channel and guild name the directory entry it came from. */
        @JvmStatic
        fun showForDirectory(
            fragmentManager: FragmentManager,
            eventId: Long,
            guildId: Long,
            channelId: Long,
        ) = show(
            fragmentManager,
            GuildScheduledEventDetailsArgs(
                eventId,
                guildId,
                channelId,
                GuildScheduledEventDetailsSource.Directory,
            ),
        )

        /** Shown when a link resolves to an event the viewer cannot see. */
        @JvmStatic
        fun handleInvalidEvent(context: Context) {
            AppToast.show(context, R.string.inaccessible_channel_link_title)
        }
    }
}

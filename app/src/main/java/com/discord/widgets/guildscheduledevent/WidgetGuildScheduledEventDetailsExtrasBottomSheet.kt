package com.discord.widgets.guildscheduledevent

import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppBottomSheet
import com.discord.app.AppToast
import com.discord.app.appViewModels
import com.discord.i18n.I18nHooks
import com.discord.utilities.rx.ui
import com.discord.widgets.notice.WidgetNoticeDialog
import rx.subscriptions.CompositeSubscription

/**
 * The overflow menu of the event details sheet.
 *
 * It replaces the details sheet rather than stacking on top of it, so dismissing it without
 * choosing anything has to bring the details sheet back — that is what [actionTaken] tracks. An
 * action that navigates somewhere sets it, and the details sheet stays closed.
 */
class WidgetGuildScheduledEventDetailsExtrasBottomSheet : AppBottomSheet() {

    private var actionTaken = false

    private val args: GuildScheduledEventDetailsArgs
        get() = requireNotNull(
            argumentsOrDefault.getParcelable(GuildScheduledEventDetailsArgs.INTENT_ARGS_KEY),
        )

    private val viewModel: GuildScheduledEventDetailsViewModel by appViewModels {
        GuildScheduledEventDetailsViewModel(args)
    }

    private lateinit var interestedToggle: TextView
    private lateinit var startEvent: TextView
    private lateinit var editEvent: TextView
    private lateinit var endEvent: TextView
    private lateinit var cancelEvent: TextView
    private lateinit var reportEvent: TextView
    private lateinit var copyId: TextView

    override fun getContentViewResId(): Int = R.layout.widget_guild_scheduled_event_details_extras_bottom_sheet

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        interestedToggle = view.findViewById(R.id.interested_toggle)
        startEvent = view.findViewById(R.id.start_event)
        editEvent = view.findViewById(R.id.edit_event)
        endEvent = view.findViewById(R.id.end_event)
        cancelEvent = view.findViewById(R.id.cancel_event)
        reportEvent = view.findViewById(R.id.report_event)
        copyId = view.findViewById(R.id.copy_id)
    }

    override fun bindSubscriptions(compositeSubscription: CompositeSubscription) {
        viewModel.observeViewState()
            .ui(this)
            .subscribe(::configureUi)
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (!actionTaken) {
            WidgetGuildScheduledEventDetailsBottomSheet.show(parentFragmentManager, args)
        }
    }

    private fun configureUi(viewState: GuildScheduledEventDetailsViewModel.ViewState) {
        val state = viewState as? GuildScheduledEventDetailsViewModel.ViewState.Initialized ?: run {
            dismiss()
            return
        }
        val event = state.guildScheduledEvent
        val canStartEvent = state.canStartEvent
        val timing = ScheduledEventUi.timing(event)
        val isLive = timing == GuildScheduledEventTiming.LIVE

        interestedToggle.visible(canStartEvent && timing.isStartable())
        interestedToggle.setText(
            if (state.isRsvped) R.string.event_mark_not_interested else R.string.event_mark_interested,
        )
        interestedToggle.setOnClickListener { viewModel.onRsvpButtonClicked() }

        // "Start" only makes sense while the event is still comfortably in the future; once it is
        // imminent or overdue the interested toggle above takes the slot instead.
        startEvent.visible(canStartEvent && timing.isLongStartable())
        startEvent.setOnClickListener {
            WidgetPreviewGuildScheduledEvent.launch(
                requireContext(),
                event.toModel(),
                WidgetPreviewGuildScheduledEvent.ExistingEventData(
                    event.id,
                    WidgetPreviewGuildScheduledEvent.Action.START_EVENT,
                ),
            )
        }

        editEvent.visible(canStartEvent)
        editEvent.setOnClickListener {
            dismissWithActionTaken()
            WidgetGuildScheduledEventLocationSelect.launchForEdit(requireContext(), event.guildId, event.id)
        }

        endEvent.visible(canStartEvent && isLive)
        endEvent.setOnClickListener {
            viewModel.endEventClicked(requireContext(), ::dismissWithActionTaken)
        }

        // JADX decompiled this visibility to a degenerate `false ? 0 : 8`, so the condition is
        // inferred rather than read: deleting is offered exactly when starting would be, and only
        // while the event has not gone live.
        cancelEvent.visible(canStartEvent && !isLive)
        cancelEvent.setOnClickListener { showDeleteConfirmation(event.name) }

        reportEvent.setOnClickListener {
            dismissWithActionTaken()
            // The reference launches `WidgetMobileReports.launchGuildScheduledEventReport`. That
            // screen is not reconstructed (only its view model and sub-views exist), so the entry
            // is left inert rather than leading nowhere. Wire it when the screen lands.
        }

        copyId.visible(state.isDeveloperMode)
        copyId.setOnClickListener {
            dismissWithActionTaken()
            AppToast.copyToClipboard(requireContext(), event.id.toString())
        }
    }

    private fun showDeleteConfirmation(eventName: String) {
        WidgetNoticeDialog.show(
            fragmentManager = childFragmentManager,
            headerText = getString(R.string.delete_event),
            bodyText = I18nHooks.format(
                requireContext(),
                R.string.delete_stage_event_confirmation_description,
                mapOf("eventName" to eventName),
            ),
            okText = getString(R.string.delete_event),
            cancelText = getString(R.string.back),
            listenerMap = mapOf(
                R.id.notice_ok to { _: View ->
                    viewModel.onDeleteButtonClicked(requireContext(), ::dismiss)
                },
            ),
        )
    }

    private fun dismissWithActionTaken() {
        actionTaken = true
        dismiss()
    }

    private fun View.visible(show: Boolean) {
        visibility = if (show) View.VISIBLE else View.GONE
    }

    companion object {
        fun show(fragmentManager: FragmentManager, args: GuildScheduledEventDetailsArgs) {
            WidgetGuildScheduledEventDetailsExtrasBottomSheet().apply {
                arguments = Bundle().apply {
                    putParcelable(GuildScheduledEventDetailsArgs.INTENT_ARGS_KEY, args)
                }
            }.show(fragmentManager, WidgetGuildScheduledEventDetailsExtrasBottomSheet::class.java.name)
        }
    }
}

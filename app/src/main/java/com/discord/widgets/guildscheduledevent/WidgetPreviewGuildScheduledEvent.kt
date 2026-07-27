package com.discord.widgets.guildscheduledevent

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.app.appViewModels
import com.discord.i18n.Hook
import com.discord.i18n.I18nHooks
import com.discord.utilities.dimen.dpToPixels
import com.discord.utilities.rx.bindToComponentLifecycle
import com.discord.utilities.rx.appSubscribe
import com.discord.views.CheckedSetting
import com.google.android.material.button.MaterialButton
import java.io.Serializable

class WidgetPreviewGuildScheduledEvent : AppFragment(R.layout.widget_preview_guild_scheduled_event) {

    private val eventModel: GuildScheduledEventModel by lazy {
        @Suppress("DEPRECATION")
        requireNotNull(requireArguments().getSerializable(EXTRA_EVENT_MODEL) as? GuildScheduledEventModel)
    }
    private val existingEventData: ExistingEventData? by lazy {
        @Suppress("DEPRECATION")
        requireArguments().getSerializable(EXTRA_EXISTING_EVENT_DATA) as? ExistingEventData
    }
    private val viewModel: PreviewGuildScheduledEventViewModel by appViewModels {
        PreviewGuildScheduledEventViewModel(eventModel, existingEventData)
    }

    private lateinit var preview: GuildScheduledEventItemView
    private lateinit var subtitle: TextView
    private lateinit var title: TextView
    private lateinit var createSubtitle: TextView
    private lateinit var notifyEveryone: CheckedSetting
    private lateinit var submit: MaterialButton
    private lateinit var back: ImageView
    private lateinit var step: TextView

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        preview = view.findViewById(R.id.confirm_guild_scheduled_event_preview)
        subtitle = view.findViewById(R.id.confirm_guild_scheduled_event_subtitle)
        title = view.findViewById(R.id.confirm_guild_scheduled_event_title)
        createSubtitle = view.findViewById(R.id.create_event_subtitle)
        notifyEveryone = view.findViewById(R.id.confirm_guild_scheduled_event_notify)
        submit = view.findViewById(R.id.guild_scheduled_event_settings_next_button)
        back = view.findViewById(R.id.back_button)
        step = view.findViewById(R.id.step_text)

        step.text = I18nHooks.format(requireContext(), R.string.guild_event_step_label, mapOf("step" to 3, "total" to 3))
        back.setOnClickListener { activity?.finish() }
        view.findViewById<ImageView>(R.id.close_button).setOnClickListener {
            activity?.setResult(RESULT_QUIT)
            activity?.finish()
        }
        submit.setOnClickListener {
            val shouldNotify = notifyEveryone.visibility == View.VISIBLE && notifyEveryone.isChecked()
            viewModel.onBottomButtonClicked(requireContext(), shouldNotify) { eventId ->
                activity?.setResult(
                    Activity.RESULT_OK,
                    Intent().apply {
                        eventModel.channelId?.let { putExtra(EXTRA_CHANNEL_ID, it) }
                        putExtra(EXTRA_GUILD_SCHEDULED_EVENT_ID, eventId ?: 0L)
                        putExtra(EXTRA_EVENT_CREATED, viewModel.isCreate())
                    },
                )
                activity?.finish()
            }
        }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetPreviewGuildScheduledEvent::class.java) { configureUi(it) }
    }

    private fun configureUi(viewState: PreviewGuildScheduledEventViewModel.ViewState) {
        when (viewState) {
            PreviewGuildScheduledEventViewModel.ViewState.Initial -> Unit
            PreviewGuildScheduledEventViewModel.ViewState.Invalid -> activity?.finish()
            is PreviewGuildScheduledEventViewModel.ViewState.Initialized -> {
                val channel = (viewState.locationInfo as? GuildScheduledEventLocationInfo.ChannelLocation)?.channel
                preview.configureAsPreview(viewState.eventModel, channel, PreviewGuildScheduledEventViewModel.fallbackCreator())
                submit.isEnabled = !viewState.requestProcessing
                submit.backgroundTintList = ContextCompat.getColorStateList(
                    requireContext(),
                    if (viewState.isStartingEvent) R.color.status_green_600 else R.color.brand_500,
                )
                submit.setText(
                    when (viewState.existingEventAction) {
                        null -> R.string.schedule_event
                        Action.EDIT_EVENT -> R.string.edit_event
                        Action.START_EVENT -> R.string.start_event
                    },
                )
                back.visibility = if (viewState.isStartingEvent) View.GONE else View.VISIBLE
                step.visibility = if (viewState.isStartingEvent) View.GONE else View.VISIBLE
                notifyEveryone.visibility = if (viewState.canNotifyEveryone) View.VISIBLE else View.GONE
                if (viewState.isStartingEvent) configureStartText(viewState) else configureCreationText(viewState)
            }
        }
    }

    private fun configureStartText(viewState: PreviewGuildScheduledEventViewModel.ViewState.Initialized) {
        subtitle.visibility = View.VISIBLE
        subtitle.setText(R.string.start_event_confirmation_no_hook)
        title.text = viewState.eventModel.name
        createSubtitle.visibility = View.GONE
    }

    private fun configureCreationText(viewState: PreviewGuildScheduledEventViewModel.ViewState.Initialized) {
        subtitle.visibility = View.GONE
        (title.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
            params.setMargins(dpToPixels(16), dpToPixels(24), dpToPixels(16), 0)
            title.layoutParams = params
        }
        title.setText(R.string.guild_event_preview_title)
        createSubtitle.visibility = View.VISIBLE
        createSubtitle.text = when (val location = viewState.locationInfo) {
            is GuildScheduledEventLocationInfo.ChannelLocation -> I18nHooks.format(
                requireContext(),
                R.string.guild_event_preview_body,
                args = mapOf("channelName" to location.locationName),
                hooks = mapOf("channelHook" to Hook(text = location.locationName)),
            )
            is GuildScheduledEventLocationInfo.ExternalLocation -> getString(R.string.guild_event_preview_external_event_body)
        }
    }

    enum class Action { EDIT_EVENT, START_EVENT }

    data class ExistingEventData(val guildScheduledEventId: Long, val action: Action) : Serializable

    companion object {
        const val EXTRA_EVENT_CREATED = "INTENT_EXTRA_EVENT_CREATED"
        private const val EXTRA_EVENT_MODEL = "INTENT_EXTRA_EVENT_MODEL"
        private const val EXTRA_EXISTING_EVENT_DATA = "INTENT_EXTRA_EXISTING_EVENT_DATA"
        private const val EXTRA_CHANNEL_ID = "com.discord.intent.extra.EXTRA_CHANNEL_ID"
        private const val EXTRA_GUILD_SCHEDULED_EVENT_ID = "com.discord.intent.extra.EXTRA_GUILD_SCHEDULED_EVENT_ID"
        private const val RESULT_QUIT = 2

        @JvmStatic
        @JvmOverloads
        fun launch(
            context: Context,
            eventModel: GuildScheduledEventModel,
            existingEventData: ExistingEventData? = null,
            launcher: ActivityResultLauncher<Intent>? = null,
            fromSettings: Boolean = false,
        ) {
            val extras = Intent().putExtra(EXTRA_EVENT_MODEL, eventModel).apply {
                existingEventData?.let { putExtra(EXTRA_EXISTING_EVENT_DATA, it) }
            }
            if (launcher == null) AppScreenRouter.launch(context, WidgetPreviewGuildScheduledEvent::class.java, extras)
            else AppScreenRouter.launchForResult(context, launcher, WidgetPreviewGuildScheduledEvent::class.java, extras)
        }

        @JvmStatic
        fun registerForResult(
            fragment: Fragment,
            onEventCreated: (Long?, Long, Boolean) -> Unit,
            onQuit: (() -> Unit)? = null,
        ): ActivityResultLauncher<Intent> = fragment.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            when (result.resultCode) {
                Activity.RESULT_OK -> result.data?.let { data ->
                    onEventCreated(
                        data.takeIf { it.hasExtra(EXTRA_CHANNEL_ID) }?.getLongExtra(EXTRA_CHANNEL_ID, 0L),
                        data.getLongExtra(EXTRA_GUILD_SCHEDULED_EVENT_ID, 0L),
                        data.getBooleanExtra(EXTRA_EVENT_CREATED, false),
                    )
                }
                RESULT_QUIT -> onQuit?.invoke()
            }
        }

        @JvmStatic
        fun createJoinOnStartActivityRegistration(
            fragment: Fragment,
            onEventStarted: () -> Unit,
        ): ActivityResultLauncher<Intent> = fragment.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) onEventStarted()
        }
    }
}

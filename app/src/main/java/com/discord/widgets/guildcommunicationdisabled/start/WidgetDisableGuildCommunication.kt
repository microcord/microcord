package com.discord.widgets.guildcommunicationdisabled.start

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.TextView
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.app.appViewModels
import com.discord.i18n.I18nSpans
import com.discord.utilities.analytics.AnalyticsTracker
import com.discord.utilities.rx.ui
import com.discord.utilities.uri.UriHandler
import com.discord.utilities.user.UserUtils.getUserNameWithDiscriminator
import com.discord.utilities.view.ToastManager
import com.discord.views.CheckedSetting
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputLayout
import java.lang.ref.WeakReference

/**
 * "Time out {member}" — a full screen with a six-way duration radio group, an optional audit-log
 * reason, and a confirm button.
 *
 * The radio group is not a [com.discord.views.RadioManager]: the reference re-binds all six rows on
 * every state emission and derives `isChecked` from the view state, which is what keeps the
 * selection correct across a store rebuild. Reproduced.
 */
class WidgetDisableGuildCommunication : AppFragment(R.layout.widget_disable_guild_communication) {

    private val guildId: Long
        get() = mostRecentIntent.getLongExtra(INTENT_EXTRA_GUILD_ID, -1L)

    private val userId: Long
        get() = mostRecentIntent.getLongExtra(INTENT_EXTRA_USER_ID, -1L)

    private val viewModel: DisableGuildCommunicationViewModel by appViewModels {
        DisableGuildCommunicationViewModel(userId = userId, guildId = guildId)
    }

    private lateinit var subtitle: TextView
    private lateinit var body: TextView
    private lateinit var reason: TextInputLayout
    private lateinit var confirm: MaterialButton
    private lateinit var durationRows: Map<TimeDurationDisabledCommunication, CheckedSetting>

    private val toastManager = ToastManager()

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        subtitle = view.findViewById(R.id.disable_guild_communication_subtitle)
        body = view.findViewById(R.id.disable_guild_communication_body)
        reason = view.findViewById(R.id.disable_guild_communication_reason)
        confirm = view.findViewById(R.id.disable_confirm)
        durationRows = mapOf(
            TimeDurationDisabledCommunication.SECONDS_60 to view.findViewById(R.id.time_unit_60_seconds),
            TimeDurationDisabledCommunication.MINUTES_5 to view.findViewById(R.id.time_unit_5_minutes),
            TimeDurationDisabledCommunication.MINUTES_10 to view.findViewById(R.id.time_unit_10_minutes),
            TimeDurationDisabledCommunication.HOUR_1 to view.findViewById(R.id.time_unit_1_hour),
            TimeDurationDisabledCommunication.DAY_1 to view.findViewById(R.id.time_unit_1_day),
            TimeDurationDisabledCommunication.WEEK_1 to view.findViewById(R.id.time_unit_1_week),
        )

        // The hint doubles as the floating label, so it has to be cleared while the field has focus
        // or the label and the placeholder overlap.
        reason.editText?.setOnFocusChangeListener { _, hasFocus ->
            reason.hint = if (hasFocus) "" else getString(R.string.guild_communication_disabled_reason_hint_android)
        }
    }

    override fun onDestroyView() {
        toastManager.close()
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        viewModel.observeViewState()
            .ui(this)
            .subscribe(::configureUI)
    }

    private fun configureUI(viewState: DisableGuildCommunicationViewModel.ViewState) {
        // ViewState.Invalid leaves the screen exactly as inflated: there is no guild to act on, so
        // no duration row is checked and the confirm button stays unwired.
        if (viewState !is DisableGuildCommunicationViewModel.ViewState.Valid) return

        durationRows.forEach { (duration, row) -> configureDurationOption(viewState, row, duration) }

        body.text = I18nSpans.render(
            getString(R.string.disable_guild_communication_body_header),
            "helpdeskArticle" to TIMEOUT_HELP_URL,
        )
        body.setOnClickListener { UriHandler.handle(body.context, TIMEOUT_HELP_URL) }

        val userName = viewState.user?.getUserNameWithDiscriminator()
        subtitle.text = userName
        subtitle.visibility = if (userName.isNullOrEmpty()) View.GONE else View.VISIBLE

        confirm.setOnClickListener {
            viewModel.onDisableCommunicationConfirm(
                context = WeakReference(requireContext()),
                reason = reason.editText?.text?.toString().orEmpty(),
            ) {
                val ctx = context ?: return@onDisableCommunicationConfirm
                toastManager.show(
                    ctx,
                    I18nSpans.render(
                        ctx.getString(R.string.disable_guild_communication_confirmed),
                        "user" to userName,
                        "duration" to GuildCommunicationDisabledDateUtils
                            .getFriendlyDurationString(ctx, viewState.selectedDurationOption),
                    ),
                )
                appActivity?.finish()
            }
        }
    }

    private fun configureDurationOption(
        viewState: DisableGuildCommunicationViewModel.ViewState.Valid,
        setting: CheckedSetting,
        settingValue: TimeDurationDisabledCommunication,
    ) {
        setting.setChecked(viewState.selectedDurationOption == settingValue)
        setting.setText(GuildCommunicationDisabledDateUtils.getFriendlyDurationString(context, settingValue))
        setting.setContainerClickListener {
            // Picking a duration commits whatever is in the reason field and gets the keyboard out
            // of the way, otherwise the newly checked row is hidden behind the IME.
            reason.clearFocus()
            hideKeyboard()
            viewModel.onDurationLengthSelected(settingValue)
        }
    }

    companion object {
        private const val INTENT_EXTRA_GUILD_ID = "INTENT_EXTRA_GUILD_ID"
        private const val INTENT_EXTRA_USER_ID = "INTENT_EXTRA_USER_ID"

        /**
         * Zendesk article 4413305239191, "Timeout FAQ". The reference builds this through
         * `AppHelpDesk`, which localises the `/hc/<locale>/` segment; that helper does not exist in
         * this project and every other screen hardcodes `en-us`, so this follows suit.
         */
        private const val TIMEOUT_HELP_URL =
            "https://support.discord.com/hc/en-us/articles/4413305239191"

        fun launch(userId: Long, guildId: Long, context: Context) {
            val intent = Intent()
                .putExtra(INTENT_EXTRA_GUILD_ID, guildId)
                .putExtra(INTENT_EXTRA_USER_ID, userId)
            AppScreenRouter.launch(context, WidgetDisableGuildCommunication::class.java, intent)
            AnalyticsTracker.viewedDisableCommunicationModal(guildId, userId)
        }
    }
}

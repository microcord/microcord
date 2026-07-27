package com.discord.widgets.user

import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppDialog
import com.discord.app.appViewModels
import com.discord.i18n.i18nFormat
import com.discord.i18n.i18nPlural
import com.discord.i18n.setI18nText
import com.discord.utilities.error.RestApiError
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.bindToComponentLifecycle
import com.discord.utilities.view.extensions.disable
import com.discord.views.CheckedSetting
import com.discord.views.RadioManager
import com.discord.widgets.user.WidgetPruneUsersViewModel.Event
import com.discord.widgets.user.WidgetPruneUsersViewModel.PruneDays
import com.discord.widgets.user.WidgetPruneUsersViewModel.ViewState
import com.google.android.material.button.MaterialButton

/**
 * "Prune inactive members" — pick an inactivity window, see how many members that would remove, and
 * confirm.
 *
 * The count is the whole point of the dialog: pruning is irreversible and unbounded, so the button
 * stays disabled until the server has said how many people it would affect, and it becomes disabled
 * again the moment the window changes.
 */
class WidgetPruneUsers : AppDialog(R.layout.widget_prune_users) {

    private lateinit var header: TextView
    private lateinit var estimateText: TextView
    private lateinit var loadingProgress: ProgressBar
    private lateinit var pruneButton: MaterialButton
    private lateinit var sevenDaysRadio: CheckedSetting
    private lateinit var thirtyDaysRadio: CheckedSetting

    private val radioManager: RadioManager by lazy {
        RadioManager(listOf(sevenDaysRadio, thirtyDaysRadio))
    }

    private val viewModel: WidgetPruneUsersViewModel by appViewModels {
        WidgetPruneUsersViewModel(argumentsOrDefault.getLong(ARG_GUILD_ID, -1L))
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        header = view.findViewById(R.id.prune_user_header)
        estimateText = view.findViewById(R.id.prune_users_estimate_text)
        loadingProgress = view.findViewById(R.id.prune_users_loading_progressbar)
        pruneButton = view.findViewById(R.id.prune_user_prune)
        sevenDaysRadio = view.findViewById(R.id.prune_users_history_1_radio)
        thirtyDaysRadio = view.findViewById(R.id.prune_users_history_2_radio)

        sevenDaysRadio.setText(formatLastSeenDays(view, PruneDays.Seven.count))
        sevenDaysRadio.setContainerClickListener { _ -> viewModel.pruneDaysSelected(PruneDays.Seven) }

        thirtyDaysRadio.setText(formatLastSeenDays(view, PruneDays.Thirty.count))
        thirtyDaysRadio.setContainerClickListener { _ -> viewModel.pruneDaysSelected(PruneDays.Thirty) }

        view.findViewById<MaterialButton>(R.id.prune_user_cancel).setOnClickListener { dismiss() }
        pruneButton.setOnClickListener { viewModel.pruneClicked() }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState()
            .distinctUntilChanged()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetPruneUsers::class.java) { state -> updateUI(state) }
        viewModel.observeEvents()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetPruneUsers::class.java) { event -> handleEvent(event) }
    }

    private fun updateUI(state: ViewState) = when (state) {
        is ViewState.Loading -> {
            header.text = "${getString(R.string.prune_members)} - ${state.guildName}"
            // The window can be chosen by the view model (its default) as well as by the user, so
            // the radio group follows the state rather than the tap.
            radioManager.setChecked(radioFor(state.whichPruneDays))
            loadingProgress.visibility = View.VISIBLE
            estimateText.visibility = View.GONE
            pruneButton.disable()
        }

        is ViewState.Loaded -> {
            loadingProgress.visibility = View.GONE
            // The copy bolds both numbers, so it goes through the markdown-aware renderer.
            estimateText.setI18nText(
                R.string.form_help_last_seen_1,
                "members" to requireContext().i18nPlural(
                    R.plurals.form_help_last_seen_1_members,
                    state.pruneCount,
                    "members" to state.pruneCount,
                ),
                "days" to requireContext().i18nPlural(
                    R.plurals.form_help_last_seen_1_days,
                    state.pruneDays.count,
                    "days" to state.pruneDays.count,
                ),
            )
            estimateText.visibility = View.VISIBLE
            pruneButton.isEnabled = state.pruneButtonEnabled
        }

        is ViewState.LoadFailed ->
            if (state.dismiss) {
                dismiss()
            } else {
                // The estimate is unknown, so the dialog stays open with nothing to confirm; the
                // failure itself is reported through Event.RestClientFailed.
                loadingProgress.visibility = View.GONE
                estimateText.visibility = View.GONE
                pruneButton.disable()
            }
    }

    private fun handleEvent(event: Event) = when (event) {
        is Event.Dismiss -> dismiss()
        is Event.RestClientFailed -> RestApiError.handle(event.throwable, "restClient", null, context)
    }

    private fun radioFor(pruneDays: PruneDays): CheckedSetting = when (pruneDays) {
        PruneDays.Seven -> sevenDaysRadio
        PruneDays.Thirty -> thirtyDaysRadio
    }

    /** "more than 7 days" / "more than 30 days" — the label on each radio row. */
    private fun formatLastSeenDays(view: View, days: Int): CharSequence = view.i18nFormat(
        R.string.last_seen,
        "days" to view.context.i18nPlural(R.plurals.last_seen_days, days, "days" to days),
    )

    companion object {
        private const val ARG_GUILD_ID = "ARG_GUILD_ID"

        @JvmStatic
        fun create(guildId: Long, fragmentManager: FragmentManager) {
            WidgetPruneUsers()
                .apply { arguments = Bundle().apply { putLong(ARG_GUILD_ID, guildId) } }
                .show(fragmentManager, WidgetPruneUsers::class.java.name)
        }
    }
}

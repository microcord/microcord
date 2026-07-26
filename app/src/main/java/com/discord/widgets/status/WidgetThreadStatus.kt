package com.discord.widgets.status

import android.content.res.ColorStateList
import android.view.View
import android.widget.TextView
import androidx.annotation.MainThread
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.appViewModels
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.bindToComponentLifecycle
import com.discord.utilities.view.ToastManager
import com.discord.views.LoadingButton
import com.discord.views.ThemedColor
import com.discord.widgets.status.WidgetThreadStatusViewModel.Status
import com.discord.widgets.status.WidgetThreadStatusViewModel.ViewState

/**
 * Banner above the chat list for a thread the user has not joined, or that has been archived.
 *
 * All decisions live in [WidgetThreadStatusViewModel]; this fragment only renders the three
 * [Status] shapes and forwards the button tap back.
 */
class WidgetThreadStatus : AppFragment(R.layout.widget_thread_status) {

    /** One-shot signals that have no place in the rendered state. */
    sealed class Event {
        data class Error(val code: Int) : Event()
    }

    private val viewModel: WidgetThreadStatusViewModel by appViewModels { WidgetThreadStatusViewModel() }
    private val toastManager = ToastManager()

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetThreadStatus::class.java) { updateView(it) }
        viewModel.observeEvents()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetThreadStatus::class.java) { handleEvent(it) }
    }

    override fun onDestroyView() {
        toastManager.close()
        super.onDestroyView()
    }

    private fun handleEvent(event: Event) {
        when (event) {
            // The reference maps the abort code to a specific message through
            // `RestAPIAbortMessages`, which this project has not reconstructed yet.
            is Event.Error -> toastManager.show(requireContext(), R.string.network_error_bad_request)
        }
    }

    @MainThread
    private fun updateView(viewState: ViewState) {
        val view = view ?: return
        val root = view.findViewById<View>(R.id.thread_status)
        val button = view.findViewById<LoadingButton>(R.id.thread_status_button)
        val text = view.findViewById<TextView>(R.id.thread_status_text)
        button.setIsLoading(viewState.isLoading)

        when (val status = viewState.threadStatus) {
            is Status.Hide -> root.visibility = View.GONE

            is Status.Archived -> {
                root.visibility = View.VISIBLE
                button.visibility = if (status.canArchive) View.VISIBLE else View.GONE
                text.setText(
                    if (status.isLocked) R.string.thread_header_notice_locked
                    else R.string.thread_header_notice_archived,
                )
                button.setText(getString(R.string.unarchive))
                button.backgroundTintList = ColorStateList.valueOf(
                    ThemedColor.resolve(requireContext(), R.attr.colorBackgroundAccent),
                )
                button.setOnClickListener { viewModel.onUnarchiveTapped() }
            }

            is Status.Unjoined -> {
                root.visibility = View.VISIBLE
                button.visibility = View.VISIBLE
                text.setText(R.string.thread_header_notice_join)
                button.setText(getString(R.string.join))
                button.backgroundTintList = ColorStateList.valueOf(
                    ThemedColor.resolve(requireContext(), R.attr.color_brand_500),
                )
                button.setOnClickListener { viewModel.onJoinTapped() }
            }
        }
    }
}

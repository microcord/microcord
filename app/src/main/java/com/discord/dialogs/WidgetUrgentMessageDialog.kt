package com.discord.dialogs

import android.view.View
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppDialog
import com.discord.app.AppToast
import com.discord.app.AppViewModel
import com.discord.app.appViewModels
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreStream
import com.discord.utilities.channel.ChannelSelector
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.bindToComponentLifecycle
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.user.UserUtils
import com.discord.views.LoadingButton
import rx.Observable
import rx.subjects.PublishSubject

class WidgetUrgentMessageDialog : AppDialog(R.layout.widget_urgent_message_dialog) {
    private val viewModel: WidgetUrgentMessageDialogViewModel by appViewModels {
        WidgetUrgentMessageDialogViewModel()
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setCancelable(false)

        view.findViewById<TextView>(R.id.view_dialog_confirmation_header)
            .setText(R.string.system_dm_urgent_message_modal_header)
        view.findViewById<TextView>(R.id.view_dialog_confirmation_text)
            .setText(R.string.system_dm_urgent_message_modal_body)
        view.findViewById<LoadingButton>(R.id.view_dialog_confirmation_confirm).apply {
            setText(getString(R.string.okay))
            setOnClickListener {
                viewModel.acknowledge {
                    ChannelSelector.getInstance()
                        .findAndSetDirectMessage(requireContext(), UserUtils.SYSTEM_USER_ID)
                }
            }
        }

    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetUrgentMessageDialog::class.java) { state ->
                view?.findViewById<LoadingButton>(R.id.view_dialog_confirmation_confirm)
                    ?.setIsLoading(state.isBusy)
            }
        viewModel.observeEvents()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetUrgentMessageDialog::class.java) { event ->
                when (event) {
                    WidgetUrgentMessageDialogViewModel.Event.Dismiss -> dismiss()
                    WidgetUrgentMessageDialogViewModel.Event.Failed ->
                        AppToast.show(this, R.string.internal_server_error)
                }
            }
    }

    override fun onDestroy() {
        StoreStream.getNotices().markDialogSeen(NOTICE_NAME)
        super.onDestroy()
    }

    companion object {
        const val NOTICE_NAME = "URGENT_MESSAGE_DIALOG"

        fun show(fragmentManager: FragmentManager) {
            WidgetUrgentMessageDialog().show(fragmentManager, WidgetUrgentMessageDialog::class.java.simpleName)
        }
    }
}

class WidgetUrgentMessageDialogViewModel(
    userFlagsObservable: Observable<Int?> = StoreStream.getUsers().observeMe()
        .map<Int?> { it.flags }
        .computationLatest()
        .ui(),
    private val updateFlags: (Int) -> Observable<Unit> = { flags ->
        RestAPI.api.patchUser(RestAPIParams.UserInfo(flags = flags))
            .restSubscribeOn()
            .ui()
            .map { Unit }
    },
) : AppViewModel<WidgetUrgentMessageDialogViewModel.ViewState>() {
    data class ViewState(val userFlags: Int?, val isBusy: Boolean)

    sealed class Event {
        object Dismiss : Event()
        object Failed : Event()
    }

    private val events = PublishSubject.create<Event>()

    init {
        userFlagsObservable
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetUrgentMessageDialogViewModel::class.java) { flags ->
                updateViewState(ViewState(flags, viewState?.isBusy == true))
            }
    }

    fun observeEvents(): Observable<Event> = events

    fun acknowledge(openSystemDm: () -> Unit) {
        openSystemDm()
        val flags = viewState?.userFlags ?: return
        updateViewState(ViewState(flags, isBusy = true))
        updateFlags(clearUrgentMessageFlag(flags))
            .bindToComponentLifecycle(this)
            .appSubscribe(
                errorClass = WidgetUrgentMessageDialogViewModel::class.java,
                errorHandler = { events.onNext(Event.Failed) },
            ) { events.onNext(Event.Dismiss) }
    }

    companion object {
        private const val URGENT_MESSAGE_FLAG = 8192

        internal fun clearUrgentMessageFlag(flags: Int): Int = flags and URGENT_MESSAGE_FLAG.inv()
    }
}

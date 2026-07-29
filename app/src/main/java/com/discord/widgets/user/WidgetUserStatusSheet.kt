package com.discord.widgets.user

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.discord.app.AppBottomSheet
import com.discord.app.appViewModels
import com.discord.R
import com.discord.api.presence.ClientStatus
import com.discord.widgets.user.profile.UserStatusPresenceCustomView
import rx.Observable
import rx.subscriptions.CompositeSubscription

class WidgetUserStatusSheet : AppBottomSheet() {
    data class State(val customStatus: UserStatusPresenceCustomView.ViewState)

    private val viewModel: WidgetUserStatusSheetViewModel by appViewModels {
        WidgetUserStatusSheetViewModel()
    }

    override fun getContentViewResId() = R.layout.widget_user_status_update

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindStatus(view, R.id.user_status_update_online, R.drawable.ic_status_online_16dp, R.string.status_online, null, ClientStatus.ONLINE)
        bindStatus(view, R.id.user_status_update_idle, R.drawable.ic_status_idle_16dp, R.string.status_idle, null, ClientStatus.IDLE)
        bindStatus(view, R.id.user_status_update_dnd, R.drawable.ic_status_dnd_16dp, R.string.status_dnd, R.string.status_dnd_help, ClientStatus.DND)
        bindStatus(view, R.id.user_status_update_invisible, R.drawable.ic_status_invisible_16dp, R.string.status_invisible, R.string.status_invisible_helper, ClientStatus.INVISIBLE)
        view.findViewById<UserStatusPresenceCustomView>(R.id.user_status_update_custom).apply {
            setOnClickListener { onCustomStatus?.invoke(); dismiss() }
            setOnClear(viewModel::clearCustomStatus)
        }
    }

    override fun bindSubscriptions(compositeSubscription: CompositeSubscription) {
        super.bindSubscriptions(compositeSubscription)
        compositeSubscription.add(
            viewModel.observeViewState().subscribe { state ->
                if (state is WidgetUserStatusSheetViewModel.ViewState.Loaded) {
                    view?.findViewById<UserStatusPresenceCustomView>(R.id.user_status_update_custom)
                        ?.updateViewState(state.customStatusViewState)
                }
            },
        )
    }

    private fun bindStatus(root: View, id: Int, icon: Int, title: Int, subtitle: Int?, status: ClientStatus) {
        root.findViewById<View>(id).apply {
            findViewById<ImageView>(R.id.user_status_presence_dot).setImageResource(icon)
            findViewById<TextView>(R.id.user_status_presence_title).setText(title)
            findViewById<TextView>(R.id.user_status_presence_subtitle).apply {
                visibility = if (subtitle == null) View.GONE else View.VISIBLE
                subtitle?.let(::setText)
            }
            setOnClickListener { viewModel.setStatus(status); dismiss() }
        }
    }

    companion object {
        @JvmField var stateSource: (() -> Observable<State>)? = null
        @JvmField var onStatusSelected: ((ClientStatus) -> Unit)? = null
        @JvmField var onCustomStatus: (() -> Unit)? = null
        @JvmField var onClearCustomStatus: (() -> Unit)? = null
        fun show(fragmentManager: androidx.fragment.app.FragmentManager) =
            WidgetUserStatusSheet().show(fragmentManager, WidgetUserStatusSheet::class.java.name)
    }
}

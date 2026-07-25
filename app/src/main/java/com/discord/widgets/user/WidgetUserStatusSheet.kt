package com.discord.widgets.user

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.discord.R
import com.discord.api.presence.ClientStatus
import com.discord.models.domain.emoji.ModelEmojiUnicode
import com.discord.widgets.user.profile.UserStatusPresenceCustomView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import rx.Observable
import rx.Subscription

class WidgetUserStatusSheet : BottomSheetDialogFragment() {
    data class State(val customStatus: UserStatusPresenceCustomView.ViewState)
    private var subscription: Subscription? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        inflater.inflate(R.layout.widget_user_status_update, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindStatus(view, R.id.user_status_update_online, R.drawable.ic_status_online_16dp, R.string.status_online, null, ClientStatus.ONLINE)
        bindStatus(view, R.id.user_status_update_idle, R.drawable.ic_status_idle_16dp, R.string.status_idle, null, ClientStatus.IDLE)
        bindStatus(view, R.id.user_status_update_dnd, R.drawable.ic_status_dnd_16dp, R.string.status_dnd, R.string.status_dnd_help, ClientStatus.DND)
        bindStatus(view, R.id.user_status_update_invisible, R.drawable.ic_status_invisible_16dp, R.string.status_invisible, R.string.status_invisible_helper, ClientStatus.INVISIBLE)
        view.findViewById<UserStatusPresenceCustomView>(R.id.user_status_update_custom).apply {
            setOnClickListener { onCustomStatus?.invoke(); dismiss() }
            setOnClear { onClearCustomStatus?.invoke() }
        }
    }

    override fun onStart() {
        super.onStart()
        subscription = (stateSource?.invoke() ?: Observable.just(defaultState())).subscribe { state ->
            view?.post { view?.findViewById<UserStatusPresenceCustomView>(R.id.user_status_update_custom)?.updateViewState(state.customStatus) }
        }
    }

    override fun onStop() { subscription?.unsubscribe(); subscription = null; super.onStop() }

    private fun bindStatus(root: View, id: Int, icon: Int, title: Int, subtitle: Int?, status: ClientStatus) {
        root.findViewById<View>(id).apply {
            findViewById<ImageView>(R.id.user_status_presence_dot).setImageResource(icon)
            findViewById<TextView>(R.id.user_status_presence_title).setText(title)
            findViewById<TextView>(R.id.user_status_presence_subtitle).apply {
                visibility = if (subtitle == null) View.GONE else View.VISIBLE
                subtitle?.let(::setText)
            }
            setOnClickListener { onStatusSelected?.invoke(status); dismiss() }
        }
    }

    companion object {
        @JvmField var stateSource: (() -> Observable<State>)? = null
        @JvmField var onStatusSelected: ((ClientStatus) -> Unit)? = null
        @JvmField var onCustomStatus: (() -> Unit)? = null
        @JvmField var onClearCustomStatus: (() -> Unit)? = null
        private fun defaultState() = State(UserStatusPresenceCustomView.ViewState.WithPlaceholder(ModelEmojiUnicode(surrogates = "💬")))
        fun show(fragmentManager: androidx.fragment.app.FragmentManager) =
            WidgetUserStatusSheet().show(fragmentManager, WidgetUserStatusSheet::class.java.name)
    }
}

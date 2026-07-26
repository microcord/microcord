package com.discord.widgets.status

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.discord.app.AppFragment
import androidx.lifecycle.Lifecycle
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.stores.StoreStream
import com.discord.views.LoadingButton
import rx.Observable
import rx.Subscription

class WidgetThreadStatus : AppFragment(R.layout.widget_thread_status) {
    data class State(val channel: Channel?, val joined: Boolean? = null, val loading: Boolean = false)

    private var subscription: Subscription? = null
    private var state = State(null)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<LoadingButton>(R.id.thread_status_button).setOnClickListener {
            val channel = state.channel ?: return@setOnClickListener
            if (channel.threadMetadata?.archived == true) onUnarchive?.invoke(channel) else onJoin?.invoke(channel)
        }
    }

    override fun onStart() {
        super.onStart()
        subscription = (stateSource?.invoke() ?: StoreStream.getChannelsSelected().observeSelectedChannel().map {
            State(it, it?.member?.let { true })
        }).distinctUntilChanged().subscribe { next ->
            view?.post {
                if (view != null && viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) show(next)
            }
        }
    }

    override fun onStop() {
        subscription?.unsubscribe()
        subscription = null
        super.onStop()
    }

    private fun show(next: State) {
        state = next
        val channel = next.channel
        val metadata = channel?.threadMetadata
        val archived = metadata?.archived == true
        val visible = channel?.type in Channel.ANNOUNCEMENT_THREAD..Channel.PRIVATE_THREAD &&
            (archived || next.joined == false)
        val root = requireView().findViewById<View>(R.id.thread_status)
        root.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) return

        val button = requireView().findViewById<LoadingButton>(R.id.thread_status_button)
        button.setIsLoading(next.loading)
        requireView().findViewById<TextView>(R.id.thread_status_text).setText(
            when {
                metadata?.locked == true -> R.string.thread_header_notice_locked
                archived -> R.string.thread_header_notice_archived
                else -> R.string.thread_header_notice_join
            },
        )
        button.setText(getString(if (archived) R.string.unarchive else R.string.join))
        button.visibility = if ((archived && onUnarchive != null) || (!archived && onJoin != null)) View.VISIBLE else View.GONE
    }

    companion object {
        @JvmField var stateSource: (() -> Observable<State>)? = null
        @JvmField var onJoin: ((Channel) -> Unit)? = null
        @JvmField var onUnarchive: ((Channel) -> Unit)? = null
    }
}

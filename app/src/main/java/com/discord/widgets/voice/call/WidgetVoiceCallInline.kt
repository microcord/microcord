package com.discord.widgets.voice.call

import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import com.discord.R
import rx.Observable
import rx.Subscription

class WidgetVoiceCallInline : Fragment(R.layout.widget_voice_call_inline) {
    data class State(val activeCall: Boolean = false, val pushToTalk: Boolean = false)

    private var subscription: Subscription? = null
    private var state = State()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<View>(R.id.voice_connected_ptt).apply {
            isEnabled = onPushToTalkChanged != null
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> onPushToTalkChanged?.invoke(true)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onPushToTalkChanged?.invoke(false)
                }
                onPushToTalkChanged != null
            }
        }
    }

    override fun onStart() {
        super.onStart()
        subscription = (stateSource?.invoke() ?: Observable.just(State())).distinctUntilChanged().subscribe { next ->
            state = next
            view?.post(::updateVisibility)
        }
    }

    override fun onStop() {
        onPushToTalkChanged?.invoke(false)
        subscription?.unsubscribe()
        subscription = null
        view?.visibility = View.GONE
        super.onStop()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        updateVisibility()
    }

    private fun updateVisibility() {
        val root = view ?: return
        val started = viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        root.visibility = if (started && !isHidden && state.activeCall && state.pushToTalk) View.VISIBLE else View.GONE
    }

    companion object {
        @JvmField var stateSource: (() -> Observable<State>)? = null
        @JvmField var onPushToTalkChanged: ((Boolean) -> Unit)? = null
    }
}

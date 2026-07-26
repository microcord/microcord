package com.discord.widgets.chat.overlay

import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.discord.app.AppFragment
import androidx.lifecycle.Lifecycle
import com.discord.R
import com.discord.stores.StoreStream
import com.discord.views.typing.TypingDots
import rx.Observable
import rx.Subscription

class WidgetChatOverlay : AppFragment(R.layout.widget_chat_overlay) {
    data class State(
        val channelId: Long = 0,
        val viewingOldMessages: Boolean = false,
        val typingUsers: List<String> = emptyList(),
        val slowmodeSeconds: Int = 0,
        val channelRateLimit: Int = 0,
    )

    private var subscription: Subscription? = null
    private var currentState: State? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<View>(R.id.stickers_suggestions).visibility = View.GONE
        view.findViewById<View>(R.id.chat_overlay_old_messages_fab).setOnClickListener {
            currentState?.takeIf { it.channelId > 0 }?.let { onJumpToPresent?.invoke(it.channelId) }
        }
    }

    override fun onStart() {
        super.onStart()
        subscription = (stateSource?.invoke() ?: StoreStream.getChannelsSelected().observeId().map { State(it) })
            .distinctUntilChanged().subscribe { state ->
                view?.post {
                    if (view != null && viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) show(state)
                }
            }
    }

    override fun onStop() {
        subscription?.unsubscribe()
        subscription = null
        currentState = null
        view?.findViewById<TypingDots>(R.id.chat_overlay_typing_dots)?.stop()
        super.onStop()
    }

    private fun show(state: State) {
        currentState = state
        requireView().findViewById<View>(R.id.chat_overlay_old_messages_fab).apply {
            visibility = if (state.viewingOldMessages) View.VISIBLE else View.GONE
            isEnabled = onJumpToPresent != null
        }

        val hasTyping = state.typingUsers.isNotEmpty()
        val hasSlowmode = state.slowmodeSeconds > 0 || state.channelRateLimit > 0
        requireView().findViewById<View>(R.id.chat_overlay_typing).visibility =
            if (hasTyping || hasSlowmode) View.VISIBLE else View.GONE
        val dots = requireView().findViewById<TypingDots>(R.id.chat_overlay_typing_dots)
        dots.visibility = if (hasTyping) View.VISIBLE else View.GONE
        if (hasTyping) dots.start() else dots.stop()

        requireView().findViewById<TextView>(R.id.chat_typing_users_typing).apply {
            text = typingText(state.typingUsers)
            visibility = if (hasTyping) View.VISIBLE else View.GONE
        }
        val slowmodeText = when {
            state.slowmodeSeconds > 0 -> DateUtils.formatElapsedTime(state.slowmodeSeconds.toLong())
            state.channelRateLimit > 0 && !hasTyping -> getString(R.string.channel_slowmode_desc_short)
            else -> ""
        }
        requireView().findViewById<TextView>(R.id.chat_typing_users_slowmode).apply {
            text = slowmodeText
            visibility = if (slowmodeText.isNotEmpty()) View.VISIBLE else View.GONE
        }
        requireView().findViewById<ImageView>(R.id.chat_typing_users_slowmode_icon).visibility =
            if (hasSlowmode) View.VISIBLE else View.GONE
    }

    private fun typingText(users: List<String>): String {
        val template = getString(
            when (users.size) {
                1 -> R.string.one_user_typing
                2 -> R.string.two_users_typing
                3 -> R.string.three_users_typing
                else -> R.string.several_users_typing
            },
        )
        return template.replace("!!{a}!!", users.getOrNull(0).orEmpty())
            .replace("!!{b}!!", users.getOrNull(1).orEmpty())
            .replace("!!{c}!!", users.getOrNull(2).orEmpty())
            .replace("**", "")
    }

    companion object {
        @JvmField var stateSource: (() -> Observable<State>)? = null
        @JvmField var onJumpToPresent: ((Long) -> Unit)? = null
    }
}

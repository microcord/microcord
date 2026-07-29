package com.discord.widgets.chat.overlay

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import com.discord.R
import com.discord.app.AppFragment
import com.discord.stores.StoreStream
import com.discord.utilities.time.TimeUtils
import com.discord.views.typing.TypingDots
import rx.Observable
import rx.subscriptions.CompositeSubscription
import java.util.concurrent.TimeUnit

class WidgetChatOverlay : AppFragment(R.layout.widget_chat_overlay) {
    data class State(
        val channelId: Long = 0L,
        val viewingOldMessages: Boolean = false,
        val typingUsers: List<String> = emptyList(),
        val slowmodeSeconds: Int = 0,
        val channelRateLimit: Int = 0,
    )

    data class OldMessageModel(val channelId: Long, val isViewingOldMessages: Boolean) {
        companion object {
            fun get(): Observable<OldMessageModel> = StoreStream.getChannelsSelected().observeId()
                .distinctUntilChanged()
                .switchMap { channelId ->
                    val viewing = if (channelId <= 0L) {
                        Observable.just(false)
                    } else {
                        Observable.combineLatest(
                            StoreStream.getMessages().observeIsDetached(channelId),
                            StoreStream.getChat().observeInteractionState()
                                .debounce(200L, TimeUnit.MILLISECONDS),
                        ) { detached, interaction ->
                            detached || interaction.channelId == channelId &&
                                !interaction.isAtBottomIgnoringTouch() &&
                                !interaction.isNearBottomIgnoringTouch()
                        }
                    }
                    Observable.concat(Observable.just(false), viewing)
                        .distinctUntilChanged()
                        .map { OldMessageModel(channelId, it) }
                }
        }
    }

    private var subscriptions = CompositeSubscription()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<View>(R.id.chat_overlay_old_messages_fab).setOnClickListener {
            StoreStream.getMessagesLoader().requestNewestMessages()
        }
    }

    override fun onStart() {
        super.onStart()
        subscriptions = CompositeSubscription(
            OldMessageModel.get().distinctUntilChanged().subscribe { model ->
                postIfStarted { showOldMessages(model) }
            },
            ChatTypingModel.get().distinctUntilChanged().subscribe { model ->
                postIfStarted { showTyping(model) }
            },
        )
    }

    override fun onStop() {
        subscriptions.unsubscribe()
        requireView().findViewById<TypingDots>(R.id.chat_overlay_typing_dots).stop()
        super.onStop()
    }

    private fun postIfStarted(action: () -> Unit) {
        view?.post {
            if (viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) action()
        }
    }

    private fun showOldMessages(model: OldMessageModel) {
        requireView().findViewById<View>(R.id.chat_overlay_old_messages_fab).visibility =
            if (model.isViewingOldMessages) View.VISIBLE else View.GONE
    }

    private fun showTyping(model: ChatTypingModel) {
        val typing = model as? ChatTypingModel.Typing
        if (typing == null || typing.typingUsers.isEmpty() && typing.channelRateLimit <= 0) {
            requireView().findViewById<TypingDots>(R.id.chat_overlay_typing_dots).stop()
            requireView().findViewById<View>(R.id.chat_overlay_typing).visibility = View.GONE
            return
        }

        val users = typing.typingUsers
        val hasTyping = users.isNotEmpty()
        requireView().findViewById<View>(R.id.chat_overlay_typing).visibility = View.VISIBLE
        requireView().findViewById<TypingDots>(R.id.chat_overlay_typing_dots).apply {
            visibility = if (hasTyping) View.VISIBLE else View.GONE
            if (hasTyping) start() else stop()
        }
        requireView().findViewById<TextView>(R.id.chat_typing_users_typing).apply {
            text = typingText(users)
            visibility = if (hasTyping) View.VISIBLE else View.GONE
        }
        val slowmodeText = when {
            typing.cooldownSecs > 0 -> TimeUtils.toFriendlyStringSimple(typing.cooldownSecs * 1000L)
            typing.channelRateLimit > 0 && !hasTyping -> getString(R.string.channel_slowmode_desc_short)
            else -> ""
        }
        requireView().findViewById<TextView>(R.id.chat_typing_users_slowmode).apply {
            text = slowmodeText
            visibility = if (slowmodeText.isNotEmpty()) View.VISIBLE else View.GONE
        }
        requireView().findViewById<ImageView>(R.id.chat_typing_users_slowmode_icon).visibility =
            if (typing.channelRateLimit > 0) View.VISIBLE else View.GONE
    }

    private fun typingText(users: List<CharSequence>): CharSequence = formatTypingText(
        getString(
            when (users.size) {
                1 -> R.string.one_user_typing
                2 -> R.string.two_users_typing
                3 -> R.string.three_users_typing
                else -> R.string.several_users_typing
            },
        ),
        users,
    )

    companion object {
        // Kept until StoreStream's legacy widget installation is removed in its owning wave.
        @JvmField var stateSource: (() -> Observable<State>)? = null
        @JvmField var onJumpToPresent: (() -> Unit)? = null

        internal fun formatTypingText(template: String, users: List<CharSequence>): String =
            template.replace("!!{a}!!", users.getOrNull(0)?.toString().orEmpty())
                .replace("!!{b}!!", users.getOrNull(1)?.toString().orEmpty())
                .replace("!!{c}!!", users.getOrNull(2)?.toString().orEmpty())
                .replace("**", "")
    }
}

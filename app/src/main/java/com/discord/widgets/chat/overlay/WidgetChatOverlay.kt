package com.discord.widgets.chat.overlay

import android.os.Bundle
import android.text.Spanned
import android.view.View
import androidx.appcompat.content.res.AppCompatResources
import androidx.lifecycle.Lifecycle
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.appActivityViewModels
import com.discord.databinding.WidgetChatOverlayBinding
import com.discord.models.guild.UserGuildMember
import com.discord.stores.StoreStream
import com.discord.utilities.feature.FeatureFlags
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.spans.ClickableSpan
import com.discord.utilities.images.setVitoSpan
import com.discord.utilities.time.TimeUtils
import com.facebook.drawee.drawable.ScalingUtils
import com.facebook.drawee.span.DraweeSpanStringBuilder
import rx.Observable
import rx.subscriptions.CompositeSubscription
import java.util.concurrent.TimeUnit
import com.discord.i18n.FormatUtils
import com.discord.i18n.I18nSpans
import com.discord.utilities.color.ColorCompat
import com.discord.widgets.chat.input.AppFlexInputViewModel
import com.discord.widgets.user.usersheet.WidgetUserSheet

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

    private var _binding: WidgetChatOverlayBinding? = null
    private val binding get() = requireNotNull(_binding)
    private var subscriptions = CompositeSubscription()
    private val flexInputViewModel: AppFlexInputViewModel by appActivityViewModels { AppFlexInputViewModel() }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = WidgetChatOverlayBinding.bind(view)
        binding.chatOverlayOldMessagesFab.setOnClickListener {
            StoreStream.getMessagesLoader().requestNewestMessages()
        }
    }

    override fun onStart() {
        super.onStart()
        subscriptions = CompositeSubscription(
            OldMessageModel.get().distinctUntilChanged().subscribe { model ->
                postIfStarted { showOldMessages(model) }
            },
            Observable.combineLatest(
                ChatTypingModel.get().distinctUntilChanged(),
                flexInputViewModel.observeMessageLimitCounter(),
            ) { typing, counter -> typing to counter }
                .distinctUntilChanged()
                .subscribe { (typing, counter) ->
                    postIfStarted { renderTypingBar(typing, counter) }
            },
        )
    }

    override fun onStop() {
        subscriptions.unsubscribe()
        binding.chatOverlayTypingDots.stop()
        super.onStop()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun postIfStarted(action: () -> Unit) {
        val postedView = view ?: return
        postedView.post {
            val owner = viewLifecycleOwnerLiveData.value ?: return@post
            if (view === postedView && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) action()
        }
    }

    private fun showOldMessages(model: OldMessageModel) {
        binding.chatOverlayOldMessagesFab.visibility =
            if (model.isViewingOldMessages) View.VISIBLE else View.GONE
    }

    private fun renderTypingBar(
        typingModel: ChatTypingModel,
        messageLimitCounter: AppFlexInputViewModel.MessageLimitCounter,
    ) {
        val typing = typingModel as? ChatTypingModel.Typing
        if (!shouldShowStatusBar(typingModel.showsBar, messageLimitCounter.visible)) {
            binding.chatOverlayTypingDots.stop()
            binding.chatOverlayTyping.visibility = View.GONE
            return
        }

        val users = typing?.typingUsers.orEmpty()
        val hasTyping = users.isNotEmpty()
        binding.chatOverlayTyping.visibility = View.VISIBLE
        binding.chatOverlayTypingDots.apply {
            visibility = if (hasTyping) View.VISIBLE else View.GONE
            if (hasTyping) start() else stop()
        }
        binding.chatTypingUsersTyping.apply {
            if (hasTyping) setDraweeSpanStringBuilder(typingText(users))
            visibility = if (hasTyping) View.VISIBLE else View.INVISIBLE
            // Past three the bar stops naming people, so the names move to a sheet.
            setOnClickListener { WidgetTypingUsersSheet.show(parentFragmentManager) }
            isClickable = users.size > 3
        }
        val slowmodeText = when {
            typing != null && typing.cooldownSecs > 0 ->
                TimeUtils.toFriendlyStringSimple(typing.cooldownSecs * 1000L)
            typing != null && typing.channelRateLimit > 0 && !hasTyping ->
                getString(R.string.channel_slowmode_desc_short)
            else -> ""
        }
        binding.chatTypingUsersSlowmode.apply {
            text = slowmodeText
            visibility = if (slowmodeText.isNotEmpty()) View.VISIBLE else View.GONE
        }
        binding.chatTypingUsersSlowmodeIcon.visibility =
            if ((typing?.channelRateLimit ?: 0) > 0) View.VISIBLE else View.GONE
        binding.chatTypingMessageLimitCounter.apply {
            text = messageLimitCounter.text
            visibility = if (messageLimitCounter.visible) View.VISIBLE else View.GONE
            setTextColor(
                if (messageLimitCounter.overLimit) {
                    ColorCompat.getColor(context, R.color.status_red_500)
                } else {
                    ColorCompat.getThemedColor(context, R.attr.colorTextMuted)
                },
            )
        }
    }

    private fun typingText(users: List<UserGuildMember>): DraweeSpanStringBuilder {
        val names = users.map { it.getNickOrUserName() }
        val rendered = I18nSpans.render(
            formatTypingText(
                getString(
                    when (users.size) {
                        1 -> R.string.one_user_typing
                        2 -> R.string.two_users_typing
                        3 -> R.string.three_users_typing
                        else -> R.string.several_users_typing
                    },
                ),
                names,
            ),
        )
        val builder = DraweeSpanStringBuilder(rendered)
        var searchFrom = 0
        val positions = names.map { name ->
            builder.indexOf(name.toString(), searchFrom).also { index ->
                if (index >= 0) searchFrom = index + name.length
            }
        }
        positions.forEachIndexed { index, position ->
            if (position < 0) return@forEachIndexed
            val userId = users[index].user.id
            builder.setSpan(
                ClickableSpan(color = null, underline = false) { openProfile(userId) },
                position,
                position + names[index].length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        if (!FeatureFlags.typingUserAvatarsEnabled() || users.size > 3) return builder

        val size = (16 * resources.displayMetrics.density).toInt()
        users.indices.reversed().forEach { index ->
            val position = positions[index]
            if (position < 0) return@forEach
            val avatarUrl = IconUtils.getForGuildMemberOrUser(
                users[index].user,
                users[index].guildMember,
                size = IconUtils.getMediaProxySize(size),
            ) ?: return@forEach
            builder.insert(position, "# ")
            builder.setVitoSpan(
                context = requireContext(),
                url = avatarUrl,
                start = position,
                end = position,
                width = size,
                height = size,
                alignment = 2,
                autoPlay = false,
                placeholder = AppCompatResources.getDrawable(requireContext(), R.drawable.asset_default_avatar_32dp),
                scaleType = ScalingUtils.ScaleType.CENTER_CROP,
                circular = true,
            )
        }
        return builder
    }

    private fun openProfile(userId: Long) {
        val channel = StoreStream.getChannelsSelected().getSelectedChannel()
        WidgetUserSheet.show(parentFragmentManager, userId, channel?.id, channel?.guildId?.takeIf { it != 0L })
    }

    companion object {
        // Kept until StoreStream's legacy widget installation is removed in its owning wave.
        @JvmField var stateSource: (() -> Observable<State>)? = null
        @JvmField var onJumpToPresent: (() -> Unit)? = null

        /** Substitution only; the emphasis markers survive for [I18nSpans] to turn into spans. */
        internal fun formatTypingText(template: String, users: List<CharSequence>): String =
            FormatUtils.substitute(
                template,
                mapOf(
                    "a" to users.getOrNull(0)?.toString().orEmpty(),
                    "b" to users.getOrNull(1)?.toString().orEmpty(),
                    "c" to users.getOrNull(2)?.toString().orEmpty(),
                ),
            )

        internal fun shouldShowStatusBar(typingVisible: Boolean, counterVisible: Boolean): Boolean =
            typingVisible || counterVisible
    }
}

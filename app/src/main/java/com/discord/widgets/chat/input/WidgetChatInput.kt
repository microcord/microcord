package com.discord.widgets.chat.input

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import android.widget.ImageView
import android.content.res.ColorStateList
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.Spanned
import android.util.TypedValue
import android.widget.Toast
import android.widget.EditText
import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.activity.OnBackPressedCallback
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.permission.Permission
import com.discord.models.message.Message
import com.discord.models.member.GuildMember
import com.discord.models.user.MeUser
import com.discord.stores.StoreChatInputEditing
import com.discord.stores.StorePendingReplies
import com.discord.stores.StoreStream
import com.discord.utilities.messagesend.MessageResult
import com.discord.utilities.messagesend.MessageSendCoordinator
import com.discord.widgets.captcha.WidgetCaptchaBottomSheet
import com.discord.widgets.chat.input.emoji.WidgetEmojiPicker
import com.lytefast.flexinput.InputListener
import com.lytefast.flexinput.fragment.FlexInputFragment
import com.lytefast.flexinput.model.Attachment
import com.lytefast.flexinput.widget.FlexEditText
import com.lytefast.flexinput.managers.KeyboardManager
import com.lytefast.flexinput.managers.FileManager
import java.io.File
import rx.Observable
import rx.Subscription
import rx.subjects.PublishSubject

class WidgetChatInput : Fragment(R.layout.widget_chat_input), WidgetEmojiPicker.Host {
    data class State(
        val channel: Channel? = null,
        val canSend: Boolean = false,
        val communicationDisabled: Boolean = false,
        val systemDm: Boolean = false,
        val pendingReply: StorePendingReplies.PendingReply? = null,
        val editing: Message? = null,
    )

    data class UploadRequest(
        val channelId: Long,
        val content: String,
        val attachments: List<Attachment<*>>,
        val pendingReply: StorePendingReplies.PendingReply?,
    )

    private var subscription: Subscription? = null
    private var sendResultSubscription: Subscription? = null
    private var uploadCaptchaSubscription: Subscription? = null
    private var mentionSubscription: Subscription? = null
    private var input: FlexEditText? = null
    private var expressionTray: View? = null
    private var expressionBackCallback: OnBackPressedCallback? = null
    private var state = State()
    private var restoringText = false
    private val drafts get() = draftsProvider()
    private val watcher = object : TextWatcher {
        override fun beforeTextChanged(value: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(value: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(value: Editable?) {
            if (restoringText) return
            val channelId = state.channel?.id ?: return
            if (state.editing == null) drafts.setTextChannelInput(channelId, value)
            coordinatorProvider()?.typing(channelId, value?.toString().orEmpty())
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<View>(R.id.chat_input_context_cancel).setOnClickListener {
            val channelId = state.channel?.id ?: return@setOnClickListener
            if (state.editing != null) editingStore?.stop(channelId) else pendingRepliesStore?.onDeletePendingReply(channelId)
        }
        view.findViewById<View>(R.id.chat_input_context_cancel).contentDescription = getString(R.string.cancel)
        view.findViewById<View>(R.id.chat_input_context_reply_mention_button).setOnClickListener {
            val channelId = state.channel?.id ?: return@setOnClickListener
            state.pendingReply?.let { pendingRepliesStore?.onSetPendingReplyShouldMention(channelId, !it.shouldMention) }
        }
        expressionBackCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = hideExpressionTray(true)
        }.also { requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, it) }
        view.post(::bindFlexInput)
    }

    override fun onStart() {
        super.onStart()
        subscription = (stateSource?.invoke() ?: defaultStateSource()).distinctUntilChanged().subscribe { next ->
            view?.post {
                if (view != null && viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) show(next)
            }
        }
        sendResultSubscription = coordinatorProvider()?.observeUpdates()?.subscribe { update ->
            val failed = update as? MessageSendCoordinator.Update.Failed ?: return@subscribe
            view?.post {
                when (val result = failed.result) {
                    is MessageResult.CaptchaRequired -> result.siteKey?.let {
                        WidgetCaptchaBottomSheet.show(parentFragmentManager, failed.requestId, it, result.rqtoken)
                    }
                    is MessageResult.Slowmode -> Toast.makeText(
                        requireContext(), R.string.channel_slowmode_desc_short, Toast.LENGTH_SHORT,
                    ).show()
                    MessageResult.AutoModBlock -> Toast.makeText(
                        requireContext(), R.string.guild_automod_blocked_message_send_notice, Toast.LENGTH_LONG,
                    ).show()
                    else -> Unit
                }
            }
        }
        uploadCaptchaSubscription = uploadCaptchaChallenges.subscribe { challenge ->
            view?.post {
                WidgetCaptchaBottomSheet.show(
                    parentFragmentManager, challenge.requestId, challenge.siteKey, challenge.rqtoken,
                )
            }
        }
        mentionSubscription = mentionRequests.subscribe { userId -> view?.post { insertMention(userId) } }
    }

    override fun onStop() {
        subscription?.unsubscribe()
        subscription = null
        sendResultSubscription?.unsubscribe()
        sendResultSubscription = null
        uploadCaptchaSubscription?.unsubscribe()
        uploadCaptchaSubscription = null
        mentionSubscription?.unsubscribe()
        mentionSubscription = null
        super.onStop()
    }

    override fun onDestroyView() {
        input?.removeTextChangedListener(watcher)
        input = null
        expressionTray = null
        expressionBackCallback = null
        super.onDestroyView()
    }

    private fun bindFlexInput() {
        val flex = childFragmentManager.findFragmentById(R.id.chat_input_widget) as? FlexInputFragment ?: return
        flex.setKeyboardManager(object : KeyboardManager {
            override fun requestDisplay(editText: EditText) {
                editText.requestFocus()
                (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
            }

            override fun requestHide() {
                val token = activity?.currentFocus?.windowToken ?: view?.windowToken ?: return
                (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(token, 0)
            }
        })
        flex.setFileManager(object : FileManager {
            override fun newImageFile(): File {
                val directory = File(requireContext().filesDir, "image_provider").apply { mkdirs() }
                return File.createTempFile("camera-", ".jpg", directory)
            }

            override fun toFileProviderUri(context: Context, file: File) =
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        })
        val editText = flex.requireView().findViewById<FlexEditText>(com.lytefast.flexinput.R.id.text_input)
        input = editText
        editText.addTextChangedListener(watcher)
        editText.setOnClickListener { hideExpressionTray(false) }
        editText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId and 0xff == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                flex.onSend()
                true
            } else false
        }
        flex.setInputListener(object : InputListener {
            override fun onSend(data: Editable, attachments: List<Attachment<*>>): Boolean =
                submit(data.toString(), attachments)
        })
        bindEmojiPicker(flex)
        show(state)
    }

    private fun bindEmojiPicker(flex: FlexInputFragment) {
        val flexView = flex.requireView()
        val tray = flexView.findViewById<View>(R.id.emoji_container)
        val button = flexView.findViewById<View>(R.id.emoji_btn)
        expressionTray = tray
        if (flex.childFragmentManager.findFragmentById(R.id.emoji_container) == null) {
            flex.childFragmentManager.beginTransaction()
                .replace(R.id.emoji_container, WidgetEmojiPicker())
                .commitNow()
        }
        button.visibility = View.VISIBLE
        button.setOnClickListener {
            if (tray.visibility == View.VISIBLE) hideExpressionTray(true) else {
                tray.visibility = View.VISIBLE
                button.isSelected = true
                expressionBackCallback?.isEnabled = true
                val token = activity?.currentFocus?.windowToken ?: view?.windowToken
                if (token != null) (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(token, 0)
            }
        }
    }

    private fun hideExpressionTray(showKeyboard: Boolean) {
        val tray = expressionTray ?: return
        tray.visibility = View.GONE
        expressionBackCallback?.isEnabled = false
        view?.findViewById<View>(R.id.emoji_btn)?.isSelected = false
        if (showKeyboard) {
            input?.requestFocus()
            (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    override fun onEmojiPicked(text: String) {
        val editor = input ?: return
        val start = minOf(editor.selectionStart, editor.selectionEnd).coerceAtLeast(0)
        val end = maxOf(editor.selectionStart, editor.selectionEnd).coerceAtLeast(start)
        editor.text?.replace(start, end, text)
        editor.setSelection((start + text.length).coerceAtMost(editor.text?.length ?: 0))
    }

    override fun onEmojiBackspace() {
        input?.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
        input?.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
    }

    private fun submit(content: String, attachments: List<Attachment<*>>): Boolean {
        val channel = state.channel ?: return false
        if (attachments.isNotEmpty()) {
            if (state.editing != null) {
                Toast.makeText(requireContext(), R.string.editing_with_attachment_error, Toast.LENGTH_SHORT).show()
                return false
            }
            val accepted = uploadHandler?.invoke(UploadRequest(channel.id, content, attachments, state.pendingReply)) == true
            if (accepted) {
                drafts.setTextChannelInput(channel.id, null)
                pendingRepliesStore?.onDeletePendingReply(channel.id)
            }
            return accepted
        }
        val coordinator = coordinatorProvider() ?: return false
        val submission = state.editing?.let { coordinator.edit(it, content) } ?: coordinator.send(
            MessageSendCoordinator.Send(
                channelId = channel.id,
                guildId = channel.guildId.takeUnless { it == 0L },
                content = content,
                canSend = state.canSend,
                communicationDisabled = state.communicationDisabled,
                systemDm = state.systemDm,
                reply = state.pendingReply,
            ),
        )
        if (submission is MessageSendCoordinator.Submission.Accepted) {
            drafts.setTextChannelInput(channel.id, null)
            pendingRepliesStore?.onDeletePendingReply(channel.id)
            editingStore?.stop(channel.id)
            return true
        }
        val result = (submission as MessageSendCoordinator.Submission.Rejected).result
        Toast.makeText(requireContext(), validationMessage(result), Toast.LENGTH_SHORT).show()
        return false
    }

    private fun insertMention(userId: Long) {
        val editor = input ?: return
        val start = editor.selectionStart.coerceAtLeast(0)
        editor.text?.insert(start, "<@$userId> ")
        editor.requestFocus()
        (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun show(next: State) {
        val previousChannel = state.channel?.id
        val previousEditing = state.editing
        if (previousChannel != null && previousChannel != next.channel?.id && state.editing == null) {
            drafts.setTextChannelInput(previousChannel, input?.text)
        }
        state = next
        val blocked = next.channel == null || !next.canSend || next.communicationDisabled || next.systemDm
        if (blocked) hideExpressionTray(false)
        requireView().findViewById<View>(R.id.chat_input_wrap).visibility = if (blocked) View.GONE else View.VISIBLE
        requireView().findViewById<View>(R.id.guard_member_verification).visibility = View.GONE
        requireView().findViewById<View>(R.id.guard_communication_disabled).visibility =
            if (next.communicationDisabled) View.VISIBLE else View.GONE
        val generalGuard = requireView().findViewById<View>(R.id.guard)
        generalGuard.visibility = if (!next.communicationDisabled && (next.systemDm || next.channel != null && !next.canSend)) View.VISIBLE else View.GONE
        requireView().findViewById<TextView>(R.id.chat_input_guard_text).text = getString(
            if (next.systemDm) R.string.system_dm_channel_description else R.string.no_send_messages_permission_placeholder,
        )
        requireView().findViewById<View>(R.id.chat_input_guard_action).visibility = View.GONE
        requireView().findViewById<View>(R.id.chat_input_guard_action_secondary).visibility = View.GONE
        showContext(next)

        if (previousChannel != next.channel?.id || previousEditing != next.editing) restoreInput(next)
        input?.isEnabled = !blocked
        input?.hint = next.channel?.name?.let {
            getString(R.string.textarea_placeholder).replace("{channel}", it).replace("!!", "")
        }
    }

    private fun restoreInput(next: State) {
        val text = next.editing?.content ?: next.channel?.id?.let(drafts::getTextChannelInput)?.toString().orEmpty()
        restoringText = true
        input?.setText(text)
        input?.setSelection(input?.text?.length ?: 0)
        restoringText = false
    }

    private fun showContext(next: State) {
        val bar = requireView().findViewById<View>(R.id.chat_input_context_bar)
        val pending = next.pendingReply
        bar.visibility = if (next.editing != null || pending != null) View.VISIBLE else View.GONE
        if (bar.visibility != View.VISIBLE) return
        val mention = requireView().findViewById<View>(R.id.chat_input_context_reply_mention_button)
        if (next.editing != null) {
            val description = requireView().findViewById<TextView>(R.id.chat_input_context_description)
            description.setText(R.string.editing_message)
            bar.contentDescription = description.text
            bar.isClickable = false
            bar.setOnClickListener(null)
            mention.visibility = View.GONE
        } else if (pending != null) {
            val author = pending.originalMessage.author
            val member = pending.messageReference.guildId?.let { guildId -> author?.id?.let { StoreStream.getGuilds().getMember(guildId, it) } }
            val username = member?.nick?.takeUnless(String::isBlank) ?: author?.username.orEmpty()
            val text = getString(R.string.mobile_replying_to).replace("{username}", username).replace("**", "")
            requireView().findViewById<TextView>(R.id.chat_input_context_description).text = SpannableString(text).apply {
                val start = text.indexOf(username)
                if (start >= 0) setSpan(ForegroundColorSpan(GuildMember.getColor(member, themedColor(R.attr.colorHeaderPrimary))), start, start + username.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            bar.contentDescription = text
            bar.isClickable = true
            bar.setOnClickListener {
                val reference = pending.messageReference
                val channelId = reference.channelId ?: return@setOnClickListener
                val messageId = reference.messageId ?: return@setOnClickListener
                StoreStream.getMessagesLoader().jumpToMessage(channelId, messageId)
            }
            mention.visibility = if (pending.showMentionToggle) View.VISIBLE else View.GONE
            val mentionColor = themedColor(if (pending.shouldMention) R.attr.colorControlBrandForeground else R.attr.colorTextMuted)
            requireView().findViewById<ImageView>(R.id.chat_input_context_reply_mention_button_image).imageTintList = ColorStateList.valueOf(mentionColor)
            requireView().findViewById<TextView>(R.id.chat_input_context_reply_mention_button_text).apply {
                setTextColor(mentionColor)
                setText(if (pending.shouldMention) R.string.reply_mention_on else R.string.reply_mention_off)
            }
            mention.contentDescription = getString(if (pending.shouldMention) R.string.reply_mention_on_tooltip else R.string.reply_mention_off_tooltip)
        }
    }

    private fun themedColor(attribute: Int) = TypedValue().let {
        requireContext().theme.resolveAttribute(attribute, it, true)
        it.data
    }

    private fun defaultStateSource(): Observable<State> = StoreStream.getChannelsSelected().observeSelectedChannel().switchMap { channel ->
        if (channel == null) return@switchMap Observable.just(State())
        val member = if (channel.guildId == 0L) Observable.just(null)
            else StoreStream.getGuilds().observeComputedMember(channel.guildId, StoreStream.getUsers().getMe().id)
        Observable.combineLatest(
            StoreStream.getPermissions().observePermissionsForChannel(channel.id),
            StoreStream.getUsers().observeMe(true),
            member,
            pendingRepliesStore?.observePendingReply(channel.id) ?: Observable.just(null),
            editingStore?.observe(channel.id) ?: Observable.just(null),
        ) { permissions, me, guildMember, pending, editing -> state(channel, permissions, me, guildMember?.isCommunicationDisabled() == true, pending, editing) }
    }

    private fun validationMessage(result: MessageResult): String = when (result) {
        is MessageResult.ValidationError -> result.message
        MessageResult.NoValidContent -> getString(R.string.message_too_long_header)
        else -> getString(R.string.error)
    }

    companion object {
        data class UploadCaptchaChallenge(val requestId: String, val siteKey: String, val rqtoken: String?)
        private val uploadCaptchaChallenges = PublishSubject.create<UploadCaptchaChallenge>().toSerialized()
        private val mentionRequests = PublishSubject.create<Long>().toSerialized()
        @JvmField var coordinatorProvider: () -> MessageSendCoordinator? = { null }
        @JvmField var draftsProvider: () -> MessageDraftsRepo = { MessageDraftsRepo.Provider.get() }
        @JvmField var pendingRepliesStore: StorePendingReplies? = null
        @JvmField var editingStore: StoreChatInputEditing? = null
        @JvmField var uploadHandler: ((UploadRequest) -> Boolean)? = null
        @JvmField var stateSource: (() -> Observable<State>)? = null

        fun requestUploadCaptcha(requestId: String, siteKey: String, rqtoken: String?) {
            uploadCaptchaChallenges.onNext(UploadCaptchaChallenge(requestId, siteKey, rqtoken))
        }

        fun requestMention(userId: Long) = mentionRequests.onNext(userId)

        private fun state(
            channel: Channel,
            permissions: Long?,
            me: MeUser,
            communicationDisabled: Boolean,
            pending: StorePendingReplies.PendingReply?,
            editing: Message?,
        ): State {
            val privateChannel = channel.type == Channel.DM || channel.type == Channel.GROUP_DM
            return State(
                channel,
                privateChannel || permissions?.and(Permission.SEND_MESSAGES)?.let { it != 0L } == true,
                communicationDisabled,
                privateChannel && channel.recipients.orEmpty().any { it.id != me.id && it.system == true },
                pending,
                editing,
            )
        }
    }
}

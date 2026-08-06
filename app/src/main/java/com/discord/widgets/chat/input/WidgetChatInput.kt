package com.discord.widgets.chat.input

import android.content.Context
import android.content.res.ColorStateList
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.AppCompatImageButton
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.activities.EmbeddedActivityLauncher
import com.discord.api.channel.Channel
import com.discord.api.channel.getDisplayNameOrDefault
import com.discord.api.channel.isAnnouncementChannel
import com.discord.api.channel.isPendingMessageRequestChannel
import com.discord.api.channel.isSpamMessageRequestChannel
import com.discord.api.channel.isThreadOnlyChannel
import com.discord.api.guild.GuildVerificationLevel
import com.discord.api.permission.Permission
import com.discord.app.AppFragment
import com.discord.app.AppToast
import com.discord.app.appActivityViewModels
import com.discord.databinding.FlexInputWidgetBinding
import com.discord.i18n.FormatUtils
import com.discord.models.domain.emoji.Emoji
import com.discord.models.member.GuildMember
import com.discord.stores.StoreChatInputEditing
import com.discord.stores.StorePendingReplies
import com.discord.stores.StoreSlowMode
import com.discord.stores.StoreStream
import com.discord.utilities.attachments.UploadAttachment
import com.discord.utilities.channel.ChannelSelector
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.duration.humanizeCountdownDuration
import com.discord.utilities.messagesend.MessageResult
import com.discord.utilities.messagesend.MessageSendCoordinator
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.filterNull
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.takeSingleUntilTimeout
import com.discord.utilities.rx.ui
import com.discord.utilities.threads.ThreadUtils
import com.discord.utilities.user.UserUtils.synthesizeApiUser
import com.discord.utilities.view.extensions.setTextAndVisibilityBy
import com.discord.widgets.captcha.WidgetCaptchaBottomSheet
import com.discord.widgets.chat.input.autocomplete.AutocompleteViewModel
import com.discord.widgets.chat.input.autocomplete.ChatInputAutocompletables
import com.discord.widgets.chat.input.autocomplete.InputAutocomplete
import com.discord.widgets.chat.input.applauncher.WidgetAppLauncher
import com.discord.widgets.chat.input.emoji.EmojiPickerContextType
import com.discord.widgets.chat.input.emoji.WidgetEmojiPicker
import com.discord.widgets.chat.input.expression.WidgetExpressionTray
import com.discord.widgets.forums.ForumPostCreateManager
import com.discord.widgets.message_requests.setMessageRequestAccepted
import com.discord.widgets.servers.member_verification.WidgetMemberVerification
import com.google.android.material.button.MaterialButton
import com.lytefast.flexinput.InputListener
import com.lytefast.flexinput.fragment.FlexInputFragment
import com.lytefast.flexinput.managers.FileManager
import com.lytefast.flexinput.managers.KeyboardManager
import com.lytefast.flexinput.model.Attachment
import com.lytefast.flexinput.widget.FlexEditText
import java.io.File
import rx.Subscription
import rx.subjects.PublishSubject
import com.discord.utilities.user.UserUtils.getDisplayName
import com.discord.i18n.i18nRender

internal fun FlexInputWidgetBinding.renderSendPermission(ableToSendMessages: Boolean) {
    cannotSendText.visibility = if (ableToSendMessages) View.GONE else View.VISIBLE
}

/**
 * The message composer.
 *
 * State comes from [ChatInputViewModel] as one `Loaded` snapshot per channel, and the fragment's
 * whole job is to turn that into either the composer or exactly one guard explaining why the
 * composer is absent. The guards are mutually exclusive and ordered — see [configureChatGuard].
 *
 * Autocomplete is a separate controller ([InputAutocomplete]) rather than inline code here,
 * because it drives three views that live in different parts of the layout and it has to own the
 * FlexInput child fragment's `EditText`, which does not exist until that fragment is laid out.
 */
class WidgetChatInput : AppFragment(R.layout.widget_chat_input), WidgetEmojiPicker.Host {

    data class UploadRequest(
        val channelId: Long,
        val content: String,
        val attachments: List<Attachment<*>>,
        val pendingReply: StorePendingReplies.PendingReply?,
    )

    private lateinit var viewModel: ChatInputViewModel
    private lateinit var autocompleteViewModel: AutocompleteViewModel

    private var sendResultSubscription: Subscription? = null
    private var mentionSubscription: Subscription? = null
    private var input: FlexEditText? = null
    private var expressionTray: WidgetExpressionTray? = null
    private var autocomplete: InputAutocomplete? = null
    private var attachments: WidgetChatInputAttachments? = null
    private var flexInputBinding: FlexInputWidgetBinding? = null

    /**
     * Owns whether the expression tray is open. Activity-scoped so the tray and its child pickers
     * — which live inside FlexInput's own fragment manager — address the same instance.
     */
    private val flexInputViewModel: AppFlexInputViewModel by appActivityViewModels {
        AppFlexInputViewModel()
    }

    private var state: ChatInputViewModel.ViewState.Loaded? = null
    private var hasAttachments = false
    private var threadCreateInProgress = false

    /** `true` while [restoreInput] rewrites the composer, so the draft watcher ignores the edit. */
    private var restoringText = false

    private val drafts get() = draftsProvider()

    private val watcher = object : TextWatcher {
        override fun beforeTextChanged(value: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(value: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(value: Editable?) {
            updateSendButtonVisibility()
            if (restoringText) return
            val loaded = state ?: return
            if (!loaded.isEditing) drafts.setTextChannelInput(loaded.channelId, value)
            if (loaded.selectedThreadDraft == null) {
                coordinatorProvider()?.typing(loaded.channelId, value?.toString().orEmpty())
            }
        }
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        // Stopgap until StoreEmoji exists; see ChatInputAutocompletables.installDefaultEmojiSetSource.
        ChatInputAutocompletables.installDefaultEmojiSetSource(requireContext())
        viewModel = ViewModelProvider(this).get(ChatInputViewModel::class.java)
        autocompleteViewModel = ViewModelProvider(this).get(AutocompleteViewModel::class.java)
        childFragmentManager.setFragmentResultListener(
            WidgetAppLauncher.RESULT_REQUEST_KEY,
            viewLifecycleOwner,
        ) { _, result ->
            val applicationId = result.getLong(WidgetAppLauncher.RESULT_APPLICATION_ID)
            val commandId = result.getString(WidgetAppLauncher.RESULT_COMMAND_ID) ?: return@setFragmentResultListener
            val store = StoreStream.getApplicationCommands()
            val command = (store.getDiscoverCommands().commands + store.getQueryCommands() +
                store.getFrecencyCommands(state?.channel?.guildId ?: 0L))
                .firstOrNull { it.id == commandId && it.applicationId == applicationId }
                ?: return@setFragmentResultListener
            val application = store.getApplicationMap()[applicationId] ?: return@setFragmentResultListener
            flexInputViewModel.showKeyboardAndHideExpressionTray()
            autocomplete?.selectCommand(application, command)
            input?.requestFocus()
        }

        view.findViewById<View>(R.id.chat_input_context_cancel).apply {
            contentDescription = getString(R.string.cancel)
            setOnClickListener {
                val loaded = state ?: return@setOnClickListener
                if (loaded.isEditing) editingStore?.stop(loaded.channelId)
                else pendingRepliesStore?.onDeletePendingReply(loaded.channelId)
            }
        }
        view.findViewById<View>(R.id.chat_input_context_reply_mention_button).setOnClickListener {
            val loaded = state ?: return@setOnClickListener
            val replying = loaded.pendingReplyState as? ChatInputViewModel.ViewState.PendingReplyState.Replying
                ?: return@setOnClickListener
            pendingRepliesStore?.onSetPendingReplyShouldMention(
                loaded.channelId,
                !replying.pendingReply.shouldMention,
            )
        }

        // Back closes, in order: the autocomplete popup, then the expression tray. Registering
        // through the registry rather than the activity's dispatcher keeps this ordered against
        // every other handler in the app instead of relying on attach order.
        setOnBackPressed(handler = {
            when {
                autocomplete?.isShowing() == true -> {
                    autocomplete?.dismiss()
                    true
                }
                flexInputViewModel.hideExpressionTray() -> true
                else -> false
            }
        })

        view.post {
            if (isAdded && this.view === view) bindFlexInput()
        }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState()
            .ui(this)
            .subscribe({ configureUI(it) }, { /* a render failure must not kill the composer */ })
        autocomplete?.onViewBoundOrOnResume()
        flexInputViewModel.observeViewState()
            .ui(this)
            .subscribe({ renderExpressionTray(it.expressionTrayShown) }, { /* leave the tray as-is */ })
        flexInputViewModel.observeEvents()
            .ui(this)
            .subscribe(
                { event ->
                    when (event) {
                        AppFlexInputViewModel.Event.ShowKeyboard -> {
                            input?.requestFocus()
                            inputMethodManager().showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
                        }

                        AppFlexInputViewModel.Event.HideKeyboard -> {
                            val token = activity?.currentFocus?.windowToken ?: view?.windowToken
                            if (token != null) inputMethodManager().hideSoftInputFromWindow(token, 0)
                        }
                    }
                },
                { /* keyboard state is best-effort */ },
            )
    }

    override fun onStart() {
        super.onStart()
        sendResultSubscription = coordinatorProvider()?.observeUpdates()?.subscribe { update ->
            val failed = update as? MessageSendCoordinator.Update.Failed ?: return@subscribe
            view?.post {
                when (val result = failed.result) {
                    is MessageResult.CaptchaRequired -> result.challenge?.let { challenge ->
                        WidgetCaptchaBottomSheet.show(
                            parentFragmentManager, failed.requestId, challenge,
                        ) { solution ->
                            if (solution != null) {
                                coordinatorProvider()?.retry(failed.requestId, solution)
                            } else {
                                coordinatorProvider()?.cancel(failed.requestId)
                            }
                        }
                    }
                    is MessageResult.Slowmode -> Toast.makeText(
                        requireContext(), R.string.channel_slowmode_desc_short, Toast.LENGTH_SHORT,
                    ).show()
                    is MessageResult.AutoModBlock -> Toast.makeText(
                        requireContext(), R.string.guild_automod_blocked_message_send_notice, Toast.LENGTH_LONG,
                    ).show()
                    else -> Unit
                }
            }
        }
        mentionSubscription = mentionRequests.subscribe { userId -> view?.post { insertMention(userId) } }
    }

    override fun onStop() {
        sendResultSubscription?.unsubscribe()
        sendResultSubscription = null
        mentionSubscription?.unsubscribe()
        mentionSubscription = null
        super.onStop()
    }

    override fun onDestroyView() {
        input?.removeTextChangedListener(watcher)
        autocomplete?.onDestroyView()
        autocomplete = null
        flexInputBinding = null
        input = null
        expressionTray = null
        attachments = null
        super.onDestroyView()
    }

    private fun bindFlexInput() {
        if (!isAdded || view == null) return
        val flex = childFragmentManager.findFragmentById(R.id.chat_input_widget) as? FlexInputFragment ?: return
        val flexView = flex.view ?: return
        flexInputBinding = FlexInputWidgetBinding.bind(flexView)
        flex.setKeyboardManager(object : KeyboardManager {
            override fun requestDisplay(editText: EditText) {
                editText.requestFocus()
                inputMethodManager().showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
            }

            override fun requestHide() {
                val token = activity?.currentFocus?.windowToken ?: view?.windowToken ?: return
                inputMethodManager().hideSoftInputFromWindow(token, 0)
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
        val editText = flexView.findViewById<FlexEditText>(com.lytefast.flexinput.R.id.text_input)
        input = editText
        editText.addTextChangedListener(watcher)
        // Public FlexInput also closes its own tray from a touch listener. Remove that competing
        // state writer; every close now routes through AppFlexInputViewModel.
        editText.setOnTouchListener(null)
        editText.setOnClickListener { flexInputViewModel.showKeyboardAndHideExpressionTray() }
        editText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId and 0xff == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                flex.onSend()
                true
            } else false
        }
        flex.setInputListener(object : InputListener {
            override fun onSend(data: Editable, attachments: List<Attachment<*>>): Boolean =
                submit(attachments)
        })
        bindAutocomplete(editText)
        bindExpressionTray(flex, flexView)
        // The composer is bound from a `post`, so the first view state usually arrives before this
        // point and found no EditText to write into. Restore explicitly rather than relying on
        // configureLoaded, whose "channel changed" test is false when re-rendering the same state.
        state?.let {
            restoreInput(it)
            configureLoaded(it)
        }
    }

    private fun bindAutocomplete(editText: EditText) {
        val root = view ?: return
        autocomplete?.onDestroyView()
        autocomplete = InputAutocomplete(
            appComponent = this,
            editText = editText,
            autocompleteHeader = root.findViewById(R.id.chat_input_emoji_matching_header),
            autocompleteRecyclerView = root.findViewById<RecyclerView>(R.id.chat_input_mentions_recycler),
            stickersContainer = root.findViewById(R.id.chat_input_stickers_container),
            viewModel = autocompleteViewModel,
        ).also {
            it.setChannelId(state?.channelId)
            it.onViewBoundOrOnResume()
        }
    }

    /**
     * Installs the full expression tray — emoji, GIF and sticker pickers behind one segmented
     * control — into FlexInput's emoji slot, and makes the emoji button toggle it.
     *
     * Visibility is not toggled here: [AppFlexInputViewModel] owns "is the tray open", because the
     * tray's own children (a sticker sheet dismissing, a GIF being picked) have to close it and
     * have no reference to this fragment.
     */
    private fun bindExpressionTray(flex: FlexInputFragment, flexView: View) {
        val button = flexView.findViewById<AppCompatImageButton>(R.id.emoji_btn)

        attachments = WidgetChatInputAttachments(
            flexInputFragment = flex,
            flexInputViewModel = flexInputViewModel,
            emojiPickerListener = object : com.discord.widgets.chat.input.emoji.EmojiPickerListener {
                override fun onEmojiPicked(emoji: Emoji) = this@WidgetChatInput.onEmojiPicked(emoji)
            },
            onBackspacePressed = ::onEmojiBackspace,
            onAttachmentsChanged = {
                hasAttachments = it
                updateSendButtonVisibility()
            },
        )
        (flex as? AppFlexInputFragment)?.onAttachmentsCleared = {
            hasAttachments = false
            updateSendButtonVisibility()
        }
        expressionTray = attachments?.configureFlexInputFragment(this)
        hasAttachments = flex.selectionAggregator.size > 0
        updateSendButtonVisibility()

        button.visibility = View.VISIBLE
        button.setOnClickListener { flexInputViewModel.toggleExpressionTray() }
    }

    private fun renderExpressionTray(shown: Boolean) {
        val flex = childFragmentManager.findFragmentById(R.id.chat_input_widget) as? FlexInputFragment ?: return
        val flexView = flex.view ?: return
        val button = flexView.findViewById<AppCompatImageButton>(R.id.emoji_btn) ?: return

        flexView.findViewById<View>(R.id.emoji_container).visibility =
            if (shown) View.VISIBLE else View.GONE
        configureExpressionTrayButton(button, shown)
        flexView.findViewById<View>(R.id.default_window_insets_handler)?.visibility =
            if (shown) View.GONE else View.VISIBLE
        expressionTray?.takeIf { it.isAdded }?.isShown(shown)
    }

    private fun updateSendButtonVisibility() {
        view?.findViewById<View>(R.id.send_btn_container)?.visibility =
            if (!input?.text.isNullOrEmpty() || hasAttachments) View.VISIBLE else View.GONE
    }

    override fun onEmojiPicked(emoji: Emoji) {
        val editor = input ?: return
        val text = emoji.getChatInputText() + " "
        val start = minOf(editor.selectionStart, editor.selectionEnd).coerceAtLeast(0)
        val end = maxOf(editor.selectionStart, editor.selectionEnd).coerceAtLeast(start)
        editor.text?.replace(start, end, text)
        editor.setSelection((start + text.length).coerceAtMost(editor.text?.length ?: 0))
    }

    override fun onEmojiBackspace() {
        input?.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
        input?.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
    }

    /**
     * Sends whatever is in the composer.
     *
     * Content is taken from [InputAutocomplete.getInputContent] rather than the raw text, so a
     * mention the user typed by hand (`@alice#0001`) leaves as `<@id>` exactly like one they
     * picked from the popup.
     */
    private fun submit(attachments: List<Attachment<*>>): Boolean {
        val loaded = state ?: return false
        var content = autocomplete?.getInputContent() ?: input?.text?.toString().orEmpty()
        val pendingReply =
            (loaded.pendingReplyState as? ChatInputViewModel.ViewState.PendingReplyState.Replying)?.pendingReply

        if (attachments.isNotEmpty() && loaded.isEditing) {
            Toast.makeText(requireContext(), R.string.editing_with_attachment_error, Toast.LENGTH_SHORT).show()
            return false
        }

        val coordinator = coordinatorProvider() ?: return false
        val uploads = runCatching {
            attachments.map { attachment ->
                UploadAttachment.from(
                    requireContext().contentResolver,
                    attachment,
                    spoiler = (attachment as? SourcedAttachment<*>)?.spoiler == true,
                )
            }
        }.getOrElse {
            Toast.makeText(requireContext(), R.string.invalid_attachments_failure, Toast.LENGTH_SHORT).show()
            return false
        }
        val commandData = autocomplete?.getApplicationCommandData(attachments)
        if (commandData != null) {
            if (!commandData.validInputs) {
                Toast.makeText(requireContext(), R.string.command_validation_required_error, Toast.LENGTH_SHORT).show()
                return false
            }
            val command = commandData.applicationCommand
            if (command.builtIn) {
                content = command.execute?.invoke(commandData.values.associate { it.name to it.value })
                    ?: return false
            } else if (EmbeddedActivityLauncher.isPrimaryEntryPoint(command)) {
                EmbeddedActivityLauncher.launch(requireContext(), command.applicationId, loaded.channelId)
                drafts.setTextChannelInput(loaded.channelId, null)
                pendingRepliesStore?.onDeletePendingReply(loaded.channelId)
                return true
            } else {
                StoreStream.getInteractions().sendApplicationCommand(
                channelId = loaded.channelId,
                guildId = loaded.channel.guildId.takeUnless { it == 0L },
                version = command.version,
                data = commandData,
                attachments = uploads,
                onFail = {
                    view?.post {
                        Toast.makeText(requireContext(), R.string.application_command_failed, Toast.LENGTH_SHORT).show()
                    }
                },
                )
                drafts.setTextChannelInput(loaded.channelId, null)
                pendingRepliesStore?.onDeletePendingReply(loaded.channelId)
                return true
            }
        }
        if (loaded.selectedThreadDraft != null && loaded.channel.isThreadOnlyChannel()) {
            val attachmentsRequest = uploads.takeIf { it.isNotEmpty() }?.let {
                ForumPostCreateManager.AttachmentsRequest(
                    currentFileSizeMB = it.sumOf(UploadAttachment::size).toFloat() / BYTES_PER_MEGABYTE,
                    maxFileSizeMB = loaded.maxFileSizeMB,
                    attachments = it,
                )
            }
            val accepted = ForumPostCreateManager.createForumPostWithMessage(
                context = requireContext(),
                forumChannelId = loaded.channel.id,
                autoArchiveDuration = loaded.threadDraftState.autoArchiveDuration
                    ?: loaded.channel.defaultAutoArchiveDuration
                    ?: DEFAULT_THREAD_ARCHIVE_DURATION,
                content = content,
                threadDraftState = loaded.threadDraftState,
                attachmentsRequest = attachmentsRequest,
            )
            if (accepted) drafts.setTextChannelInput(loaded.channelId, null)
            return accepted
        }
        val threadDraft = loaded.selectedThreadDraft
        if (threadDraft != null) {
            if (threadCreateInProgress) return false
            val threadName = loaded.threadDraftState.threadName
            if (threadName.isNullOrEmpty()) {
                StoreStream.getThreadDraft().setDraftState(
                    loaded.threadDraftState.copy(shouldDisplayNameError = true),
                )
                return false
            }
            threadCreateInProgress = true
            StoreStream.getThreadDraft().setDraftSending()
            val threadType = when {
                loaded.channel.isAnnouncementChannel() -> Channel.ANNOUNCEMENT_THREAD
                loaded.threadDraftState.isPrivate -> Channel.PRIVATE_THREAD
                else -> Channel.PUBLIC_THREAD
            }
            ThreadUtils.createThread(
                parentChannelId = loaded.channel.id,
                parentMessageId = threadDraft.starterMessageId,
                type = threadType,
                name = threadName,
                autoArchiveDuration = loaded.threadDraftState.autoArchiveDuration
                    ?: loaded.channel.defaultAutoArchiveDuration
                    ?: DEFAULT_THREAD_ARCHIVE_DURATION,
                location = threadDraft.threadStartLocation,
            ).restSubscribeOn()
                .flatMap { channel ->
                    StoreStream.handleThreadCreateOrUpdate(channel)
                    StoreStream.getChannels().observeChannel(channel.id).filterNull().takeSingleUntilTimeout()
                }
                .ui(this)
                .appSubscribe(
                    errorClass = WidgetChatInput::class.java,
                    context = requireContext(),
                    errorHandler = { error ->
                        state?.threadDraftState?.copy(isSending = false)
                            ?.let(StoreStream.getThreadDraft()::setDraftState)
                        if (error.code == THREAD_CREATE_RATE_LIMIT_CODE) {
                            StoreStream.getSlowMode().onCooldown(
                                loaded.channelId,
                                error.retryAfterMs ?: 0,
                                StoreSlowMode.Type.ThreadCreate,
                            )
                        }
                    },
                    onTerminated = { threadCreateInProgress = false },
                ) { channel ->
                    val submission = coordinator.send(
                        MessageSendCoordinator.Send(
                            channelId = channel.id,
                            guildId = channel.guildId,
                            content = content,
                            author = loaded.me.synthesizeApiUser(),
                            canSend = loaded.ableToSendMessage,
                            communicationDisabled = loaded.isCommunicationDisabled,
                            systemDm = false,
                            attachments = uploads,
                        ),
                    )
                    if (submission is MessageSendCoordinator.Submission.Accepted) {
                        drafts.setTextChannelInput(loaded.channelId, null)
                    } else {
                        val result = (submission as MessageSendCoordinator.Submission.Rejected).result
                        Toast.makeText(requireContext(), validationMessage(result), Toast.LENGTH_SHORT).show()
                    }
                    StoreStream.getSlowMode().onThreadCreated(loaded.channelId)
                    ChannelSelector.getInstance().selectChannel(channel)
                }
            return true
        }
        val editing = loaded.editingMessage.takeIf { loaded.isEditing }
        val submission = editing?.let { coordinator.edit(it, content) } ?: coordinator.send(
            MessageSendCoordinator.Send(
                channelId = loaded.channelId,
                guildId = loaded.channel.guildId.takeUnless { it == 0L },
                content = content,
                author = loaded.me.synthesizeApiUser(),
                canSend = loaded.ableToSendMessage,
                communicationDisabled = loaded.isCommunicationDisabled,
                systemDm = loaded.isSystemDM,
                reply = pendingReply,
                attachments = uploads,
            ),
        )
        if (submission is MessageSendCoordinator.Submission.Accepted) {
            drafts.setTextChannelInput(loaded.channelId, null)
            pendingRepliesStore?.onDeletePendingReply(loaded.channelId)
            editingStore?.stop(loaded.channelId)
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
        inputMethodManager().showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun configureUI(viewState: ChatInputViewModel.ViewState) = when (viewState) {
        ChatInputViewModel.ViewState.Loading -> {
            state = null
            requireView().findViewById<View>(R.id.chat_input_wrap).visibility = View.GONE
            requireView().findViewById<View>(R.id.guard).visibility = View.GONE
            requireView().findViewById<View>(R.id.guard_member_verification).visibility = View.GONE
            requireView().findViewById<View>(R.id.guard_communication_disabled).visibility = View.GONE
        }
        is ChatInputViewModel.ViewState.Loaded -> configureLoaded(viewState)
    }

    private fun configureLoaded(next: ChatInputViewModel.ViewState.Loaded) {
        val previous = state
        if (previous != null && previous.channelId != next.channelId && !previous.isEditing) {
            drafts.setTextChannelInput(previous.channelId, input?.text)
        }
        state = next

        requireView().findViewById<View>(R.id.chat_input_wrap).visibility =
            if (next.isInputShowing) View.VISIBLE else View.GONE
        val channelPermissions = StoreStream.getPermissions().getPermissionsByChannel()[next.channelId]
        val ableToSendMessages = when {
            next.selectedThreadDraft != null ->
                PermissionUtils.can(Permission.SEND_MESSAGES_IN_THREADS, channelPermissions)
            next.channel.isThreadOnlyChannel() -> false
            else -> PermissionUtils.hasAccessWrite(next.channel, channelPermissions)
        }
        flexInputBinding?.renderSendPermission(ableToSendMessages)
        configureChatGuard(next)
        configureContextBar(next)

        val channelChanged = previous?.channelId != next.channelId
        if (channelChanged) autocomplete?.setChannelId(next.channelId)
        // The composer is only rewritten when it is showing something else: switching channel, or
        // entering/leaving edit mode. Any other state change must leave what the user typed alone.
        if (channelChanged || previous?.editingMessage != next.editingMessage) restoreInput(next)
        input?.isEnabled = next.isInputShowing
        input?.hint = getHint(requireContext(), next.channel, next.isBlocked, next.ableToSendMessage)
    }

    /**
     * Exactly one of the three guards is shown, and the precedence is not arbitrary:
     *
     * 1. the **general guard** (lurking / verification level / system DM / follow) wins outright;
     * 2. the **member-verification** card only when that guard is hidden; and
     * 3. the **timeout** card only when both of the others are hidden.
     *
     * The ordering exists because the conditions genuinely overlap — a lurker in a screened guild
     * satisfies both (1) and (2) — and showing two stacked cards over the composer is what the
     * reference is avoiding.
     */
    private fun configureChatGuard(viewState: ChatInputViewModel.ViewState.Loaded) {
        val root = requireView()
        val guard = root.findViewById<View>(R.id.guard)
        val guardText = root.findViewById<TextView>(R.id.chat_input_guard_text)
        val guardSubtext = root.findViewById<TextView>(R.id.chat_input_guard_subtext)
        val guardAction = root.findViewById<MaterialButton>(R.id.chat_input_guard_action)
        val guardActionSecondary = root.findViewById<MaterialButton>(R.id.chat_input_guard_action_secondary)

        val showGuard = viewState.isLurking ||
            viewState.isVerificationLevelTriggered ||
            viewState.isSystemDM ||
            viewState.shouldShowFollow ||
            viewState.channel.isPendingMessageRequestChannel()
        guard.visibility = if (showGuard) View.VISIBLE else View.GONE
        root.findViewById<View>(R.id.guard_member_verification).visibility =
            if (!showGuard && viewState.shouldShowVerificationGate) View.VISIBLE else View.GONE
        root.findViewById<View>(R.id.guard_communication_disabled).visibility =
            if (!showGuard && !viewState.shouldShowVerificationGate && viewState.isCommunicationDisabled) {
                View.VISIBLE
            } else {
                View.GONE
            }

        guardSubtext.visibility = View.GONE
        guardActionSecondary.visibility = View.GONE

        when {
            viewState.channel.isPendingMessageRequestChannel() ->
                configureMessageRequestGuard(guardText, guardSubtext, guardAction, guardActionSecondary, viewState)

            viewState.isSystemDM -> {
                guardText.setText(R.string.system_dm_channel_description)
                guardSubtext.setTextAndVisibilityBy(getString(R.string.system_dm_channel_description_subtext))
                guardAction.visibility = View.GONE
            }

            viewState.shouldShowFollow -> {
                guardText.setText(R.string.follow_news_chat_input_message)
                bindGuardAction(guardAction, R.string.game_popout_follow, ChatInputGuardActions.showFollowSheet != null) {
                    ChatInputGuardActions.showFollowSheet?.invoke(
                        parentFragmentManager,
                        viewState.channelId,
                        viewState.channel.guildId,
                    )
                }
            }

            viewState.isLurking -> {
                guardText.setText(R.string.lurker_mode_chat_input_message)
                bindGuardAction(
                    guardAction,
                    R.string.lurker_mode_chat_input_button,
                    ChatInputGuardActions.joinGuildAsMember != null,
                ) {
                    ChatInputGuardActions.joinGuildAsMember?.invoke(viewState.channel.guildId, {}, {})
                }
            }

            viewState.isCommunicationDisabled -> {
                configureTimeoutGuard(root, viewState)
            }

            viewState.shouldShowVerificationGate -> {
                configureMemberVerificationGuard(root, viewState)
            }

            else -> {
                guardText.text = getVerificationText(requireContext(), viewState.verificationLevelTriggered)
                bindGuardAction(
                    guardAction,
                    verificationActionTextRes(viewState.verificationLevelTriggered),
                    ChatInputGuardActions.launchAccountVerification != null,
                ) {
                    ChatInputGuardActions.launchAccountVerification
                        ?.invoke(requireContext(), viewState.verificationLevelTriggered)
                }
            }
        }
    }

    private fun configureMessageRequestGuard(
        text: TextView,
        subtext: TextView,
        primary: MaterialButton,
        secondary: MaterialButton,
        state: ChatInputViewModel.ViewState.Loaded,
    ) {
        val spam = state.channel.isSpamMessageRequestChannel()
        text.setText(if (spam) R.string.message_requests_spam_guard_title else R.string.message_requests_guard_title)
        subtext.setTextAndVisibilityBy(
            getString(if (spam) R.string.message_requests_spam_guard_subtitle else R.string.message_requests_guard_subtitle),
        )
        bindGuardAction(
            primary,
            if (spam) R.string.message_requests_report_spam else R.string.message_requests_accept,
            true,
        ) { updateMessageRequest(state, accepted = !spam, primary, secondary) }
        bindGuardAction(
            secondary,
            if (spam) R.string.message_requests_not_spam else R.string.message_requests_ignore,
            true,
        ) { updateMessageRequest(state, accepted = spam, primary, secondary) }
    }

    private fun updateMessageRequest(
        state: ChatInputViewModel.ViewState.Loaded,
        accepted: Boolean,
        primary: MaterialButton,
        secondary: MaterialButton,
    ) {
        val api = RestAPI.apiOrNull ?: return
        primary.isEnabled = false
        secondary.isEnabled = false
        val request = api.setMessageRequestAccepted(state.channelId, accepted)
        request.restSubscribeOn(false).ui(this).appSubscribe(
            javaClass,
            errorHandler = {
                primary.isEnabled = true
                secondary.isEnabled = true
                AppToast.show(requireContext(), R.string.message_requests_action_failed)
            },
            onCompleted = {
                if (accepted) {
                    StoreStream.getChannels().onGroupCreated(
                        state.channel.copy(isMessageRequest = false, isMessageRequestTimestamp = null, isSpam = false),
                    )
                } else {
                    ChannelSelector.getInstance().selectChannel(0L, 0L)
                }
            },
            onTerminated = {
                primary.isEnabled = true
                secondary.isEnabled = true
            },
            onNext = {},
        )
    }

    private fun bindGuardAction(
        button: MaterialButton,
        textRes: Int?,
        enabled: Boolean,
        onClick: () -> Unit,
    ) {
        if (textRes == null || !enabled) {
            button.visibility = View.GONE
            button.setOnClickListener(null)
            return
        }
        button.setTextAndVisibilityBy(getString(textRes))
        button.setOnClickListener { onClick() }
    }

    private fun configureTimeoutGuard(root: View, viewState: ChatInputViewModel.ViewState.Loaded) {
        val body = root.findViewById<TextView>(R.id.chat_input_communication_disabled_guard_text)
        body.text = FormatUtils.format(
            getString(R.string.guild_communication_disabled_chat_notice_description),
            "articleLink" to TIMEOUT_HELP_ARTICLE_URL,
        )
        root.findViewById<TextView>(R.id.chat_input_communication_disabled_guard_timer_text).text =
            humanizeCountdownDuration(requireContext(), viewState.timeoutLeftMs)
    }

    /**
     * The screening card.
     *
     * Only the "not applied yet" branch is reachable: the reference's other two states (application
     * pending, application rejected) come from `StoreGuildJoinRequests`, which does not exist here,
     * so `joinRequestStatus` has no source and the reference's own default branch is the one that
     * runs.
     */
    private fun configureMemberVerificationGuard(root: View, viewState: ChatInputViewModel.ViewState.Loaded) {
        root.findViewById<MaterialButton>(R.id.chat_input_member_verification_guard_button).visibility = View.GONE
        val action = root.findViewById<ImageView>(R.id.chat_input_member_verification_guard_action)
        action.visibility = View.VISIBLE
        action.setImageResource(R.drawable.ic_arrow_right_24dp)
        root.findViewById<TextView>(R.id.chat_input_member_verification_guard_text)
            .setText(R.string.member_verification_chat_blocker_text)
        root.findViewById<ImageView>(R.id.chat_input_member_verification_guard_icon)
            .setImageResource(R.drawable.img_member_verification_started)
        root.findViewById<View>(R.id.chat_input_member_verification_guard).setOnClickListener {
            WidgetMemberVerification.create(requireContext(), viewState.channel.guildId, "Chat Input")
        }
    }

    private fun getHint(
        context: Context,
        channel: Channel,
        isBlocked: Boolean,
        hasSendMessagePermissions: Boolean,
    ): CharSequence = when {
        isBlocked -> context.getString(R.string.dm_verification_text_blocked)
        !hasSendMessagePermissions -> context.getString(R.string.no_send_messages_permission_placeholder)
        else -> FormatUtils.format(
            context.getString(R.string.textarea_placeholder),
            "channel" to channel.getDisplayNameOrDefault(context),
        )
    }

    /**
     * `MEDIUM` and `HIGH` are waiting periods — there is nothing the user can do but wait — so the
     * reference offers no button for them, and neither does this.
     */
    private fun verificationActionTextRes(level: GuildVerificationLevel): Int? = when (level) {
        GuildVerificationLevel.LOW -> R.string.verify_account
        GuildVerificationLevel.HIGHEST -> R.string.verify_phone
        else -> null
    }

    private fun getVerificationText(context: Context, level: GuildVerificationLevel): CharSequence? = when (level) {
        GuildVerificationLevel.LOW -> context.getString(R.string.guild_verification_text_not_claimed)
        GuildVerificationLevel.MEDIUM -> FormatUtils.format(
            context.getString(R.string.guild_verification_text_account_age),
            "minutes" to "5",
        )
        GuildVerificationLevel.HIGH -> FormatUtils.format(
            context.getString(R.string.guild_verification_text_member_age),
            "minutes" to "10",
        )
        GuildVerificationLevel.HIGHEST -> context.getString(R.string.guild_verification_text_not_phone_verified)
        GuildVerificationLevel.NONE -> null
    }

    private fun restoreInput(next: ChatInputViewModel.ViewState.Loaded) {
        val text = next.editingMessage?.content?.takeIf { next.isEditing }
            ?: drafts.getTextChannelInput(next.channelId)?.toString().orEmpty()
        restoringText = true
        input?.setText(text)
        input?.setSelection(input?.text?.length ?: 0)
        restoringText = false
    }

    private fun configureContextBar(next: ChatInputViewModel.ViewState.Loaded) {
        val bar = requireView().findViewById<View>(R.id.chat_input_context_bar)
        val replying = next.pendingReplyState as? ChatInputViewModel.ViewState.PendingReplyState.Replying
        bar.visibility = if (next.isEditing || replying != null) View.VISIBLE else View.GONE
        if (bar.visibility != View.VISIBLE) return

        val mention = requireView().findViewById<View>(R.id.chat_input_context_reply_mention_button)
        val description = requireView().findViewById<TextView>(R.id.chat_input_context_description)

        if (next.isEditing) {
            description.setText(R.string.editing_message)
            bar.contentDescription = description.text
            bar.isClickable = false
            bar.setOnClickListener(null)
            mention.visibility = View.GONE
            return
        }

        val pending = replying?.pendingReply ?: return
        val author = pending.originalMessage.author
        val member = pending.messageReference.guildId?.let { guildId ->
            author?.id?.let { StoreStream.getGuilds().getMember(guildId, it) }
        }
        val username = author?.getDisplayName(member?.nick).orEmpty()
        val rendered = requireContext().i18nRender(R.string.mobile_replying_to, "username" to username)
        description.text = SpannableString(rendered).apply {
            // The renderer's emphasis run is exactly the name, so the role colour rides on it.
            getSpans(0, length, StyleSpan::class.java).forEach { emphasis ->
                setSpan(
                    ForegroundColorSpan(
                        GuildMember.getColor(
                            member,
                            ColorCompat.getThemedColor(requireContext(), R.attr.colorHeaderPrimary),
                        ),
                    ),
                    getSpanStart(emphasis),
                    getSpanEnd(emphasis),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
        bar.contentDescription = rendered
        bar.isClickable = true
        bar.setOnClickListener {
            val reference = pending.messageReference
            val channelId = reference.channelId ?: return@setOnClickListener
            val messageId = reference.messageId ?: return@setOnClickListener
            StoreStream.getMessagesLoader().jumpToMessage(channelId, messageId)
        }
        mention.visibility = if (pending.showMentionToggle) View.VISIBLE else View.GONE
        val mentionColor =
            ColorCompat.getThemedColor(requireContext(), if (pending.shouldMention) R.attr.colorControlBrandForeground else R.attr.colorTextMuted)
        requireView().findViewById<ImageView>(R.id.chat_input_context_reply_mention_button_image).imageTintList =
            ColorStateList.valueOf(mentionColor)
        requireView().findViewById<TextView>(R.id.chat_input_context_reply_mention_button_text).apply {
            setTextColor(mentionColor)
            setText(if (pending.shouldMention) R.string.reply_mention_on else R.string.reply_mention_off)
        }
        mention.contentDescription =
            getString(if (pending.shouldMention) R.string.reply_mention_on_tooltip else R.string.reply_mention_off_tooltip)
    }

    private fun inputMethodManager(): InputMethodManager =
        requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    internal fun currentChannelId(): Long? = state?.channelId

    private fun validationMessage(result: MessageResult): String = when (result) {
        is MessageResult.ValidationError -> result.message
        MessageResult.NoValidContent -> getString(R.string.message_too_long_header)
        else -> getString(R.string.error)
    }

    companion object {
        /** Discord's own help-centre article on timeouts; the reference hardcodes the same id. */
        private const val TIMEOUT_HELP_ARTICLE_URL =
            "https://support.discord.com/hc/articles/360045138571"
        private const val BYTES_PER_MEGABYTE = 1024f * 1024f
        private const val DEFAULT_THREAD_ARCHIVE_DURATION = 1_440
        private const val THREAD_CREATE_RATE_LIMIT_CODE = 20016

        private val mentionRequests = PublishSubject.create<Long>().toSerialized()

        @JvmField var coordinatorProvider: () -> MessageSendCoordinator? = { null }
        @JvmField var draftsProvider: () -> MessageDraftsRepo = { MessageDraftsRepo.Provider.get() }
        @JvmField var pendingRepliesStore: StorePendingReplies? = null
        @JvmField var editingStore: StoreChatInputEditing? = null
        @JvmField var uploadHandler: ((UploadRequest) -> Boolean)? = null

        fun requestMention(userId: Long) = mentionRequests.onNext(userId)
    }
}

internal fun configureExpressionTrayButton(button: AppCompatImageButton, shown: Boolean) {
    button.setImageResource(if (shown) R.drawable.ic_keyboard_24dp else R.drawable.ic_emoji_24dp)
    button.contentDescription = button.context.getString(
        if (shown) R.string.show_keyboard else R.string.toggle_emoji_keyboard,
    )
    // 126.21 changes the drawable and description; it does not use selected/activated selectors.
    button.isSelected = false
    button.isActivated = false
}

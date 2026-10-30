package com.discord.widgets.chat.input

import android.text.InputType
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.view.Gravity
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.SystemClock
import android.text.Editable
import android.text.Spanned
import android.text.SpannableString
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.view.ViewGroup
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.WindowManager
import android.view.animation.AnimationUtils
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.Chronometer
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.AppCompatImageButton
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.widget.ImageViewCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.appcompat.app.AlertDialog
import com.discord.R
import com.discord.activities.EmbeddedActivityLauncher
import com.discord.api.channel.Channel
import com.discord.models.commands.BuiltInCommand
import com.discord.models.commands.BuiltInCommandAction
import com.discord.widgets.chat.input.models.ApplicationCommandData
import com.discord.api.channel.getDisplayNameOrDefault
import com.discord.api.channel.isAnnouncementChannel
import com.discord.api.channel.isPendingMessageRequestChannel
import com.discord.api.channel.isPrivate
import com.discord.api.channel.isSpamMessageRequestChannel
import com.discord.api.channel.isThreadOnlyChannel
import com.discord.api.guild.GuildVerificationLevel
import com.discord.api.permission.Permission
import com.discord.api.message.MessageFlags
import com.discord.app.AppFragment
import com.discord.app.AppToast
import com.discord.app.appActivityViewModels
import com.discord.databinding.FlexInputWidgetBinding
import com.discord.databinding.WidgetChatInputBinding
import com.discord.dialogs.SimpleConfirmationDialog
import com.discord.dialogs.SimpleConfirmationDialogArgs
import com.discord.i18n.FormatUtils
import com.discord.i18n.setI18nText
import com.discord.models.domain.emoji.Emoji
import com.discord.models.member.GuildMember
import com.discord.stores.StoreChatInputEditing
import com.discord.stores.StoreChat
import com.discord.stores.StorePendingReplies
import com.discord.stores.StoreSlowMode
import com.discord.stores.StoreStream
import com.discord.utilities.attachments.UploadAttachment
import com.discord.utilities.channel.ChannelSelector
import com.discord.utilities.cache.SharedPreferencesProvider
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.duration.humanizeCountdownDuration
import com.discord.utilities.feature.FeatureFlags
import com.discord.utilities.feature.ChatInputCustomization
import com.discord.utilities.feature.ChatInputCustomization.Button
import com.discord.utilities.feature.ChatInputCustomization.Zone
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages
import com.discord.utilities.messagesend.MessageResult
import com.discord.utilities.messagesend.MessageSendCoordinator
import com.discord.utilities.messagesend.MessageSendLocation
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
import com.discord.widgets.chat.list.VoiceWaveformView
import com.discord.widgets.forums.ForumPostCreateManager
import com.discord.widgets.debugging.WidgetDebugging
import com.discord.widgets.user.usersheet.WidgetUserSheet
import com.discord.widgets.message_requests.setMessageRequestAccepted
import com.discord.api.guildjoinrequest.ApplicationStatus
import com.discord.widgets.servers.member_verification.MemberVerificationPendingDialog
import com.discord.widgets.servers.member_verification.MemberVerificationPendingViewModel
import com.discord.widgets.servers.member_verification.WidgetMemberVerification
import com.google.android.material.button.MaterialButton
import com.lytefast.flexinput.InputListener
import com.lytefast.flexinput.fragment.FlexInputFragment
import com.lytefast.flexinput.managers.FileManager
import com.lytefast.flexinput.managers.KeyboardManager
import com.lytefast.flexinput.model.Attachment
import com.lytefast.flexinput.widget.FlexEditText
import java.io.File
import java.util.UUID
import rx.Subscription
import rx.subjects.PublishSubject
import com.discord.utilities.user.UserUtils.getDisplayName
import com.discord.i18n.i18nRender
import com.discord.plugins.PluginUiRegistry
import com.discord.utilities.feature.PluginSurfaceCustomization
import com.discord.utilities.feature.PluginSurfaceCustomization.Surface
import dev.mantikafasi.manticord.plugins.api.pluginChannelContext
import dev.mantikafasi.manticord.plugins.api.pluginComposerContext
import dev.mantikafasi.manticord.plugins.api.PluginComposerZone
import dev.mantikafasi.manticord.plugins.api.PluginRegistration

internal fun FlexInputWidgetBinding.renderSendPermission(ableToSendMessages: Boolean) {
    cannotSendText.visibility = if (ableToSendMessages) View.GONE else View.VISIBLE
}

internal fun messageLimitText(length: Int, limit: Int) = "$length/$limit"

internal enum class VoiceGestureState { SENDING, CANCELLING, LOCKING, LOCKED }

internal fun resolveVoiceGestureState(
    deltaX: Float,
    deltaY: Float,
    cancelThreshold: Float,
    lockThreshold: Float,
): VoiceGestureState = when {
    deltaX < cancelThreshold -> VoiceGestureState.CANCELLING
    deltaY <= -lockThreshold -> VoiceGestureState.LOCKING
    else -> VoiceGestureState.SENDING
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
    private data class VoiceRecordingUi(
        val root: View,
        val hint: TextView,
        val waveform: VoiceWaveformView,
        val cancel: View,
        val send: View,
    )

    data class UploadRequest(
        val channelId: Long,
        val content: String,
        val attachments: List<Attachment<*>>,
        val pendingReply: StorePendingReplies.PendingReply?,
    )

    private lateinit var viewModel: ChatInputViewModel
    private lateinit var autocompleteViewModel: AutocompleteViewModel
    private var _binding: WidgetChatInputBinding? = null
    private val binding get() = requireNotNull(_binding)

    private var sendResultSubscription: Subscription? = null
    private var mentionSubscription: Subscription? = null
    private var chatEventSubscription: Subscription? = null
    private var input: FlexEditText? = null
    private var truncatedHint: WidgetChatInputTruncatedHint? = null
    private var expressionTray: WidgetExpressionTray? = null
    private var autocomplete: InputAutocomplete? = null
    private var attachments: WidgetChatInputAttachments? = null
    private var flexInputBinding: FlexInputWidgetBinding? = null
    private var voiceMessageRecorder: VoiceMessageRecorder? = null
    private var voiceRecordingUi: VoiceRecordingUi? = null
    private var voiceRecordingChannelId: Long? = null
    private var voiceGestureState = VoiceGestureState.SENDING
    private var voiceGestureActive = false

    // Expression-tray smooth animation (see renderExpressionTray).
    private var panelAnimator: ValueAnimator? = null
    private var lastKnownKeyboardHeight = 0
    private var panelSyncToken = 0
    private var pendingKeyboardSwap = false
    private data class PanelSwap(val container: View, val target: Int)
    private var activePanelSwap: PanelSwap? = null
    private var voiceGestureStartX = 0f
    private var voiceGestureStartY = 0f
    private val pluginComposerViews = mutableMapOf<String, View>()
    private var pluginComposerRegistration: PluginRegistration? = null
    private val pluginComposerBinder = Runnable { state?.let(::bindPluginComposerViews) }
    private var pendingVoiceFinish: Boolean? = null
    private val customizationListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == ChatInputCustomization.LAYOUT_KEY) {
            view?.post {
                flexInputBinding?.let {
                    configureChatboxCustomizations(it)
                    installPluginComposerViews(it)
                    bindKeyboardButton(it)
                }
                state?.let(::updateChatboxCustomizationAvatar)
                updateSendButtonVisibility()
            }
        }
    }

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
    /** The configured zone the silent-typing button sits in; drives hide-on-text visibility. */
    private var keyboardButtonZone: Zone = Zone.INSIDE_END
    /** Set once the author declines, so the prompt asks only on the first media send. */
    private var addMediaPromptShown = false

    /** `true` while [restoreInput] rewrites the composer, so the draft watcher ignores the edit. */
    private var restoringText = false

    private val drafts get() = draftsProvider()

    private val watcher = object : TextWatcher {
        override fun beforeTextChanged(value: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(value: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(value: Editable?) {
            updateSendButtonVisibility()
            updateMessageLimitCounter(value)
            updateKeyboardButtonForText(value?.isNotEmpty() == true)
            schedulePluginComposerBind()
            if (restoringText) return
            val loaded = state ?: return
            if (!loaded.isEditing) drafts.setTextChannelInput(loaded.channelId, value)
            if (loaded.selectedThreadDraft == null) {
                coordinatorProvider()?.typing(loaded.channelId, value?.toString().orEmpty())
            }
        }
    }

    override fun onViewBound(view: View) {
        inputAppender = { channelId, text ->
            val editor = input?.takeIf { state?.channelId == channelId }
            if (editor == null || !editor.isEnabled) {
                false
            } else {
                val start = minOf(editor.selectionStart, editor.selectionEnd).coerceAtLeast(0)
                val end = maxOf(editor.selectionStart, editor.selectionEnd).coerceAtLeast(start)
                editor.text?.replace(start, end, text)
                editor.setSelection((start + text.length).coerceAtMost(editor.text?.length ?: 0))
                true
            }
        }
        super.onViewBound(view)
        _binding = WidgetChatInputBinding.bind(view)
        pluginComposerRegistration = PluginUiRegistry.addComposerListener {
            view.post { flexInputBinding?.let(::installPluginComposerViews) }
        }
        SharedPreferencesProvider.get().registerOnSharedPreferenceChangeListener(customizationListener)
        // Stopgap until StoreEmoji exists; see ChatInputAutocompletables.installDefaultEmojiSetSource.
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

        binding.chatInputContextCancel.apply {
            contentDescription = getString(R.string.cancel)
            setOnClickListener {
                val loaded = state ?: return@setOnClickListener
                if (loaded.isEditing) editingStore?.stop(loaded.channelId)
                else pendingRepliesStore?.onDeletePendingReply(loaded.channelId)
            }
        }
        binding.chatInputContextReplyMentionButton.setOnClickListener {
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
        fun applyBottomInset(bottom: Int) {
            view.rootView.findViewById<View>(R.id.widget_chat_bottom_space)
                ?.updateLayoutParams { height = bottom }
            // Mirrors the panel-swap sync loop's own math, but from directly inside the same
            // callback that just set bottom_space's height — zero extra latency, vs. the polling
            // loop's inherent one-frame lag reading bottom_space back out after the fact. The
            // loop still runs concurrently as a fallback for when this callback never fires.
            activePanelSwap?.let { swap ->
                swap.container.updateLayoutParams { height = (swap.target - bottom).coerceIn(0, swap.target) }
            }
            // Tracks the keyboard's peak height, not just its latest sample: this fires on every
            // frame of both the opening AND closing animation, and closing ends with a run of
            // small-but-positive values ticking down to 0 — overwriting unconditionally would leave
            // this holding one of those near-zero tail values instead of the real keyboard height.
            if (bottom > lastKnownKeyboardHeight) lastKnownKeyboardHeight = bottom
        }
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            // The panel already reserves the nav-bar's own height as padding (see
            // WidgetHome.applyPanelInsets); ime()'s bottom inset overlaps that same region, so
            // subtract it out rather than reserving it a second time here.
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            applyBottomInset((ime - navBar).coerceAtLeast(0))
            insets
        }
        ViewCompat.requestApplyInsets(view)
        if (FeatureFlags.smoothKeyboardEnabled()) {
            // Drives widget_chat_bottom_space's height from every frame of the real IME animation
            // (not just its settled value), so the resize tracks the keyboard instead of jumping.
            SmoothKeyboardReactionHelper.install(view, ::applyBottomInset)
        }
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            // Below Android 11 this edge-to-edge window is not resized for the keyboard, the IME
            // inset never reaches the listener above, and the animation callback does not exist:
            // a debug log from Android 8.1 showed a 725px keyboard over an unmoved chat box. The
            // root insets do report it, so the spacer follows them on every layout pass.
            var appliedBottom = -1
            view.viewTreeObserver.addOnGlobalLayoutListener {
                val root = ViewCompat.getRootWindowInsets(view) ?: return@addOnGlobalLayoutListener
                val bottom = (
                    root.getInsets(WindowInsetsCompat.Type.ime()).bottom -
                        root.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
                    ).coerceAtLeast(0)
                if (bottom != appliedBottom) {
                    appliedBottom = bottom
                    applyBottomInset(bottom)
                }
            }
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

                        AppFlexInputViewModel.Event.ShowKeyboardAfterExpressionTray -> {
                            pendingKeyboardSwap = true
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
        view?.post(::updateSendButtonVisibility)
    }

    /** The 126.21 chat box is plain textMultiLine, so keyboards never auto-capitalize; this adds it. */
    private fun applyCapitalizeSentences(editText: FlexEditText) {
        val inputType = if (FeatureFlags.capitalizeSentencesEnabled()) {
            editText.inputType or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        } else {
            editText.inputType and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES.inv()
        }
        if (inputType != editText.inputType) editText.inputType = inputType
    }

    override fun onResume() {
        super.onResume()
        // Re-applied on resume so the switch takes effect on returning to the chat.
        input?.let(::applyCapitalizeSentences)
        // Below Android 11 there is no animation callback to drive the spacer, and adjustNothing
        // would stop the keyboard height being reported at all; adjustResize stays.
        if (FeatureFlags.smoothKeyboardEnabled() && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            // adjustResize performs its own window resize in lockstep with our manual
            // widget_chat_bottom_space resize, doubling the offset on API 35+ edge-to-edge
            // devices; owning IME positioning exclusively through insets avoids the stacking.
            @Suppress("DEPRECATION")
            requireActivity().window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        }
    }

    override fun onPause() {
        if (FeatureFlags.smoothKeyboardEnabled() && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            @Suppress("DEPRECATION")
            requireActivity().window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        super.onPause()
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
        chatEventSubscription = ChatInputStoreBindings.chatEvents().subscribe { event ->
            view?.post { handleChatEvent(event) }
        }
    }

    override fun onStop() {
        cancelVoiceRecording()
        sendResultSubscription?.unsubscribe()
        sendResultSubscription = null
        mentionSubscription?.unsubscribe()
        mentionSubscription = null
        chatEventSubscription?.unsubscribe()
        chatEventSubscription = null
        super.onStop()
    }

    override fun onDestroyView() {
        panelAnimator?.cancel()
        panelAnimator = null
        panelSyncToken++
        activePanelSwap = null
        flexInputViewModel.updateMessageLimitCounter(AppFlexInputViewModel.MessageLimitCounter())
        view?.removeCallbacks(pluginComposerBinder)
        input?.removeTextChangedListener(watcher)
        inputAppender = null
        autocomplete?.onDestroyView()
        SharedPreferencesProvider.get().unregisterOnSharedPreferenceChangeListener(customizationListener)
        autocomplete = null
        flexInputBinding = null
        pluginComposerRegistration?.unregister()
        pluginComposerRegistration = null
        pluginComposerViews.clear()
        _binding = null
        input = null
        truncatedHint = null
        expressionTray = null
        attachments = null
        super.onDestroyView()
    }

    private fun bindFlexInput() {
        if (!isAdded || view == null) return
        val flex = childFragmentManager.findFragmentById(R.id.chat_input_widget) as? FlexInputFragment ?: return
        val flexBinding = FlexInputWidgetBinding.bind(flex.view ?: return)
        flexInputBinding = flexBinding
        configureChatboxCustomizations(flexBinding)
        installPluginComposerViews(flexBinding)
        bindKeyboardButton(flexBinding)
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
        val editText = flexBinding.textInput
        input = editText
        // The field can be rebuilt without this fragment resuming, so it is set here as well.
        applyCapitalizeSentences(editText)
        truncatedHint = WidgetChatInputTruncatedHint(editText).also { it.addBindedTextWatcher(this) }
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
        bindVoiceMessageButton(flexBinding)
        bindAutocomplete(editText)
        bindExpressionTray(flex, flexBinding)
        // The composer is bound from a `post`, so the first view state usually arrives before this
        // point and found no EditText to write into. Restore explicitly rather than relying on
        // configureLoaded, whose "channel changed" test is false when re-rendering the same state.
        state?.let {
            restoreInput(it)
            swapChannelAttachments(null, it.channelId)
            configureLoaded(it)
        }
    }

    private fun configureChatboxCustomizations(flex: FlexInputWidgetBinding) {
        if (!FeatureFlags.chatboxCustomizationsEnabled()) return
        flex.leftBtnsContainer.layoutTransition = null
        flex.mainInputContainer.layoutTransition = null
        flex.rightBtnsContainer.layoutTransition = null
        val views = mapOf(
            Button.PROFILE to flex.chatboxCustomizationAvatar,
            Button.ATTACHMENT to flex.addBtn,
            Button.APPS to flex.giftBtn,
            Button.EXPAND to flex.expandBtn,
            Button.EXPRESSION to flex.expressionBtnContainer,
            Button.SEND to flex.sendBtnContainer,
        )
        val layout = ChatInputCustomization.get()
        val attachmentInside = layout.first { it.button == Button.ATTACHMENT }.zone in
            setOf(Zone.INSIDE, Zone.INSIDE_END)
        val sendInside = layout.first { it.button == Button.SEND }.zone in
            setOf(Zone.INSIDE, Zone.INSIDE_END)
        views.values.forEach { (it.parent as? ViewGroup)?.removeView(it) }
        flex.addBtn.apply {
            setImageResource(if (attachmentInside) R.drawable.ic_add_circle else R.drawable.ic_add_24dp)
            ImageViewCompat.setImageTintList(
                this,
                ColorStateList.valueOf(ColorCompat.getThemedColor(context, R.attr.colorInteractiveNormal)),
            )
            if (attachmentInside) background = null else setBackgroundResource(R.drawable.drawable_circle_black)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        flex.chatboxCustomizationAvatar.apply {
            MGImages.setRoundingParams(this, cornerRadius = 0f, circle = true)
        }
        flex.sendBtnContainer.apply {
            if (sendInside) background = null else setBackgroundResource(R.drawable.drawable_circle_black)
        }
        layout.forEach { entry ->
            if (entry.button == Button.KEYBOARD) return@forEach
            val size = if (entry.button == Button.EXPRESSION) 32 else 40
            placeInZone(flex, views.getValue(entry.button), entry.zone, sizeDp = size)
        }
        layout.firstOrNull { it.button == Button.EXPAND && it.zone != Zone.HIDDEN }?.let { expand ->
            val siblings = layout.filter {
                it.zone == Zone.LEFT || it.zone == Zone.RIGHT
            }.filter {
                it.button != expand.button && it.button != Button.SEND && it.button != Button.KEYBOARD
            }
                .map { views.getValue(it.button) }
            var savedVisibility = emptyMap<View, Int>()
            flex.expandBtn.rotation = 180f
            flex.expandBtn.setOnClickListener {
                if (savedVisibility.isEmpty()) {
                    savedVisibility = siblings.associateWith(View::getVisibility)
                    siblings.forEach { it.visibility = View.GONE }
                    flex.expandBtn.rotation = 0f
                } else {
                    savedVisibility.forEach { (view, visibility) -> view.visibility = visibility }
                    savedVisibility = emptyMap()
                    flex.expandBtn.rotation = 180f
                }
                updateChatboxCustomizationGeometry(flex)
            }
        }
        updateChatboxCustomizationGeometry(flex)
        state?.let(::updateChatboxCustomizationAvatar)
    }

    private fun bindKeyboardButton(flex: FlexInputWidgetBinding) {
        keyboardButtonZone = ChatInputCustomization.get().firstOrNull { it.button == Button.KEYBOARD }?.zone
            ?: ChatInputCustomization.defaultZone(Button.KEYBOARD)
        placeInZone(flex, flex.keyboardBtn, keyboardButtonZone, sizeDp = 32)
        flex.keyboardBtn.setOnClickListener {
            FeatureFlags.setSilentTypingEnabled(!FeatureFlags.silentTypingEnabled())
            updateKeyboardButtonTint(flex)
        }
        updateKeyboardButtonTint(flex)
        updateKeyboardButtonForText(input?.text?.isNotEmpty() == true)
        if (FeatureFlags.chatboxCustomizationsEnabled()) updateChatboxCustomizationGeometry(flex)
    }

    /** Moves [view] into the container for [zone], sizing it to a standard composer button. */
    private fun placeInZone(flex: FlexInputWidgetBinding, view: View, zone: Zone, sizeDp: Int = 40) {
        (view.parent as? ViewGroup)?.removeView(view)
        if (zone == Zone.HIDDEN) {
            view.visibility = View.GONE
            return
        }
        view.visibility = View.VISIBLE
        val margin = if (zone == Zone.INSIDE || zone == Zone.INSIDE_END) 0 else dp(4)
        view.layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)).apply {
            gravity = Gravity.CENTER_VERTICAL
            marginStart = margin
            marginEnd = margin
        }
        when (zone) {
            Zone.LEFT -> flex.leftBtnsContainer.addView(view)
            Zone.INSIDE -> flex.mainInputContainer.addView(
                view,
                flex.mainInputContainer.indexOfChild(flex.textInput).coerceAtLeast(0),
            )
            Zone.INSIDE_END -> flex.mainInputContainer.addView(view)
            Zone.RIGHT -> flex.rightBtnsContainer.addView(view)
            Zone.HIDDEN -> Unit
        }
    }

    /** Hides the silent-typing button while text is present, matching `hideOnText`. */
    private fun updateKeyboardButtonForText(hasText: Boolean) {
        val button = flexInputBinding?.keyboardBtn ?: return
        button.visibility = if (keyboardButtonZone == Zone.HIDDEN || hasText) View.GONE else View.VISIBLE
    }

    private fun updateKeyboardButtonTint(flex: FlexInputWidgetBinding) {
        val color = if (FeatureFlags.silentTypingEnabled()) {
            ContextCompat.getColor(flex.keyboardBtn.context, R.color.status_red_500)
        } else {
            ColorCompat.getThemedColor(flex.keyboardBtn.context, R.attr.colorInteractiveNormal)
        }
        ImageViewCompat.setImageTintList(flex.keyboardBtn, ColorStateList.valueOf(color))
    }

    private fun installPluginComposerViews(flex: FlexInputWidgetBinding) {
        val registered = PluginUiRegistry.composers()
        val byKey = registered.associateBy {
            PluginSurfaceCustomization.pluginKey(it.owner, it.contribution.id)
        }
        val contributions = PluginSurfaceCustomization.get(Surface.COMPOSER, byKey.keys.toList())
            .filter(PluginSurfaceCustomization.Entry::visible)
            .mapNotNull { byKey[it.key] }
        val activeKeys = contributions.mapTo(mutableSetOf()) { it.key }
        pluginComposerViews.keys.filter { it !in activeKeys }.forEach { key ->
            val removed = pluginComposerViews.remove(key) ?: return@forEach
            (removed.parent as? ViewGroup)?.removeView(removed)
        }
        pluginComposerViews.values.forEach { view -> (view.parent as? ViewGroup)?.removeView(view) }
        contributions.forEach { owned ->
            val key = owned.key
            val pluginView = pluginComposerViews[key] ?: runCatching {
                owned.contribution.createView(requireContext())
            }.getOrNull()?.also { pluginComposerViews[key] = it } ?: return@forEach
            when (owned.contribution.zone) {
                PluginComposerZone.LEFT -> flex.leftBtnsContainer.addView(pluginView)
                PluginComposerZone.INSIDE_START -> flex.mainInputContainer.addView(
                    pluginView,
                    flex.mainInputContainer.indexOfChild(flex.textInput).coerceAtLeast(0),
                )
                PluginComposerZone.INSIDE_END -> flex.mainInputContainer.addView(pluginView)
                PluginComposerZone.RIGHT -> flex.rightBtnsContainer.addView(pluginView)
            }
        }
        updateChatboxCustomizationGeometry(flex)
        state?.let(::bindPluginComposerViews)
    }

    private fun bindPluginComposerViews(state: ChatInputViewModel.ViewState.Loaded) {
        val channel = pluginChannelContext(
            channelId = state.channelId,
            guildId = state.channel.guildId.takeIf { it > 0L },
            channelType = state.channel.type,
            isPrivate = state.channel.isPrivate(),
        )
        val context = pluginComposerContext(
            channel = channel,
            isEditing = state.isEditing,
            canSendMessages = state.ableToSendMessage,
            text = input?.text ?: "",
        )
        PluginUiRegistry.composers().forEach { owned ->
            val pluginView = pluginComposerViews[owned.key] ?: return@forEach
            pluginView.visibility = if (runCatching { owned.contribution.isVisible(context) }.getOrDefault(false)) {
                View.VISIBLE
            } else {
                View.GONE
            }
            runCatching { owned.contribution.bind(pluginView, context) }
        }
    }

    private fun schedulePluginComposerBind() {
        view?.let {
            it.removeCallbacks(pluginComposerBinder)
            it.postOnAnimation(pluginComposerBinder)
        }
    }

    private fun updateChatboxCustomizationGeometry(flex: FlexInputWidgetBinding) {
        fun ViewGroup.hasVisibleChild() = (0 until childCount).any { getChildAt(it).visibility != View.GONE }
        val leftVisible = flex.leftBtnsContainer.hasVisibleChild()
        val rightVisible = flex.rightBtnsContainer.hasVisibleChild()
        flex.leftBtnsContainer.visibility = if (leftVisible) View.VISIBLE else View.GONE
        flex.rightBtnsContainer.visibility = if (rightVisible) View.VISIBLE else View.GONE
        val textIndex = flex.mainInputContainer.indexOfChild(flex.textInput).coerceAtLeast(0)
        val hasVisibleBeforeText = (0 until textIndex).any {
            flex.mainInputContainer.getChildAt(it).visibility != View.GONE
        }
        flex.mainInputContainer.setPaddingRelative(
            if (hasVisibleBeforeText) 0 else dp(12),
            flex.mainInputContainer.paddingTop,
            dp(4),
            flex.mainInputContainer.paddingBottom,
        )
        (flex.mainInputContainer.layoutParams as RelativeLayout.LayoutParams).apply {
            if (!leftVisible) {
                removeRule(RelativeLayout.END_OF)
                removeRule(RelativeLayout.RIGHT_OF)
                addRule(RelativeLayout.ALIGN_PARENT_START)
                addRule(RelativeLayout.ALIGN_PARENT_LEFT)
                marginStart = dp(8)
                leftMargin = dp(8)
            } else {
                removeRule(RelativeLayout.ALIGN_PARENT_START)
                removeRule(RelativeLayout.ALIGN_PARENT_LEFT)
                addRule(RelativeLayout.END_OF, flex.leftBtnsContainer.id)
            }
            if (!rightVisible) {
                removeRule(RelativeLayout.START_OF)
                removeRule(RelativeLayout.LEFT_OF)
                addRule(RelativeLayout.ALIGN_PARENT_END)
                addRule(RelativeLayout.ALIGN_PARENT_RIGHT)
            } else {
                removeRule(RelativeLayout.ALIGN_PARENT_END)
                removeRule(RelativeLayout.ALIGN_PARENT_RIGHT)
                addRule(RelativeLayout.START_OF, flex.rightBtnsContainer.id)
            }
            marginEnd = dp(8)
            rightMargin = dp(8)
            flex.mainInputContainer.layoutParams = this
        }
    }

    private fun updateChatboxCustomizationAvatar(state: ChatInputViewModel.ViewState.Loaded) {
        val avatar = flexInputBinding?.chatboxCustomizationAvatar ?: return
        val me = StoreStream.getUsers().getMe()
        val guildId = state.channel.guildId
        avatar.setImageURI(
            IconUtils.getForGuildMemberOrUser(
                me,
                StoreStream.getGuilds().getMember(guildId, me.id),
                size = 128,
            ),
        )
        avatar.setOnClickListener {
            WidgetUserSheet.show(
                parentFragmentManager,
                me.id,
                state.channelId,
                guildId.takeIf { it > 0L },
            )
        }
        avatar.setOnLongClickListener {
            WidgetDebugging.launch(requireContext())
            true
        }
    }

    private fun bindAutocomplete(editText: EditText) {
        autocomplete?.onDestroyView()
        autocomplete = InputAutocomplete(
            appComponent = this,
            editText = editText,
            autocompleteHeader = binding.chatInputEmojiMatchingHeader,
            autocompleteRecyclerView = binding.chatInputMentionsRecycler,
            stickersContainer = binding.chatInputStickersContainer,
            viewModel = autocompleteViewModel,
            onAttachmentOptionClicked = { flexInputBinding?.addBtn?.performClick() },
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
    private fun bindExpressionTray(flex: FlexInputFragment, flexBinding: FlexInputWidgetBinding) {
        val button = flexBinding.emojiBtn

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
                saveChannelAttachments()
            },
        )
        (flex as? AppFlexInputFragment)?.onAttachmentsCleared = {
            hasAttachments = false
            updateSendButtonVisibility()
            saveChannelAttachments()
        }
        expressionTray = attachments?.configureFlexInputFragment(this)
        hasAttachments = flex.selectionAggregator.size > 0
        updateSendButtonVisibility()

        // Deterministic starting point for the smooth open/close animation in
        // renderExpressionTray: a GONE view keeps whatever height its LayoutParams last had (its
        // XML-declared default_keyboard_height here), so without this the very first open would
        // animate from that stale full height back down to itself — reading as "nothing happened".
        flexBinding.emojiContainer.updateLayoutParams { height = 0 }

        button.visibility = View.VISIBLE
        button.setOnClickListener { flexInputViewModel.toggleExpressionTray() }
    }

    private fun bindVoiceMessageButton(flexBinding: FlexInputWidgetBinding) {
        flexBinding.voiceMessageButton.apply {
            setOnTouchListener { _, event -> handleVoiceGesture(event) }
            setOnClickListener {
                if (voiceMessageRecorder == null) {
                    voiceGestureActive = false
                    updateVoiceGestureState(VoiceGestureState.LOCKED)
                    startVoiceRecording()
                }
            }
        }
    }

    private fun shouldShowVoiceMessageButton(): Boolean {
        val loaded = state ?: return false
        return FeatureFlags.voiceMessagesEnabled() && loaded.isInputShowing && !loaded.isEditing &&
            loaded.selectedThreadDraft == null
    }

    private fun canRecordVoiceMessage(): Boolean {
        val loaded = state ?: return false
        if (!shouldShowVoiceMessageButton() || loaded.isCommunicationDisabled) return false
        if (loaded.channel.isPrivate()) return true
        val permissions = StoreStream.getPermissions().getPermissionsByChannel()[loaded.channelId]
        return PermissionUtils.can(Permission.ATTACH_FILES, permissions) &&
            PermissionUtils.can(Permission.SEND_VOICE_MESSAGES, permissions)
    }

    private fun handleVoiceGesture(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!canRecordVoiceMessage() || event.pointerCount > 1) return false
                flexInputBinding?.voiceMessageButton?.parent?.requestDisallowInterceptTouchEvent(true)
                voiceGestureActive = true
                voiceGestureStartX = event.rawX
                voiceGestureStartY = event.rawY
                pendingVoiceFinish = null
                updateVoiceGestureState(VoiceGestureState.SENDING)
                startVoiceRecording()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!voiceGestureActive) return true
                val deltaX = event.rawX - voiceGestureStartX
                val deltaY = event.rawY - voiceGestureStartY
                val cancelThreshold = -((flexInputBinding?.root?.width ?: resources.displayMetrics.widthPixels) * 0.5f)
                updateVoiceGestureState(
                    resolveVoiceGestureState(deltaX, deltaY, cancelThreshold, dp(40).toFloat()),
                )
            }
            MotionEvent.ACTION_UP -> {
                if (!voiceGestureActive) return true
                flexInputBinding?.voiceMessageButton?.parent?.requestDisallowInterceptTouchEvent(false)
                voiceGestureActive = false
                when (voiceGestureState) {
                    VoiceGestureState.CANCELLING -> completeVoiceGesture(send = false)
                    VoiceGestureState.LOCKING -> updateVoiceGestureState(VoiceGestureState.LOCKED)
                    VoiceGestureState.SENDING -> completeVoiceGesture(send = true)
                    VoiceGestureState.LOCKED -> Unit
                }
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> {
                flexInputBinding?.voiceMessageButton?.parent?.requestDisallowInterceptTouchEvent(false)
                if (voiceGestureActive) {
                    voiceGestureActive = false
                    completeVoiceGesture(send = false)
                }
            }
        }
        return true
    }

    private fun startVoiceRecording() {
        if (voiceMessageRecorder != null || !canRecordVoiceMessage()) return
        requestMicrophone(onFailure = null) {
            val loaded = state ?: return@requestMicrophone
            val recorder = VoiceMessageRecorder(requireContext())
            runCatching {
                recorder.start(
                    onMaxDuration = { view?.post { finishVoiceRecording(send = true) } },
                    onAmplitude = { sample -> voiceRecordingUi?.waveform?.addSample(sample) },
                )
            }.onFailure {
                Toast.makeText(requireContext(), R.string.voice_message_recording_failed, Toast.LENGTH_SHORT).show()
                return@requestMicrophone
            }
            voiceMessageRecorder = recorder
            voiceRecordingChannelId = loaded.channelId
            showVoiceRecordingBar()
            pendingVoiceFinish?.let { send ->
                pendingVoiceFinish = null
                finishVoiceRecording(send)
            }
        }
    }

    private fun showVoiceRecordingBar() {
        val container = _binding?.chatInputWidgetContainer ?: return
        val bar = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
            setBackgroundColor(ColorCompat.getThemedColor(context, R.attr.colorBackgroundSecondary))
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56))
        }
        fun action(icon: Int, description: Int, click: () -> Unit) = AppCompatImageButton(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(ColorCompat.getThemedColor(context, R.attr.colorInteractiveNormal))
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            contentDescription = getString(description)
            setOnClickListener { click() }
        }
        val cancel = action(R.drawable.ic_delete_24dp, R.string.cancel) { cancelVoiceRecording() }.apply {
            visibility = View.GONE
        }.also(bar::addView)
        bar.addView(View(requireContext()).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ColorCompat.getThemedColor(context, R.attr.colorError))
            }
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginStart = dp(8) }
        })
        bar.addView(Chronometer(requireContext()).apply {
            base = SystemClock.elapsedRealtime()
            setTextColor(ColorCompat.getThemedColor(context, R.attr.colorTextNormal))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(8)
            }
            start()
        })
        val waveform = VoiceWaveformView(requireContext(), recording = true).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginStart = dp(8) }
        }.also(bar::addView)
        val hint = TextView(requireContext()).apply {
            setTextColor(ColorCompat.getThemedColor(context, R.attr.colorTextMuted))
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(8)
            }
        }.also(bar::addView)
        val send = action(R.drawable.ic_send_24dp, R.string.send) { finishVoiceRecording(send = true) }.apply {
            visibility = View.GONE
        }.also(bar::addView)
        container.addView(bar)
        voiceRecordingUi = VoiceRecordingUi(bar, hint, waveform, cancel, send)
        updateVoiceGestureState(voiceGestureState, haptic = false)
    }

    private fun completeVoiceGesture(send: Boolean) {
        if (voiceMessageRecorder == null) pendingVoiceFinish = send else finishVoiceRecording(send)
    }

    private fun updateVoiceGestureState(state: VoiceGestureState, haptic: Boolean = true) {
        if (state == voiceGestureState && haptic) return
        voiceGestureState = state
        if (haptic && state in setOf(VoiceGestureState.CANCELLING, VoiceGestureState.LOCKING, VoiceGestureState.LOCKED)) {
            flexInputBinding?.voiceMessageButton?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        voiceRecordingUi?.hint?.setText(
            when (state) {
                VoiceGestureState.SENDING -> R.string.voice_message_slide_cancel
                VoiceGestureState.CANCELLING -> R.string.voice_message_release_cancel
                VoiceGestureState.LOCKING -> R.string.voice_message_release_lock
                VoiceGestureState.LOCKED -> R.string.voice_message_locked
            },
        )
        val locked = state == VoiceGestureState.LOCKED
        voiceRecordingUi?.cancel?.visibility = if (locked) View.VISIBLE else View.GONE
        voiceRecordingUi?.send?.visibility = if (locked) View.VISIBLE else View.GONE
        voiceRecordingUi?.root?.setBackgroundColor(
            ColorCompat.getThemedColor(
                requireContext(),
                if (state == VoiceGestureState.CANCELLING) R.attr.colorBackgroundModifierSelected else R.attr.colorBackgroundSecondary,
            ),
        )
    }

    private fun finishVoiceRecording(send: Boolean) {
        val recorder = voiceMessageRecorder ?: return
        voiceMessageRecorder = null
        val channelId = voiceRecordingChannelId
        voiceRecordingChannelId = null
        val recording = if (send) recorder.stop() else null.also { recorder.cancel() }
        hideVoiceRecordingBar()
        if (!send || recording == null) return
        if (recording.durationSecs * 1000 < VoiceMessageRecorder.VOICE_RECORDING_MIN_DURATION_MILLIS) {
            recording.file.delete()
            Toast.makeText(requireContext(), R.string.voice_message_too_short, Toast.LENGTH_SHORT).show()
            return
        }
        val loaded = state?.takeIf { it.channelId == channelId } ?: run {
            recording.file.delete()
            return
        }
        val pendingReply =
            (loaded.pendingReplyState as? ChatInputViewModel.ViewState.PendingReplyState.Replying)?.pendingReply
        val upload = UploadAttachment(
            id = System.currentTimeMillis(),
            uri = Uri.fromFile(recording.file),
            displayName = if (recording.mimeType == "audio/ogg") "voice-message.ogg" else "voice-message.m4a",
            spoiler = false,
            mimeType = recording.mimeType,
            size = recording.file.length(),
            permissionPersisted = false,
            durationSecs = recording.durationSecs,
            waveform = recording.waveform,
        )
        val submission = coordinatorProvider()?.send(
            MessageSendCoordinator.Send(
                channelId = loaded.channelId,
                guildId = loaded.channel.guildId.takeUnless { it == 0L },
                content = "",
                author = loaded.me.synthesizeApiUser(),
                canSend = loaded.ableToSendMessage,
                communicationDisabled = loaded.isCommunicationDisabled,
                systemDm = loaded.isSystemDM,
                reply = pendingReply,
                attachments = listOf(upload),
                flags = MessageFlags.IS_VOICE_MESSAGE,
                location = MessageSendLocation.VOICE_MESSAGE,
            ),
        )
        if (submission is MessageSendCoordinator.Submission.Accepted) {
            pendingRepliesStore?.onDeletePendingReply(loaded.channelId)
        } else {
            recording.file.delete()
            val result = (submission as? MessageSendCoordinator.Submission.Rejected)?.result
                ?: MessageResult.NoValidContent
            Toast.makeText(requireContext(), validationMessage(result), Toast.LENGTH_SHORT).show()
        }
    }

    private fun cancelVoiceRecording() = finishVoiceRecording(send = false)

    private fun hideVoiceRecordingBar() {
        voiceRecordingUi?.root?.let { (it.parent as? ViewGroup)?.removeView(it) }
        voiceRecordingUi = null
        pendingVoiceFinish = null
        voiceGestureActive = false
        voiceGestureState = VoiceGestureState.SENDING
        updateSendButtonVisibility()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun renderExpressionTray(shown: Boolean) {
        val flexBinding = flexInputBinding ?: return
        val button = flexBinding.emojiBtn
        val container = flexBinding.emojiContainer

        configureExpressionTrayButton(button, shown)
        flexBinding.defaultWindowInsetsHandler.visibility = if (shown) View.GONE else View.VISIBLE

        val swapWithKeyboard = pendingKeyboardSwap
        pendingKeyboardSwap = false

        if (!FeatureFlags.smoothKeyboardEnabled()) {
            panelAnimator?.cancel()
            panelSyncToken++
            activePanelSwap = null
            // bindExpressionTray zeroes this height as the smooth-open animation's starting point;
            // this path never animates, so without restoring it here the tray would be VISIBLE but
            // 0px tall — i.e. not actually shown — the first time it opens.
            if (shown) container.updateLayoutParams { height = panelHeight() }
            container.visibility = if (shown) View.VISIBLE else View.GONE
            expressionTray?.takeIf { it.isAdded }?.isShown(shown)
            return
        }

        panelAnimator?.cancel()
        panelSyncToken++
        activePanelSwap = null
        val target = panelHeight()
        val bottomSpace = view?.rootView?.findViewById<View>(R.id.widget_chat_bottom_space)
        val keyboardCurrentlyOpen = (bottomSpace?.height ?: 0) > 0

        if (shown) {
            container.visibility = View.VISIBLE
            if (keyboardCurrentlyOpen) {
                // Slaved to the keyboard's own live height every frame, rather than an
                // independently-timed animation guessed to match it — two animations picked to
                // look similar still drift apart frame to frame and read as a bounce at the
                // crossover; reading the real value each frame cannot drift from it.
                syncPanelToKeyboard(container, bottomSpace, target, opening = true)
            } else {
                val current = container.height.takeIf { it in 1 until target } ?: 0
                animatePanelHeight(container, from = current, to = target)
            }
        } else if (swapWithKeyboard) {
            syncPanelToKeyboard(container, bottomSpace, target, opening = false)
        } else {
            val current = container.height.takeIf { it > 0 } ?: target
            animatePanelHeight(container, from = current, to = 0) { container.visibility = View.GONE }
        }
        expressionTray?.takeIf { it.isAdded }?.isShown(shown)
    }

    /**
     * Slaves [container]'s height to [bottomSpace]'s live height. [activePanelSwap] gets it mirrored
     * with zero extra latency directly from applyBottomInset's own WindowInsetsAnimation callback
     * while that's actually firing; this polling loop is the fallback that drives it the rest of the
     * time and, via its timeout, guarantees this always finishes at the correct final state even if
     * the keyboard's own animation never runs (hideSoftInputFromWindow/showSoftInput are no-ops when
     * it's already mid-transition, which would otherwise leave nothing to finish this at all).
     */
    private fun syncPanelToKeyboard(container: View, bottomSpace: View?, target: Int, opening: Boolean) {
        val token = panelSyncToken
        val startTime = SystemClock.uptimeMillis()
        activePanelSwap = PanelSwap(container, target)
        val tick = object : Runnable {
            override fun run() {
                if (token != panelSyncToken) return
                val bottom = bottomSpace?.height ?: 0
                val elapsed = SystemClock.uptimeMillis() - startTime
                if (elapsed < PANEL_SYNC_TIMEOUT_MS) {
                    container.updateLayoutParams { height = (target - bottom).coerceIn(0, target) }
                    container.postOnAnimation(this)
                } else {
                    activePanelSwap = null
                    container.updateLayoutParams { height = if (opening) target else 0 }
                    if (!opening) container.visibility = View.GONE
                }
            }
        }
        container.postOnAnimation(tick)
    }

    /**
     * Called whenever something is about to hide the real keyboard directly (bypassing
     * [flexInputViewModel]) and immediately open a dialog or bottom sheet over the composer — e.g.
     * the attachment picker, or the attachment preview's long-press sheet.
     *
     * With smooth keyboard enabled, [widget_chat_bottom_space]'s height is driven purely by the IME
     * WindowInsetsAnimation on this fragment's own window (see [SmoothKeyboardReactionHelper]) —
     * there is no [WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE] to fall back on. Whatever's
     * about to show opens in its own window, which steals focus before that animation reaches its
     * settled frame, so the callback never fires again with the real closed height and the space is
     * left stuck at whatever it last reported. The keyboard is known to be closing here, so force
     * the space to its known final value instead of waiting on an animation that won't finish.
     */
    fun forceKeyboardClosed() {
        if (!FeatureFlags.smoothKeyboardEnabled()) return
        panelAnimator?.cancel()
        panelSyncToken++
        activePanelSwap = null
        view?.rootView?.findViewById<View>(R.id.widget_chat_bottom_space)?.updateLayoutParams { height = 0 }
    }

    private fun panelHeight(): Int {
        // Prefer the keyboard's own live space over the cached peak: falling back to a stale
        // remembered height when the real one is available is exactly what produces a mismatched
        // final size — and a visible jump — the moment this swaps places with the keyboard.
        val liveKeyboardHeight = view?.rootView?.findViewById<View>(R.id.widget_chat_bottom_space)?.height ?: 0
        return liveKeyboardHeight.takeIf { it > 0 }
            ?: lastKnownKeyboardHeight.takeIf { it > 0 }
            ?: resources.getDimensionPixelSize(R.dimen.default_keyboard_height)
    }

    private fun animatePanelHeight(container: View, from: Int, to: Int, onEnd: (() -> Unit)? = null) {
        panelAnimator = ValueAnimator.ofInt(from, to).apply {
            duration = PANEL_ANIMATION_DURATION_MS
            interpolator = AnimationUtils.loadInterpolator(container.context, android.R.interpolator.fast_out_slow_in)
            addUpdateListener { container.updateLayoutParams { height = it.animatedValue as Int } }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = onEnd?.invoke() ?: Unit
            })
            start()
        }
    }

    private fun updateSendButtonVisibility() {
        val hasSendContent = !input?.text.isNullOrEmpty() || hasAttachments
        val showVoiceMessage = !hasSendContent && canRecordVoiceMessage()
        flexInputBinding?.sendBtnContainer?.visibility =
            if (hasSendContent || showVoiceMessage) View.VISIBLE else View.GONE
        flexInputBinding?.sendBtn?.visibility =
            if (hasSendContent) View.VISIBLE else View.GONE
        flexInputBinding?.voiceMessageButton?.visibility =
            if (showVoiceMessage) View.VISIBLE else View.GONE
        flexInputBinding?.let(::updateChatboxCustomizationGeometry)
    }

    private fun updateMessageLimitCounter(value: CharSequence? = input?.text) {
        val length = value?.length ?: 0
        val limit = state?.me?.premiumTier?.let(MessageSendCoordinator::characterLimit)
            ?: MessageSendCoordinator.DEFAULT_CHARACTER_LIMIT
        flexInputViewModel.updateMessageLimitCounter(
            AppFlexInputViewModel.MessageLimitCounter(
                text = messageLimitText(length, limit),
                visible = FeatureFlags.messageLimitCounterEnabled() &&
                    state?.isInputShowing == true && length > 0,
                overLimit = length > limit,
            ),
        )
    }

    override fun onEmojiPicked(emoji: Emoji) {
        val editor = input ?: return
        val start = minOf(editor.selectionStart, editor.selectionEnd).coerceAtLeast(0)
        val end = maxOf(editor.selectionStart, editor.selectionEnd).coerceAtLeast(start)
        val editable = editor.text
        // Fake-Nitro inserts a markdown link, so keep it from gluing onto a preceding word.
        val prefix = if (editable != null && start > 0 && start <= editable.length &&
            !editable[start - 1].isWhitespace()
        ) {
            " "
        } else {
            ""
        }
        val text = prefix + emoji.getChatInputText() + " "
        editor.text?.replace(start, end, text)
        editor.setSelection((start + text.length).coerceAtMost(editor.text?.length ?: 0))
    }

    override fun onEmojiBackspace() {
        val editor = input ?: return
        val text = editor.editableText ?: return
        val range = deletionRangeBeforeCursor(text, editor.selectionStart, editor.selectionEnd) ?: return
        text.delete(range.first, range.last)
        android.text.Selection.setSelection(text, range.first)
    }

    /**
     * Sends whatever is in the composer.
     *
     * Content is taken from [InputAutocomplete.getInputContent] rather than the raw text, so a
     * mention the user typed by hand (`@alice#0001`) leaves as `<@id>` exactly like one they
     * picked from the popup.
     */
    private fun submit(
        attachments: List<Attachment<*>>,
        contentOverride: String? = null,
        generatedUpload: UploadAttachment? = null,
    ): Boolean {
        val loaded = state ?: return false
        val characterLimit = MessageSendCoordinator.characterLimit(loaded.me.premiumTier)
        var content = contentOverride ?: autocomplete?.getInputContent() ?: input?.text?.toString().orEmpty()
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
                ).copy(
                    isRemix = ((attachment as? SourcedAttachment<*>)?.source == SOURCE_PHOTO_EDITOR)
                        .takeIf { it },
                )
            } + listOfNotNull(generatedUpload)
        }.getOrElse {
            Toast.makeText(requireContext(), R.string.invalid_attachments_failure, Toast.LENGTH_SHORT).show()
            return false
        }
        // Content passed in is final: it never runs as a command, even if the composer now holds one.
        val commandData = if (contentOverride == null) autocomplete?.getApplicationCommandData(attachments) else null
        if (commandData != null) {
            if (!commandData.validInputs) {
                val reason = if (commandData.hasUnusableAttachments) {
                    R.string.command_validation_unusable_attachments
                } else {
                    R.string.command_validation_required_error
                }
                Toast.makeText(requireContext(), reason, Toast.LENGTH_SHORT).show()
                return false
            }
            val command = commandData.applicationCommand
            if (command.builtIn) {
                val action = (command as? BuiltInCommand)?.action
                if (action != null) {
                    runBuiltInAction(action, loaded.channel, commandData)
                    drafts.setTextChannelInput(loaded.channelId, null)
                    return true
                }
                val executeAsync = (command as? BuiltInCommand)?.executeAsync
                if (executeAsync != null) {
                    drafts.setTextChannelInput(loaded.channelId, null)
                    executeAsync(commandData.values.associate { it.name to it.value }) { result ->
                        view?.post { if (!result.isNullOrEmpty() && isAdded) submit(emptyList(), contentOverride = result) }
                    }
                    return true
                }
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
        if (
            generatedUpload == null &&
            !loaded.isEditing &&
            content.length > characterLimit
        ) {
            offerSendAsFile(content, attachments)
            return false
        }
        if (uploads.any { it.mimeType.startsWith("image/") || it.mimeType.startsWith("video/") } &&
            shouldOfferMediaForOriginalPost(loaded)
        ) {
            promptAddMediaToOriginalPost(loaded, content, uploads, attachments)
            return false
        }
        if (loaded.selectedThreadDraft != null && loaded.channel.isThreadOnlyChannel()) {
            val attachmentsRequest = uploads.takeIf { it.isNotEmpty() }?.let {
                ForumPostCreateManager.AttachmentsRequest(
                    sizes = it.map(UploadAttachment::size),
                    maxFileSize = loaded.maxFileSize,
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
                            characterLimit = characterLimit,
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
        val submission = editing?.let {
            coordinator.edit(it, content, characterLimit = characterLimit)
        } ?: coordinator.send(
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
                characterLimit = characterLimit,
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

    private fun runBuiltInAction(
        action: BuiltInCommandAction,
        channel: Channel,
        commandData: ApplicationCommandData,
    ) = when (action) {
        BuiltInCommandAction.LEAVE_GROUP_DM -> {
            val silent = commandData.values.firstOrNull { it.name == "silent" }?.value == true
            RestAPI.api.deleteChannel(channel.id, silent)
                .restSubscribeOn()
                .ui(this)
                .appSubscribe(WidgetChatInput::class.java, context) { }
        }
    }

    private fun offerSendAsFile(content: String, attachments: List<Attachment<*>>) {
        SimpleConfirmationDialog.show(
            parentFragmentManager,
            SimpleConfirmationDialogArgs(
                title = getString(R.string.message_too_long_header),
                description = getString(R.string.message_too_long_send_as_file_description),
                positiveButtonText = getString(R.string.message_too_long_send_as_file),
                negativeButtonText = getString(R.string.cancel),
            ),
            positiveClickListener = View.OnClickListener {
                val upload = runCatching {
                    val file = File(requireContext().cacheDir, "message-${UUID.randomUUID()}.txt")
                    file.writeText(content, Charsets.UTF_8)
                    UploadAttachment(
                        id = System.currentTimeMillis(),
                        uri = Uri.fromFile(file),
                        displayName = "message.txt",
                        spoiler = false,
                        mimeType = MessageSendCoordinator.TEXT_FILE_MIME_TYPE,
                        size = file.length(),
                        permissionPersisted = false,
                    )
                }.getOrElse {
                    Toast.makeText(requireContext(), R.string.invalid_attachments_failure, Toast.LENGTH_SHORT).show()
                    return@OnClickListener
                }
                if (submit(attachments, contentOverride = "", generatedUpload = upload)) {
                    input?.setText("")
                    (childFragmentManager.findFragmentById(R.id.chat_input_widget) as? FlexInputFragment)
                        ?.clearAttachments()
                }
            },
        )
    }

    private fun insertMention(userId: Long) {
        val editor = input ?: return
        val start = editor.selectionStart.coerceAtLeast(0)
        editor.text?.insert(start, "<@$userId> ")
        editor.requestFocus()
        inputMethodManager().showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun handleChatEvent(event: StoreChat.Event) {
        val editor = input ?: return
        when (event) {
            is StoreChat.Event.AppendChatText -> {
                val start = editor.selectionStart.coerceAtLeast(0)
                editor.text?.insert(start, event.text)
            }
            is StoreChat.Event.ReplaceChatText -> {
                editor.setText(event.text)
                editor.setSelection(editor.text?.length ?: 0)
            }
        }
        editor.requestFocus()
        inputMethodManager().showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun configureUI(viewState: ChatInputViewModel.ViewState) = when (viewState) {
        ChatInputViewModel.ViewState.Loading -> {
            state = null
            binding.chatInputWrap.visibility = View.GONE
            binding.guard.root.visibility = View.GONE
            binding.guardMemberVerification.root.visibility = View.GONE
            binding.guardCommunicationDisabled.root.visibility = View.GONE
        }
        is ChatInputViewModel.ViewState.Loaded -> configureLoaded(viewState)
    }

    private fun configureLoaded(next: ChatInputViewModel.ViewState.Loaded) {
        val previous = state
        if (previous != null && previous.channelId != next.channelId) cancelVoiceRecording()
        if (previous != null && previous.channelId != next.channelId && !previous.isEditing) {
            drafts.setTextChannelInput(previous.channelId, input?.text)
        }
        state = next
        if (previous == null || previous.channelId != next.channelId) {
            swapChannelAttachments(previous?.channelId, next.channelId)
        }
        updateMessageLimitCounter()

        binding.chatInputWrap.visibility =
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
        val hint = getHint(requireContext(), next.channel, next.isBlocked, next.ableToSendMessage)
        truncatedHint?.setHint(hint) ?: run { input?.hint = hint }
        updateSendButtonVisibility()
        updateChatboxCustomizationAvatar(next)
        bindPluginComposerViews(next)
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
        val guard = binding.guard.root
        val guardText = binding.guard.chatInputGuardText
        val guardSubtext = binding.guard.chatInputGuardSubtext
        val guardAction = binding.guard.chatInputGuardAction
        val guardActionSecondary = binding.guard.chatInputGuardActionSecondary

        val showGuard = viewState.isLurking ||
            viewState.isVerificationLevelTriggered ||
            viewState.isSystemDM ||
            viewState.shouldShowFollow ||
            viewState.channel.isPendingMessageRequestChannel()
        guard.visibility = if (showGuard) View.VISIBLE else View.GONE
        binding.guardMemberVerification.root.visibility =
            if (!showGuard && viewState.shouldShowVerificationGate) View.VISIBLE else View.GONE
        binding.guardCommunicationDisabled.root.visibility =
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
                if (viewState.isLurking) {
                    bindGuardAction(
                        guardActionSecondary,
                        R.string.join,
                        ChatInputGuardActions.joinGuildAsMember != null,
                    ) {
                        ChatInputGuardActions.joinGuildAsMember?.invoke(viewState.channel.guildId, {}, {})
                    }
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
                configureTimeoutGuard(viewState)
            }

            viewState.shouldShowVerificationGate -> {
                configureMemberVerificationGuard(viewState)
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

    private fun configureTimeoutGuard(viewState: ChatInputViewModel.ViewState.Loaded) {
        // Rendered, not formatted: the string masks a link, and the inflater's own rendering of it
        // is lost the moment this assigns plain text over the top.
        binding.guardCommunicationDisabled.chatInputCommunicationDisabledGuardText.setI18nText(
            R.string.guild_communication_disabled_chat_notice_description,
            "link" to TIMEOUT_HELP_ARTICLE_URL,
        )
        binding.guardCommunicationDisabled.chatInputCommunicationDisabledGuardTimerText.text =
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
    private fun configureMemberVerificationGuard(viewState: ChatInputViewModel.ViewState.Loaded) {
        binding.guardMemberVerification.chatInputMemberVerificationGuardButton.visibility = View.GONE
        val action = binding.guardMemberVerification.chatInputMemberVerificationGuardAction
        action.visibility = View.VISIBLE
        action.setImageResource(R.drawable.ic_arrow_right_24dp)
        val guildId = viewState.channel.guildId
        val text = binding.guardMemberVerification.chatInputMemberVerificationGuardText
        val icon = binding.guardMemberVerification.chatInputMemberVerificationGuardIcon
        // An application that is already submitted or turned down must not reopen the form: the
        // only actions left are withdrawing it or reading why it was refused.
        when (viewState.joinRequestStatus) {
            ApplicationStatus.PENDING -> {
                text.setText(R.string.member_verification_application_confirmation_title)
                icon.setImageResource(R.drawable.img_member_verification_pending)
                binding.guardMemberVerification.chatInputMemberVerificationGuard.setOnClickListener {
                    MemberVerificationPendingDialog.show(
                        parentFragmentManager,
                        guildId,
                        MemberVerificationPendingViewModel.DialogState.CANCEL,
                    )
                }
            }
            ApplicationStatus.REJECTED -> {
                text.setText(R.string.member_verification_rejected_title)
                icon.setImageResource(R.drawable.img_member_verification_denied)
                binding.guardMemberVerification.chatInputMemberVerificationGuard.setOnClickListener {
                    MemberVerificationPendingDialog.show(
                        parentFragmentManager,
                        guildId,
                        MemberVerificationPendingViewModel.DialogState.REJECTED,
                    )
                }
            }
            else -> {
                text.setText(R.string.member_verification_chat_blocker_text)
                icon.setImageResource(R.drawable.img_member_verification_started)
                binding.guardMemberVerification.chatInputMemberVerificationGuard.setOnClickListener {
                    WidgetMemberVerification.create(requireContext(), guildId, "Chat Input")
                }
            }
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
            "min" to "5",
        )
        GuildVerificationLevel.HIGH -> FormatUtils.format(
            context.getString(R.string.guild_verification_text_member_age),
            "min" to "10",
        )
        GuildVerificationLevel.HIGHEST -> context.getString(R.string.guild_verification_text_not_phone_verified)
        GuildVerificationLevel.NONE -> null
    }

    /**
     * Attachments are part of a channel's draft: park [from]'s and bring back whatever [to] had,
     * skipping any file that has gone away since. On the first load ([from] is `null`) a composer
     * that already restored its own attachments is left alone.
     */
    private fun swapChannelAttachments(from: Long?, to: Long) {
        // Until the composer is bound there is neither a preview strip nor anything in it.
        if (attachments == null) return
        val flex = childFragmentManager.findFragmentById(R.id.chat_input_widget) as? FlexInputFragment ?: return
        val current = ArrayList(flex.selectionAggregator.attachments)
        if (from == null && current.isNotEmpty()) return
        val incoming = drafts.getAttachments(to).filter(::isReadable)
        // [state] already points at [to], so the change listener saves to [to] from here on.
        if (from != null) drafts.setAttachments(from, current)
        if (current.isNotEmpty()) flex.clearAttachments()
        incoming.forEach(flex::addExternalAttachment)
        if (incoming.isEmpty()) drafts.setAttachments(to, emptyList())
    }

    private fun isReadable(attachment: Attachment<*>): Boolean = runCatching {
        requireContext().contentResolver.openAssetFileDescriptor(attachment.uri, "r")?.close() != null
    }.getOrDefault(false)

    private fun saveChannelAttachments() {
        val channelId = state?.channelId ?: return
        val flex = childFragmentManager.findFragmentById(R.id.chat_input_widget) as? FlexInputFragment ?: return
        drafts.setAttachments(channelId, flex.selectionAggregator.attachments)
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
        val bar = binding.chatInputContextBar
        val replying = next.pendingReplyState as? ChatInputViewModel.ViewState.PendingReplyState.Replying
        bar.visibility = if (next.isEditing || replying != null) View.VISIBLE else View.GONE
        if (bar.visibility != View.VISIBLE) return

        val mention = binding.chatInputContextReplyMentionButton
        val description = binding.chatInputContextDescription

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
        val username = author?.getDisplayName(member?.identityNick()).orEmpty()
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
        binding.chatInputContextReplyMentionButtonImage.imageTintList =
            ColorStateList.valueOf(mentionColor)
        binding.chatInputContextReplyMentionButtonText.apply {
            setTextColor(mentionColor)
            setText(if (pending.shouldMention) R.string.reply_mention_on else R.string.reply_mention_off)
        }
        mention.contentDescription =
            getString(if (pending.shouldMention) R.string.reply_mention_on_tooltip else R.string.reply_mention_off_tooltip)
    }

    private fun inputMethodManager(): InputMethodManager =
        requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    internal fun currentChannelId(): Long? = state?.channelId

    /**
     * A forum row takes its thumbnail from the post's opening message, so media sent afterwards
     * never reaches the browser unless it is added there — which the reference offers the author.
     */
    private fun shouldOfferMediaForOriginalPost(
        loaded: ChatInputViewModel.ViewState.Loaded,
    ): Boolean {
        val channel = loaded.channel
        val parent = StoreStream.getChannels().getChannel(channel.parentId) ?: return false
        if (!parent.isThreadOnlyChannel()) return false
        val first = StoreStream.getForumPostMessages().getForumPostFirstMessage(channel.id) ?: return false
        return first.author?.id == StoreStream.getUsers().getMe().id &&
            first.attachments.isNullOrEmpty() &&
            !addMediaPromptShown
    }

    private fun promptAddMediaToOriginalPost(
        loaded: ChatInputViewModel.ViewState.Loaded,
        content: String,
        uploads: List<UploadAttachment>,
        attachments: List<Attachment<*>>,
    ) {
        val first = StoreStream.getForumPostMessages().getForumPostFirstMessage(loaded.channel.id) ?: return
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.forum_add_media_to_original_post_title)
            .setMessage(R.string.forum_add_media_to_original_post_description)
            .setPositiveButton(R.string.forum_add_media_to_original_post_add) { _, _ ->
                ForumPostCreateManager.addMediaToOriginalPost(
                    requireContext(),
                    loaded.channel.id,
                    first.id,
                    content,
                    uploads,
                ) {
                    input?.setText("")
                    (childFragmentManager.findFragmentById(R.id.chat_input_widget) as? FlexInputFragment)
                        ?.clearAttachments()
                }
            }
            .setNegativeButton(R.string.forum_add_media_to_original_post_dont_add) { _, _ ->
                addMediaPromptShown = true
                submit(attachments)
            }
            .setNeutralButton(R.string.cancel, null)
            .show()
    }

    private fun validationMessage(result: MessageResult): String = when (result) {
        is MessageResult.ValidationError -> result.message
        MessageResult.NoValidContent -> getString(R.string.message_too_long_header)
        else -> getString(R.string.error)
    }

    companion object {
        /** Discord's own help-centre article on timeouts; the reference hardcodes the same id. */
        private const val TIMEOUT_HELP_ARTICLE_URL =
            "https://support.discord.com/hc/articles/360045138571"
        private const val DEFAULT_THREAD_ARCHIVE_DURATION = 1_440
        private const val THREAD_CREATE_RATE_LIMIT_CODE = 20016
        private const val PANEL_ANIMATION_DURATION_MS = 250L
        /** Generous upper bound on a real IME transition across devices/OEM keyboards; the panel
         * sync loop force-settles at its correct final state once this elapses either way. */
        private const val PANEL_SYNC_TIMEOUT_MS = 400L

        private val mentionRequests = PublishSubject.create<Long>().toSerialized()

        @JvmField var coordinatorProvider: () -> MessageSendCoordinator? = { null }
        @JvmField var draftsProvider: () -> MessageDraftsRepo = { MessageDraftsRepo.Provider.get() }
        @JvmField var pendingRepliesStore: StorePendingReplies? = null
        @JvmField var editingStore: StoreChatInputEditing? = null
        @JvmField var uploadHandler: ((UploadRequest) -> Boolean)? = null

        /** Inserts text at the composer's cursor, like an emoji pick; null when no input is bound. */
        @JvmField var inputAppender: ((channelId: Long, text: String) -> Boolean)? = null

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

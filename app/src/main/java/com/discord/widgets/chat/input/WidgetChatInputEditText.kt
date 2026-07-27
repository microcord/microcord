package com.discord.widgets.chat.input

import android.text.TextUtils
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import com.discord.stores.StoreStream
import com.discord.utilities.time.ClockFactory
import com.discord.utilities.view.text.TextWatcher
import com.lytefast.flexinput.widget.FlexEditText
import rx.Observable
import rx.subjects.BehaviorSubject

/**
 * The behaviour bolted onto the composer's raw `EditText`: drafts, typing indicators, and the two
 * different ways "send" can be pressed.
 *
 * It is a plain wrapper rather than an `EditText` subclass because the view itself belongs to
 * FlexInput and is inflated by its fragment; there is nothing to subclass by the time this matters.
 *
 * Hardware keyboards are the awkward case. Enter must send, but only when it is a *real* Enter:
 * IME composition-generated key events (`FLAG_SOFT_KEYBOARD`) are ignored, and whether Shift must
 * be held is the user's own `shift + enter to send` setting — with the sense inverted, so that with
 * the setting on, a bare Enter inserts a newline instead.
 */
class WidgetChatInputEditText(
    private val editText: FlexEditText,
    private val messageDraftsRepo: MessageDraftsRepo = MessageDraftsRepo.Provider.get(),
) {

    /** The channel drafts are saved against. Set by the composer on every channel change. */
    var channelId: Long = 0L
        private set

    var onSendListener: (() -> Unit)? = null

    private val emptyTextSubject: BehaviorSubject<Boolean> = BehaviorSubject.create(true)

    /** Last time a typing event was sent, so the gateway is not told once per keystroke. */
    private var lastTypingEmissionMillis: Long = 0L

    init {
        setOnTextChangedListener()
        setSoftwareKeyboardSendBehavior()
        setHardwareKeyboardSendBehavior()
    }

    /** Emits whether the composer is empty; the send button's enablement follows it. */
    fun observeEmptyText(): Observable<Boolean> = emptyTextSubject.distinctUntilChanged()

    fun getText(): String = toStringSafe(editText)

    fun saveText() = messageDraftsRepo.setTextChannelInput(channelId, editText.text)

    /**
     * @param saveExistingText persists the outgoing channel's draft first. The composer passes
     *   `false` while editing a message, where the text is not a draft.
     */
    fun setChannelId(channelId: Long, saveExistingText: Boolean) {
        if (saveExistingText) saveText()
        this.channelId = channelId
    }

    /** Lets the next keystroke re-announce typing; used after a send. */
    fun clearLastTypingEmission() {
        lastTypingEmissionMillis = 0L
    }

    private fun setOnTextChangedListener() {
        editText.addTextChangedListener(
            object : TextWatcher() {
                private var empty = true

                override fun afterTextChanged(s: android.text.Editable) {
                    super.afterTextChanged(s)
                    saveText()

                    val isEmpty = TextUtils.isEmpty(s)
                    if (empty != isEmpty) {
                        empty = isEmpty
                        emptyTextSubject.onNext(isEmpty)
                    }

                    val now = ClockFactory.get().currentTimeMillis()
                    val due = lastTypingEmissionMillis - now < -TYPING_EMISSION_INTERVAL_MS
                    // A slash command is not a message being typed, so it does not announce.
                    if (due && !isEmpty && !s.toString().startsWith('/')) {
                        lastTypingEmissionMillis = now
                        StoreStream.getUsersTyping().setUserTyping(channelId)
                    }
                }
            },
        )
    }

    private fun setSoftwareKeyboardSendBehavior() {
        editText.setOnEditorActionListener { _, actionId, _ ->
            val listener = onSendListener
            if (listener != null && (actionId and EditorInfo.IME_MASK_ACTION) == EditorInfo.IME_ACTION_SEND) {
                listener()
                true
            } else {
                false
            }
        }
    }

    private fun setHardwareKeyboardSendBehavior() {
        editText.setOnKeyListener { _, keyCode, event ->
            val fromSoftKeyboard = (event.flags and KeyEvent.FLAG_SOFT_KEYBOARD) == KeyEvent.FLAG_SOFT_KEYBOARD
            if (keyCode != KeyEvent.KEYCODE_ENTER || fromSoftKeyboard) return@setOnKeyListener false

            val shiftHeld = event.hasModifiers(KeyEvent.META_SHIFT_ON)
            val shiftEnterToSend = StoreStream.getUserSettings().getIsShiftEnterToSendEnabled()
            val shouldSend = if (shiftEnterToSend) shiftHeld else !shiftHeld
            if (!shouldSend) return@setOnKeyListener false

            // Consume both down and up, but only act on up, so the key does not fire twice.
            if (event.action == KeyEvent.ACTION_UP) onSendListener?.invoke()
            true
        }
    }

    companion object {
        /** Discord's typing indicator lasts ten seconds; re-announcing sooner is wasted traffic. */
        private const val TYPING_EMISSION_INTERVAL_MS = 10_000L

        /**
         * A `TextView`'s text can throw while spans are being swapped underneath it; a composer
         * that crashes on read is worse than one that momentarily reads empty.
         */
        @JvmStatic
        fun toStringSafe(textView: TextView): String = try {
            textView.text.toString()
        } catch (_: Exception) {
            ""
        }
    }
}

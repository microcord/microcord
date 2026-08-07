package com.discord.widgets.chat.input.expression

import android.os.Bundle
import android.view.View
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.databinding.WidgetEmojiPickerSheetBinding
import com.discord.models.domain.emoji.Emoji
import com.discord.widgets.chat.input.emoji.EmojiPickerContextType
import com.discord.widgets.chat.input.emoji.EmojiPickerListener
import com.discord.widgets.chat.input.emoji.EmojiPickerMode
import com.discord.widgets.chat.input.emoji.EmojiPickerNavigator
import com.discord.widgets.chat.input.emoji.WidgetEmojiPicker
import com.google.android.material.textfield.TextInputEditText

/**
 * The emoji picker as a search-first bottom sheet, opened by the expression tray's search field.
 *
 * Distinct from `WidgetEmojiPickerSheet`, which exists for *reactions* and therefore hands its
 * caller a `MessageReactionEmoji`. The chat input and [EmojiPickerNavigator] need the [Emoji]
 * itself, so that the receiving surface decides how it is inserted.
 *
 * The picker finds its host by walking up the fragment tree, which is why this sheet — not the tray
 * behind it — implements [WidgetEmojiPicker.Host] while the sheet is showing.
 */
class WidgetEmojiPickerChatSheet : WidgetExpressionPickerSheet(), WidgetEmojiPicker.Host {

    private var _binding: WidgetEmojiPickerSheetBinding? = null
    private val binding get() = requireNotNull(_binding)
    private var listener: EmojiPickerListener? = null
    private val emojiPickerContextType: EmojiPickerContextType
        get() = arguments?.getSerializable(EmojiPickerNavigator.ARG_EMOJI_PICKER_CONTEXT_TYPE)
            as? EmojiPickerContextType ?: EmojiPickerContextType.Chat

    override fun getContentViewResId(): Int = R.layout.widget_emoji_picker_sheet

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = WidgetEmojiPickerSheetBinding.bind(view)
        childFragmentManager.beginTransaction()
            .replace(
                binding.emojiSheetEmojiPickerContent.id,
                WidgetEmojiPicker.create(emojiPickerContextType, EmojiPickerMode.BOTTOM_SHEET),
            )
            .runOnCommit {
                binding.root.layoutParams = binding.root.layoutParams?.apply {
                    height = (resources.displayMetrics.heightPixels * SHEET_HEIGHT_FRACTION).toInt()
                }
                binding.root.findViewById<TextInputEditText>(R.id.emoji_search_input)?.let { input ->
                    input.post {
                        if (_binding == null) return@post
                        input.requestFocus()
                        showKeyboard(input)
                    }
                }
            }
            .commit()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    override fun onEmojiPicked(emoji: Emoji) {
        listener?.onEmojiPicked(emoji)
        dismiss()
    }

    override fun onEmojiBackspace() = Unit

    internal fun bindCallbacks(
        listener: EmojiPickerListener?,
        onCancel: (() -> Unit)?,
    ) {
        this.listener = listener
        setOnCancel(onCancel)
    }

    companion object {
        private const val SHEET_HEIGHT_FRACTION = 0.9

        fun show(
            fragmentManager: FragmentManager,
            listener: EmojiPickerListener?,
            emojiPickerContextType: EmojiPickerContextType = EmojiPickerContextType.Chat,
            onCancel: (() -> Unit)? = null,
        ): WidgetEmojiPickerChatSheet = WidgetEmojiPickerChatSheet().apply {
            arguments = Bundle().apply {
                putSerializable(EmojiPickerNavigator.ARG_EMOJI_PICKER_CONTEXT_TYPE, emojiPickerContextType)
            }
            bindCallbacks(listener, onCancel)
            show(fragmentManager, WidgetEmojiPickerChatSheet::class.java.simpleName)
        }
    }
}

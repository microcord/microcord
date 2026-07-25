package com.discord.widgets.chat.input.emoji

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.discord.R
import com.discord.api.message.reaction.MessageReactionEmoji
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class WidgetEmojiPickerSheet : BottomSheetDialogFragment() {
    private var listener: ((MessageReactionEmoji) -> Unit)? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?) =
        inflater.inflate(R.layout.widget_emoji_picker_sheet, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val picker = WidgetEmojiPicker().apply {
            onPicked = { emoji ->
                listener?.invoke(when (emoji) {
                    is WidgetEmojiPicker.PickerEmoji.Unicode -> MessageReactionEmoji(null, emoji.emoji.surrogates, false)
                    is WidgetEmojiPicker.PickerEmoji.Custom -> MessageReactionEmoji(
                        emoji.emoji.id.toString(), emoji.emoji.name, emoji.emoji.animated,
                    )
                })
                dismiss()
            }
        }
        childFragmentManager.beginTransaction()
            .replace(R.id.emoji_sheet_emoji_picker_content, picker)
            .runOnCommit {
                view.layoutParams = view.layoutParams.apply {
                    height = (resources.displayMetrics.heightPixels * 0.9).toInt()
                }
            }
            .commit()
    }

    companion object {
        fun show(manager: androidx.fragment.app.FragmentManager, listener: (MessageReactionEmoji) -> Unit) {
            WidgetEmojiPickerSheet().apply { this.listener = listener }
                .show(manager, WidgetEmojiPickerSheet::class.java.simpleName)
        }
    }
}

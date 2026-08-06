package com.discord.widgets.forums

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.discord.R
import com.discord.api.channel.ForumTag
import com.discord.api.message.reaction.MessageReactionEmoji
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.restapi.RestAPIAbortCodes
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreStream
import com.discord.utilities.dimen.dpToPixels
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.view.text.SimpleDraweeSpanTextView
import com.discord.views.CheckedSetting
import com.discord.views.renderReactionEmoji
import com.discord.widgets.chat.input.emoji.WidgetEmojiPickerSheet
import com.discord.widgets.notice.WidgetNoticeDialog
import com.google.android.material.floatingactionbutton.FloatingActionButton
import rx.Observable

/** Creating or editing one of a thread-only channel's tags. */
class WidgetForumTagEdit : AppFragment(R.layout.widget_forum_tag_edit) {

    private lateinit var emojiView: SimpleDraweeSpanTextView
    private lateinit var emojiPlaceholder: ImageView
    private lateinit var name: EditText
    private lateinit var clear: ImageView
    private lateinit var moderated: CheckedSetting
    private lateinit var save: FloatingActionButton
    private var emoji: MessageReactionEmoji? = null
    private var tag: ForumTag? = null

    private val channelId get() = arguments?.getLong(EXTRA_CHANNEL_ID) ?: mostRecentIntent.getLongExtra(EXTRA_CHANNEL_ID, -1L)
    private val tagId get() = (arguments?.getLong(EXTRA_TAG_ID) ?: mostRecentIntent.getLongExtra(EXTRA_TAG_ID, 0L)).takeIf { it != 0L }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarDisplayHomeAsUpEnabled()
        emojiView = view.findViewById(R.id.forum_tag_edit_emoji)
        emojiPlaceholder = view.findViewById(R.id.forum_tag_edit_emoji_placeholder)
        name = view.findViewById(R.id.forum_tag_edit_name)
        clear = view.findViewById(R.id.forum_tag_edit_clear)
        moderated = view.findViewById(R.id.forum_tag_edit_moderated)
        save = view.findViewById(R.id.forum_tag_edit_save)

        tag = StoreStream.getChannels().getChannel(channelId)?.availableTags?.firstOrNull { it.id == tagId }
        val existing = tag
        setActionBarTitle(if (existing == null) R.string.forum_tag_create else R.string.forum_tag_edit)
        existing?.let {
            name.setText(it.name)
            moderated.isChecked = it.moderated
            emoji = MessageReactionEmoji(it.customEmojiId, it.emojiName, false)
        }

        view.findViewById<TextView>(R.id.forum_tag_edit_remove).apply {
            isVisible = existing != null
            setOnClickListener { confirmRemove() }
        }
        view.findViewById<FrameLayout>(R.id.forum_tag_edit_emoji_wrap).setOnClickListener {
            WidgetEmojiPickerSheet.show(parentFragmentManager) { picked ->
                emoji = picked
                renderEmoji()
                updateSaveVisibility()
            }
        }
        clear.setOnClickListener {
            emoji = null
            name.setText("")
            renderEmoji()
            updateSaveVisibility()
        }
        name.doAfterTextChanged { updateSaveVisibility() }
        moderated.setOnCheckedListener { updateSaveVisibility() }
        save.setOnClickListener { saveChanges() }

        renderEmoji()
        updateSaveVisibility()
    }

    private fun renderEmoji() {
        val current = emoji?.takeIf { it.id != null || it.name != null }
        emojiView.isVisible = current != null
        emojiPlaceholder.isVisible = current == null
        current?.let { renderReactionEmoji(emojiView, it, true, dpToPixels(20)) }
    }

    private fun updateSaveVisibility() {
        val typed = name.text.toString().trim()
        val existing = tag
        val changed = existing == null ||
            typed != existing.name ||
            moderated.isChecked != existing.moderated ||
            emoji?.id != existing.customEmojiId ||
            emoji?.name != existing.emojiName
        save.isVisible = typed.isNotEmpty() && changed
        clear.isVisible = typed.isNotEmpty() || emoji != null
    }

    private fun saveChanges() {
        val typed = name.text.toString().trim()
        if (typed.isEmpty()) return
        val customId = emoji?.id?.toLongOrNull()
        val body = RestAPIParams.ForumTag(
            name = typed,
            emojiId = customId,
            emojiName = emoji?.name.takeIf { customId == null },
            moderated = moderated.isChecked,
        )
        val existingId = tag?.id
        val request = if (existingId == null) {
            RestAPI.api.createForumTag(channelId, body)
        } else {
            RestAPI.api.updateForumTag(channelId, existingId, body)
        }
        submit(request, R.string.forum_tag_edit_error)
    }

    private fun confirmRemove() {
        val existingId = tag?.id ?: return
        WidgetNoticeDialog.show(
            fragmentManager = parentFragmentManager,
            headerText = getString(R.string.forum_tag_remove),
            bodyText = getString(R.string.forum_tag_remove_confirm),
            okText = getString(R.string.yes_text),
            cancelText = getString(R.string.no_text),
            listenerMap = mapOf(R.id.notice_ok to { _: View ->
                submit(RestAPI.api.deleteForumTag(channelId, existingId), R.string.forum_tag_remove_error)
            }),
            dialogTheme = R.attr.notice_theme_positive_red,
        )
    }

    /**
     * The failure the reference calls out by name is a channel left with only moderator tags while
     * posting requires one; anything else falls through to the usual toast.
     */
    private fun submit(request: Observable<*>, errorTitleRes: Int) {
        request.restSubscribeOn().ui(this).appSubscribe(
            WidgetForumTagEdit::class.java,
            context = context,
            errorHandler = { error ->
                val body = when {
                    error.code == RestAPIAbortCodes.NON_MODERATED_TAG_REQUIRED ->
                        getString(R.string.forum_post_non_moderated_tag_required)
                    error.code == RestAPIAbortCodes.INVALID_FORM_BODY && error.messages.keys.any { it.startsWith("emoji") } ->
                        getString(R.string.forum_post_tag_update_emoji_failed)
                    else -> null
                }
                if (body != null) {
                    error.showErrorToasts = false
                    WidgetNoticeDialog.show(
                        fragmentManager = parentFragmentManager,
                        headerText = getString(errorTitleRes),
                        bodyText = body,
                        okText = getString(R.string.okay),
                    )
                }
            },
        ) { appActivity?.onBackPressed() }
    }

    companion object {
        const val EXTRA_CHANNEL_ID = "com.discord.intent.extra.EXTRA_CHANNEL_ID"
        const val EXTRA_TAG_ID = "com.discord.intent.extra.EXTRA_TAG_ID"
        const val MAX_FORUM_TAGS = 20

        fun launch(context: Context, channelId: Long, tagId: Long?) = AppScreenRouter.launch(
            context,
            WidgetForumTagEdit::class.java,
            Intent().putExtra(EXTRA_CHANNEL_ID, channelId).putExtra(EXTRA_TAG_ID, tagId ?: 0L),
        )
    }
}

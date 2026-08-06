package com.discord.widgets.chat.list.adapter

import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.TextView
import com.discord.R
import com.discord.api.permission.Permission
import com.discord.utilities.permissions.PermissionUtils
import com.discord.api.channel.isGuildForumChannel
import com.discord.api.channel.isGuildMediaChannel
import com.discord.api.channel.isThreadOnlyChannel
import com.discord.i18n.Hook
import com.discord.i18n.I18nHooks
import com.discord.stores.StoreStream
import com.discord.utilities.view.extensions.clear
import com.discord.views.ThemedColor
import com.discord.widgets.channels.threads.WidgetThreadDraftArchiveSheet
import com.discord.widgets.forums.WidgetForumPostTagsSheet
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.ThreadDraftFormEntry
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputLayout
import java.util.Locale

/**
 * The inline "name your thread" form that takes over the composer while a thread is being created.
 *
 * The form is a chat row rather than a dialog because the message it is being started from stays
 * visible above it. It writes straight back into the thread draft on every keystroke, so the name,
 * the privacy toggle and the auto-archive choice survive the list being rebuilt underneath it.
 */
class WidgetChatListAdapterItemThreadDraftForm(
    adapter: WidgetChatListAdapter,
) : WidgetChatListItem(R.layout.widget_chat_list_adapter_item_thread_draft_form, adapter) {

    private val icon: ImageView = itemView.findViewById(R.id.thread_icon)
    private val nameInput: TextInputLayout = itemView.findViewById(R.id.thread_name_input)
    private val privateToggle: View = itemView.findViewById(R.id.private_thread_toggle)
    private val privateToggleBadge: TextView = itemView.findViewById(R.id.private_thread_toggle_badge)
    private val privateToggleSwitch: SwitchMaterial =
        itemView.findViewById(R.id.private_thread_toggle_switch)
    private val autoArchiveDuration: TextView = itemView.findViewById(R.id.auto_archive_duration)
    private val tagPicker: TextView = itemView.findViewById(R.id.thread_draft_tags)

    private var nameTextWatcher: TextWatcher? = null

    override fun onConfigure(position: Int, data: ChatListEntry) {
        super.onConfigure(position, data)
        val entry = data as ThreadDraftFormEntry
        configureThreadNameInput(entry)
        configureAutoArchive(entry)
        configureIcon(entry)
        configureNameHint(entry)
        configureTagPicker(entry)
        configurePrivateThreadOption(entry)
        nameInput.requestFocus()
    }

    private fun configureThreadNameInput(entry: ThreadDraftFormEntry) {
        val draft = entry.threadDraftState
        if (draft.threadName.isNullOrEmpty()) nameInput.clear()

        val editText = nameInput.editText
        if (editText != null) {
            // The holder is recycled, so the previous row's watcher has to go before the new one is
            // attached or both would write to the draft.
            editText.removeTextChangedListener(nameTextWatcher)
            val watcher = object : TextWatcher {
                override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    // The "required" error stays only while the field is still empty.
                    val stillEmpty = draft.shouldDisplayNameError && s?.isEmpty() == true
                    StoreStream.getThreadDraft().setDraftState(
                        draft.copy(threadName = s?.toString(), shouldDisplayNameError = stillEmpty),
                    )
                }
            }
            editText.addTextChangedListener(watcher)
            nameTextWatcher = watcher

            editText.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus && draft.shouldDisplayNameError) {
                    StoreStream.getThreadDraft().setDraftState(draft.copy(shouldDisplayNameError = false))
                }
            }
            // The reference hands Next over to the message composer so the first message can be
            // typed straight away; `AppFlexInputViewModel` is not reconstructed, so the action is
            // left to the platform's default focus search.
            editText.imeOptions = EditorInfo.IME_ACTION_NEXT
        }

        nameInput.error = if (draft.shouldDisplayNameError) {
            nameInput.context.getString(R.string.member_verification_form_required_item)
        } else {
            null
        }
        nameInput.isErrorEnabled = draft.shouldDisplayNameError
    }

    private fun configureAutoArchive(entry: ThreadDraftFormEntry) {
        val duration = entry.threadDraftState.autoArchiveDuration
            ?: entry.defaultAutoArchiveDuration
            ?: DEFAULT_AUTO_ARCHIVE_MINUTES
        val openPicker: (View) -> Unit = {
            WidgetThreadDraftArchiveSheet.show(adapter.fragmentManager, entry.guildId)
        }
        autoArchiveDuration.text = I18nHooks.format(
            autoArchiveDuration.context,
            R.string.create_thread_header_2,
            hooks = mapOf(
                "durationHook" to Hook(
                    text = autoArchiveDurationName(autoArchiveDuration.context, duration)
                        .lowercase(Locale.getDefault()),
                    spans = listOf(
                        StyleSpan(Typeface.BOLD),
                        ForegroundColorSpan(
                            ThemedColor.resolve(adapter.context, R.attr.colorHeaderPrimary),
                        ),
                    ),
                    onClick = openPicker,
                ),
            ),
        )
        autoArchiveDuration.setOnClickListener(openPicker)
    }

    /** Only a forum post carries tags, and only when its channel offers any. */
    private fun configureTagPicker(entry: ThreadDraftFormEntry) {
        val tags = entry.parentChannel.availableTags.orEmpty()
        val isForumPost = entry.parentChannel.isThreadOnlyChannel()
        tagPicker.visibility = if (isForumPost && tags.isNotEmpty()) View.VISIBLE else View.GONE
        if (tagPicker.visibility != View.VISIBLE) return

        val selected = entry.threadDraftState.appliedTags
        tagPicker.text = if (selected.isEmpty()) {
            itemView.context.getString(R.string.add_tag_forum_post)
        } else {
            tags.filter { it.id in selected }.joinToString { it.name }
        }
        tagPicker.setOnClickListener {
            WidgetForumPostTagsSheet.show(
                adapter.fragmentManager,
                entry.parentChannel.id,
                selected,
                canManageThreads = PermissionUtils.can(
                    Permission.MANAGE_THREADS,
                    StoreStream.getPermissions().getPermissionsByChannel()[entry.parentChannel.id],
                ),
            )
        }
    }

    /** A forum post is titled, not named, so the reference labels the field "Title" there. */
    private fun configureNameHint(entry: ThreadDraftFormEntry) {
        nameInput.hint = itemView.context.getString(
            if (entry.parentChannel.isThreadOnlyChannel()) {
                R.string.forum_post_title_placeholder_focused
            } else {
                R.string.thread_name
            },
        )
    }

    private fun configureIcon(entry: ThreadDraftFormEntry) {
        icon.setImageResource(
            when {
                entry.parentChannel.isGuildMediaChannel() -> R.drawable.ic_image_library_24dp
                entry.parentChannel.isGuildForumChannel() -> R.drawable.ic_channel_forum_post
                entry.threadDraftState.isPrivate -> R.drawable.ic_thread_locked
                else -> R.drawable.ic_thread
            },
        )
    }

    private fun configurePrivateThreadOption(entry: ThreadDraftFormEntry) {
        val draft = entry.threadDraftState
        if (entry.parentChannel.isThreadOnlyChannel()) {
            // A forum post is always public; there is nothing to choose.
            privateToggle.visibility = View.GONE
            return
        }

        // A thread started from a message is public by definition — it hangs off a message everyone
        // in the channel can already see.
        val canChoose = entry.parentMessageId == null && entry.canSeePrivateThreadOption
        privateToggle.visibility = if (canChoose) View.VISIBLE else View.GONE

        when {
            !entry.canCreatePublicThread && !draft.isPrivate -> {
                // Private is the only kind the viewer may create, so the draft is moved there and
                // the toggle stops responding.
                StoreStream.getThreadDraft().setDraftState(draft.copy(isPrivate = true))
                privateToggle.setOnClickListener(null)
            }
            !entry.canCreatePrivateThread -> {
                // The reference opens the boost upsell here; `PremiumUtils` and the upsell sheet are
                // not reconstructed, so the row is shown but inert.
                privateToggle.setOnClickListener(null)
            }
            else -> privateToggle.setOnClickListener {
                StoreStream.getThreadDraft().setDraftState(draft.copy(isPrivate = !draft.isPrivate))
            }
        }

        privateToggleSwitch.isChecked = draft.isPrivate
        // The boost-tier badge next to the label comes from `PremiumUtils.BoostFeatureBadgeData`,
        // which does not exist yet; without it there is nothing to show.
        privateToggleBadge.visibility = View.GONE
    }

    /** The translated name of an auto-archive duration, which the API states in minutes. */
    private fun autoArchiveDurationName(context: Context, minutes: Int): String = context.getString(
        when (minutes) {
            ONE_HOUR_MINUTES -> R.string.auto_archive_duration_1_hour
            THREE_DAYS_MINUTES -> R.string.auto_archive_duration_3_days
            ONE_WEEK_MINUTES -> R.string.auto_archive_duration_1_week
            else -> R.string.auto_archive_duration_24_hours
        },
    )

    private companion object {
        private const val ONE_HOUR_MINUTES = 60
        private const val DEFAULT_AUTO_ARCHIVE_MINUTES = 1440
        private const val THREE_DAYS_MINUTES = 4320
        private const val ONE_WEEK_MINUTES = 10080
    }
}

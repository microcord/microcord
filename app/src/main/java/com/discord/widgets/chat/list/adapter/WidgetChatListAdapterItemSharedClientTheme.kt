package com.discord.widgets.chat.list.adapter

import android.view.LayoutInflater
import com.discord.R
import com.discord.app.AppFragment
import com.discord.databinding.WidgetChatListAdapterItemSharedClientThemeBinding
import com.discord.utilities.theme.ThemeRuntime
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.SharedClientThemeEntry
import com.discord.widgets.settings.themes.ThemeActions

/**
 * A Nitro custom theme shared in a message, as desktop's card: the gradient, Preview, which shows
 * it on this device until reverted, and Apply, which keeps it like a picked one. While this theme
 * is previewed, Preview becomes Revert. Both are off where custom themes cannot run.
 */
class WidgetChatListAdapterItemSharedClientTheme private constructor(
    adapter: WidgetChatListAdapter,
    private val binding: WidgetChatListAdapterItemSharedClientThemeBinding,
) : WidgetChatListItem(binding.root, adapter) {

    constructor(adapter: WidgetChatListAdapter) : this(
        adapter,
        WidgetChatListAdapterItemSharedClientThemeBinding.inflate(
            LayoutInflater.from(adapter.recycler.context),
            adapter.recycler,
            false,
        ),
    )

    override fun onConfigure(position: Int, data: ChatListEntry) {
        super.onConfigure(position, data)
        val entry = data as SharedClientThemeEntry
        configureCellHighlight(
            entry.message,
            binding.chatListAdapterItemHighlightedBg,
            binding.chatListAdapterItemGutterBg,
            entry.isPendingReply,
        )
        binding.sharedClientThemePreview.setImageBitmap(entry.theme.render(PREVIEW_WIDTH, PREVIEW_HEIGHT))
        val supported = ThemeRuntime.isSupported
        val store = if (supported) ThemeRuntime.store(itemView.context) else null
        val inUse = store != null && entry.theme.sameAs(store.clientTheme)
        val previewing = inUse && store?.previewRestore != null
        val activity = { (adapter.appPermissionsRequests as? AppFragment)?.appActivity }
        binding.sharedClientThemePreviewButton.isEnabled = supported && (previewing || !inUse)
        binding.sharedClientThemePreviewButton.setText(
            if (previewing) R.string.manticord_custom_theme_shared_revert else R.string.manticord_custom_theme_shared_preview,
        )
        binding.sharedClientThemePreviewButton.setOnClickListener {
            if (previewing) ThemeActions.endPreview(activity()) else ThemeActions.previewClientTheme(activity(), entry.theme)
        }
        binding.sharedClientThemeApply.isEnabled = supported && (previewing || !inUse)
        binding.sharedClientThemeApply.setOnClickListener { ThemeActions.applyClientTheme(activity(), entry.theme) }
    }

    private companion object {
        /** Small: a gradient has no detail, and the image view stretches it to the card. */
        const val PREVIEW_WIDTH = 240
        const val PREVIEW_HEIGHT = 60
    }
}

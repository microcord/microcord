package com.discord.widgets.chat.list.adapter

import android.view.View
import android.widget.LinearLayout
import androidx.lifecycle.findViewTreeLifecycleOwner
import com.discord.R
import com.discord.api.botuikit.SelectItem
import com.discord.widgets.botuikit.views.ComponentActionListener
import com.discord.widgets.botuikit.views.select.ComponentContext
import com.discord.widgets.botuikit.views.select.SelectComponentBottomSheet
import com.discord.widgets.chat.list.entries.BotUiComponentEntry
import com.discord.widgets.chat.list.entries.ChatListEntry

/**
 * The buttons and select menus a bot attached to its message.
 *
 * The row itself owns no widgetry: `ComponentProvider` turns each [com.discord.models.botuikit.MessageComponent]
 * into the matching view and the provider is shared across the whole list, so scrolling reuses the
 * views instead of re-inflating an action row per bind. What the row does own is the interaction —
 * every click leaves here as one `onBotUiComponentClicked` carrying the message, the component
 * index and the values the user chose.
 */
class WidgetChatListAdapterItemBotComponentRow(
    adapter: WidgetChatListAdapter,
) : WidgetChatListItem(R.layout.widget_chat_list_adapter_item_bot_component_row, adapter),
    ComponentActionListener {

    private val highlightedBackground: View =
        itemView.findViewById(R.id.chat_list_adapter_item_highlighted_bg)
    private val gutterBackground: View = itemView.findViewById(R.id.chat_list_adapter_item_gutter_bg)
    private val componentRoot: LinearLayout =
        itemView.findViewById(R.id.chat_list_adapter_item_component_root)

    private lateinit var entry: BotUiComponentEntry

    override fun onConfigure(position: Int, data: ChatListEntry) {
        super.onConfigure(position, data)
        val botEntry = data as BotUiComponentEntry
        entry = botEntry
        configureCellHighlight(botEntry.message, highlightedBackground, gutterBackground)

        val views = botEntry.messageComponents.mapIndexedNotNull { index, component ->
            adapter.botUiComponentProvider
                .getConfiguredComponentView(this, component, componentRoot, index)
        }
        componentRoot.replaceViews(views)
    }

    override fun onButtonComponentClick(componentIndex: Int, customId: String) {
        adapter.onBotUiComponentClicked(
            applicationId = entry.applicationId,
            messageId = entry.message.id,
            messageFlags = entry.message.flags,
            componentIndex = componentIndex,
            customId = customId,
            values = null,
        )
    }

    /**
     * Opens the real select sheet.
     *
     * The result comes back through the fragment-result API rather than a captured callback: the
     * sheet outlives this view holder (recycling, rotation, process death), so anything it captured
     * would either be stale or a leak. The listener is registered against the item view's lifecycle
     * owner — the chat list's view — which is exactly the scope in which delivering a result still
     * makes sense.
     */
    override fun onSelectComponentClick(
        componentIndex: Int,
        customId: String,
        placeholder: String?,
        options: List<SelectItem>,
        selectedItems: List<SelectItem>,
        minOptionsToSelect: Int,
        maxOptionsToSelect: Int,
        emojiAnimationsEnabled: Boolean,
    ) {
        val lifecycleOwner = itemView.findViewTreeLifecycleOwner() ?: return
        adapter.fragmentManager.setFragmentResultListener(
            SelectComponentBottomSheet.RESULT_KEY,
            lifecycleOwner,
        ) { _, result ->
            val context =
                result.getSerializable(SelectComponentBottomSheet.RESULT_COMPONENT_CONTEXT) as? ComponentContext
                    ?: return@setFragmentResultListener
            adapter.onBotUiComponentClicked(
                applicationId = context.applicationId,
                messageId = context.messageId,
                messageFlags = context.messageFlags,
                componentIndex = result.getInt(SelectComponentBottomSheet.RESULT_COMPONENT_INDEX),
                customId = result.getString(SelectComponentBottomSheet.RESULT_CUSTOM_ID),
                values = result.getStringArrayList(SelectComponentBottomSheet.RESULT_VALUES),
            )
        }

        SelectComponentBottomSheet.show(
            fragmentManager = adapter.fragmentManager,
            componentContext = ComponentContext(
                guildId = entry.guildId,
                messageId = entry.message.id,
                channelId = entry.message.channelId,
                messageFlags = entry.message.flags,
                applicationId = entry.applicationId,
            ),
            componentIndex = componentIndex,
            customId = customId,
            placeholder = placeholder,
            min = minOptionsToSelect,
            max = maxOptionsToSelect,
            options = options,
            selectedItems = selectedItems,
            emojiAnimationsEnabled = emojiAnimationsEnabled,
        )
    }
}

package com.discord.widgets.chat.list.adapter

import android.view.View
import android.widget.LinearLayout
import androidx.lifecycle.findViewTreeLifecycleOwner
import com.discord.R
import com.discord.api.botuikit.ComponentType
import com.discord.api.botuikit.SelectItem
import com.discord.api.channel.getDisplayName
import com.discord.api.channel.isGuildTextyChannel
import com.discord.stores.StoreStream
import com.discord.utilities.user.UserUtils.displayName
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
    private var renderKey: Any? = null
    override val componentEntry: BotUiComponentEntry get() = entry

    override fun onConfigure(position: Int, data: ChatListEntry) {
        super.onConfigure(position, data)
        val botEntry = data as BotUiComponentEntry
        entry = botEntry
        configureCellHighlight(botEntry.message, highlightedBackground, gutterBackground, botEntry.isPendingReply)
        val nextRenderKey = listOf(botEntry.message.id, botEntry.messageComponents, botEntry.messageState)
        if (renderKey == nextRenderKey) return
        renderKey = nextRenderKey

        val views = botEntry.messageComponents.mapIndexedNotNull { index, component ->
            adapter.botUiComponentProvider
                .getConfiguredComponentView(this, component, componentRoot, index)
        }
        componentRoot.replaceViews(views)
    }

    /**
     * A snowflake select declares no options; the choices are whatever the guild currently holds.
     */
    private fun snowflakeOptions(componentType: ComponentType): List<SelectItem> {
        val guildId = entry.guildId ?: return emptyList()
        return when (componentType) {
            ComponentType.ROLE_SELECT -> guildRoleOptions(guildId)
            ComponentType.CHANNEL_SELECT -> guildChannelOptions(guildId)
            ComponentType.USER_SELECT -> guildMemberOptions(guildId)
            ComponentType.MENTIONABLE_SELECT -> guildMemberOptions(guildId) + guildRoleOptions(guildId)
            else -> emptyList()
        }
    }

    private fun guildRoleOptions(guildId: Long): List<SelectItem> =
        StoreStream.getGuilds().getRoles()[guildId].orEmpty().values
            .sortedByDescending { it.position }
            .map { option(it.name, it.id) }

    private fun guildChannelOptions(guildId: Long): List<SelectItem> =
        StoreStream.getChannels().getAllChannels().values
            .filter { it.guildId == guildId && it.isGuildTextyChannel() }
            .sortedBy { it.position }
            .map { option(it.getDisplayName(), it.id) }

    private fun guildMemberOptions(guildId: Long): List<SelectItem> {
        val users = StoreStream.getUsers().getUsers()
        return StoreStream.getGuilds().getMembers()[guildId].orEmpty().values
            .mapNotNull { member ->
                val user = users[member.userId] ?: return@mapNotNull null
                option(member.identityNick() ?: user.displayName, member.userId)
            }
            .sortedBy { it.label }
    }

    private fun option(label: String, id: Long) =
        SelectItem(label = label, value = id.toString(), emoji = null, description = null, default = false)

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
        componentType: ComponentType,
        customId: String,
        placeholder: String?,
        options: List<SelectItem>,
        selectedItems: List<SelectItem>,
        minOptionsToSelect: Int,
        maxOptionsToSelect: Int,
        emojiAnimationsEnabled: Boolean,
        defaultValues: List<String>,
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
                componentType = componentType,
            )
        }

        val resolvedOptions = options.ifEmpty { snowflakeOptions(componentType) }
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
            options = resolvedOptions,
            selectedItems = selectedItems.ifEmpty { resolvedOptions.filter { it.value in defaultValues } },
            emojiAnimationsEnabled = emojiAnimationsEnabled,
        )
    }
}

package com.discord.widgets.settings

import android.view.View
import com.discord.R
import com.discord.api.botuikit.ComponentType
import com.discord.api.botuikit.SelectItem
import com.discord.app.AppFragment
import com.discord.databinding.ViewBotUiKitSelectBinding
import com.discord.databinding.WidgetSettingsBotUiKitBinding
import com.discord.models.domain.ModelUserRelationship
import com.discord.models.botuikit.ActionInteractionComponentState
import com.discord.models.botuikit.SelectMessageComponent
import com.discord.stores.StoreStream
import com.discord.utilities.textprocessing.AstRenderer
import com.discord.utilities.textprocessing.DiscordParser
import com.discord.utilities.textprocessing.MessageRenderContext
import com.discord.widgets.botuikit.ComponentProvider
import com.discord.widgets.botuikit.views.ComponentActionListener
import com.discord.widgets.botuikit.views.select.ComponentContext
import com.discord.widgets.botuikit.views.select.SelectComponentBottomSheet

class WidgetSettingsBotUiKit : AppFragment(R.layout.widget_settings_bot_ui_kit) {
    private var demoGuildId: Long? = null

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle(R.string.bot_ui_kit)
        setActionBarDisplayHomeAsUpEnabled()
        val binding = WidgetSettingsBotUiKitBinding.bind(view)
        binding.botUiKitTextDisplay.text = AstRenderer.render(
            DiscordParser.parse("hello world! :eyes: **bold** `code` https://discord.com [google](https://google.com) ||spoiler|| <t:1755730638:t>"),
            MessageRenderContext(requireContext(), myId = 0L, isAnimationEnabled = false),
        )
        val selectionUpdates = mutableMapOf<String, (List<String>) -> Unit>()
        parentFragmentManager.setFragmentResultListener(SelectComponentBottomSheet.RESULT_KEY, viewLifecycleOwner) { _, result ->
            val id = result.getString(SelectComponentBottomSheet.RESULT_CUSTOM_ID) ?: return@setFragmentResultListener
            selectionUpdates[id]?.invoke(result.getStringArrayList(SelectComponentBottomSheet.RESULT_VALUES) ?: emptyList())
        }
        val rolesByGuild = StoreStream.getGuilds().getRoles()
        demoGuildId = StoreStream.getGuildSelected().getSelectedGuildId().takeIf(rolesByGuild::containsKey) ?: rolesByGuild.keys.firstOrNull()
        val roles = demoGuildId?.let(rolesByGuild::get).orEmpty().values.sortedByDescending { it.position }.take(12)
        val friendIds = StoreStream.getUserRelationships().getRelationships().filterValues { it == ModelUserRelationship.TYPE_FRIEND }.keys
        val users = StoreStream.getUsers().getUsers(friendIds).values.sortedBy { it.username.lowercase() }.take(12)
        val userItems = users.map { SelectItem(it.username, it.id.toString(), null, null, false) }
        val roleItems = roles.map { SelectItem(it.name, it.id.toString(), null, null, false) }
        val channelItems = demoGuildId?.let { StoreStream.getChannels().getChannelsForGuild(it).values }
            .orEmpty().filter { !it.name.isNullOrBlank() }.sortedBy { it.position }.take(12)
            .map { SelectItem("#${it.name}", it.id.toString(), null, null, false) }
        val resets = listOf(
            configure(binding.botUiKitStringSelect, "String Select", ComponentType.SELECT, listOf(SelectItem("test with a long label", "test", null, null, false), SelectItem("test 2 with a long label", "test2", null, "with description!", false), SelectItem("star", "star", null, null, false), SelectItem("advaith", "advaith", null, null, false)), selectionUpdates),
            configure(binding.botUiKitUserSelect, "User Select", ComponentType.USER_SELECT, userItems, selectionUpdates),
            configure(binding.botUiKitRoleSelect, "Role Select", ComponentType.ROLE_SELECT, roleItems, selectionUpdates),
            configure(binding.botUiKitMentionableSelect, "Mentionable Select", ComponentType.MENTIONABLE_SELECT, userItems + roleItems, selectionUpdates),
            configure(binding.botUiKitChannelSelect, "Channel Select", ComponentType.CHANNEL_SELECT, channelItems, selectionUpdates),
        )
        binding.botUiKitReset.setOnClickListener { resets.forEach { it() } }
    }

    private fun configure(
        binding: ViewBotUiKitSelectBinding,
        title: String,
        type: ComponentType,
        options: List<SelectItem>,
        selectionUpdates: MutableMap<String, (List<String>) -> Unit>,
    ): () -> Unit {
        var multi = false
        val selected = linkedSetOf<SelectItem>()
        val customId = title.lowercase().replace(' ', '_')
        binding.botUiKitSelectTitle.text = title

        fun render() {
            binding.botUiKitSelectValue.text = if (selected.isEmpty()) "Nothing selected" else "Selected values: ${selected.joinToString { it.label }}"
            binding.botUiKitSelectToggle.text = "Toggle Multi Select (${if (multi) "on" else "off"})"
            binding.botUiKitSelectComponent.root.configure(
                SelectMessageComponent(type, type.type, ActionInteractionComponentState.Enabled, customId, "Choose...", 0, if (multi) 9 else 1, options, selected.toList(), false),
                ComponentProvider(requireContext()),
                object : ComponentActionListener {
                    override fun onButtonComponentClick(componentIndex: Int, customId: String) = Unit
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
                        openSelector(type, customId, options, selectedItems, maxOptionsToSelect)
                    }
                },
            )
        }

        selectionUpdates[customId] = { values ->
            selected.clear()
            values.forEach { value ->
                selected += options.firstOrNull { it.value == value || it.label == value }
                    ?: SelectItem(value, value, null, null, false)
            }
            render()
        }
        binding.botUiKitSelectToggle.setOnClickListener {
            multi = !multi
            if (!multi && selected.size > 1) selected.retainAll(listOf(selected.last()))
            render()
        }
        render()
        return {
            multi = false
            selected.clear()
            render()
        }
    }

    private fun openSelector(
        type: ComponentType,
        customId: String,
        options: List<SelectItem>,
        selected: List<SelectItem>,
        max: Int,
    ) = SelectComponentBottomSheet.show(
            parentFragmentManager,
            ComponentContext(demoGuildId, 0L, 0L, null, 0L),
            type.type,
            customId,
            "Choose...",
            0,
            max,
            options,
            selected,
            false,
            type,
        )
}

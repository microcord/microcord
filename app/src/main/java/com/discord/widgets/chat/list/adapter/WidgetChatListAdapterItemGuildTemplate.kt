package com.discord.widgets.chat.list.adapter

import android.view.View
import android.widget.TextView
import com.discord.R
import com.discord.i18n.i18nRenderPlural
import com.discord.models.domain.ModelGuildTemplate
import com.discord.stores.StoreGuildTemplates
import com.discord.stores.StoreStream
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import com.discord.utilities.view.extensions.setTextAndVisibilityBy
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.GuildTemplateEntry
import com.discord.widgets.guilds.create.WidgetGuildClone
import com.google.android.material.button.MaterialButton
import rx.Subscription

/**
 * A `discord.new` server-template link.
 *
 * Three states, the same shape as the gift card: **loading** while the code is resolved,
 * **resolved** with the template's name and how many servers have been created from it, or
 * **invalid** when the code is unknown or has been deleted.
 */
class WidgetChatListAdapterItemGuildTemplate(
    adapter: WidgetChatListAdapter,
) : WidgetChatListItem(R.layout.widget_chat_list_adapter_item_guild_template, adapter) {

    private val name: TextView = itemView.findViewById(R.id.item_guild_template_name)
    private val subtext: TextView = itemView.findViewById(R.id.item_guild_template_subtext)
    private val joinButton: MaterialButton =
        itemView.findViewById(R.id.item_guild_template_join_button)

    private var modelSubscription: Subscription? = null
    override val subscription: Subscription? get() = modelSubscription

    override fun onConfigure(position: Int, data: ChatListEntry) {
        super.onConfigure(position, data)
        val entry = data as GuildTemplateEntry

        configureLoadingUI()
        val templates = StoreStream.getGuildTemplates()
        templates.maybeInitTemplateState(entry.guildTemplateCode)
        templates.observeGuildTemplate(entry.guildTemplateCode)
            .ui()
            .appSubscribe(
                errorClass = WidgetChatListAdapterItemGuildTemplate::class.java,
                subscriptionHandler = { modelSubscription = it },
                errorHandler = { configureInvalidUI() },
            ) { state ->
                when (state) {
                    StoreGuildTemplates.GuildTemplateState.None,
                    StoreGuildTemplates.GuildTemplateState.Loading,
                    StoreGuildTemplates.GuildTemplateState.LoadFailed -> configureLoadingUI()
                    StoreGuildTemplates.GuildTemplateState.Invalid -> configureInvalidUI()
                    is StoreGuildTemplates.GuildTemplateState.Resolved ->
                        configureResolvedUI(state.guildTemplate)
                }
            }
    }

    private fun configureLoadingUI() {
        name.setText(R.string.guild_template_resolving_title)
        subtext.visibility = View.GONE
        joinButton.visibility = View.GONE
    }

    private fun configureInvalidUI() {
        name.setText(R.string.guild_template_invalid_title)
        subtext.setText(R.string.guild_template_invalid_subtitle)
        subtext.visibility = View.VISIBLE
        joinButton.visibility = View.GONE
    }

    private fun configureResolvedUI(template: ModelGuildTemplate) {
        name.text = template.name
        subtext.setTextAndVisibilityBy(
            subtext.i18nRenderPlural(
                R.plurals.guild_template_usages_usageCount,
                template.usageCount,
                "usageCount" to template.usageCount,
            ),
        )
        joinButton.visibility = View.VISIBLE
        joinButton.setOnClickListener {
            WidgetGuildClone.show(itemView.context, template.code, GUILD_TEMPLATE_LOCATION)
        }
    }

    private companion object {
        const val GUILD_TEMPLATE_LOCATION = "Guild Template Embed"
    }
}

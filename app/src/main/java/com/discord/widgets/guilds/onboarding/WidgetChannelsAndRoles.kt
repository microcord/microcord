package com.discord.widgets.guilds.onboarding

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.channel.isCategory
import com.discord.api.channel.isAnnouncementChannel
import com.discord.api.channel.isGuildVocalChannel
import com.discord.api.channel.isThread
import com.discord.api.guild.GuildOnboardingPrompt
import com.discord.api.guild.GuildOnboardingPromptOption
import com.discord.api.role.GuildRole
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.databinding.WidgetChannelsAndRolesBinding
import com.discord.databinding.WidgetChannelsAndRolesItemBinding
import com.discord.databinding.WidgetChannelsAndRolesHeaderBinding
import com.discord.databinding.WidgetGuildOnboardingOptionBinding
import com.discord.models.domain.ModelNotificationSettings
import com.discord.stores.StoreGuildOnboarding
import com.discord.stores.StoreStream
import com.discord.utilities.rx.ui
import rx.Observable

class WidgetChannelsAndRoles : AppFragment(R.layout.widget_channels_and_roles) {
    private enum class Tab { CUSTOMIZE, BROWSE }

    private data class Model(
        val onboarding: StoreGuildOnboarding.State,
        val channels: Map<Long, Channel>,
        val roles: Map<Long, GuildRole>,
        val settings: ModelNotificationSettings,
    )

    private data class Row(
        val title: String,
        val subtitle: String? = null,
        val checked: Boolean? = null,
        val header: Boolean = false,
        val followCategory: Boolean = false,
        val icon: Int? = null,
        val first: Boolean = false,
        val last: Boolean = false,
        val option: GuildOnboardingPromptOption? = null,
        val onClick: (() -> Unit)? = null,
    )

    private var _binding: WidgetChannelsAndRolesBinding? = null
    private val binding get() = requireNotNull(_binding)
    private val rows = RowsAdapter()
    private val pendingChannels = mutableMapOf<Long, Boolean>()
    private var tab = Tab.CUSTOMIZE
    private var query = ""
    private var model: Model? = null
    private var responsesDirty = false

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        _binding = WidgetChannelsAndRolesBinding.bind(view)
        setActionBarDisplayHomeAsUpEnabled()
        binding.list.adapter = rows
        binding.customizeTab.setOnClickListener { setTab(Tab.CUSTOMIZE) }
        binding.browseTab.setOnClickListener { setTab(Tab.BROWSE) }
        binding.search.doAfterTextChanged {
            query = it?.toString().orEmpty()
            renderRows()
        }
        StoreStream.getGuildOnboarding().fetch(guildId())
        renderTabs()
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        val guildId = guildId()
        Observable.combineLatest(
            StoreStream.getGuildOnboarding().observe(guildId),
            StoreStream.getChannels().observeChannelsForGuild(guildId),
            StoreStream.getGuilds().observeRoles(guildId),
            StoreStream.getUserGuildSettings().observeGuildSettings(guildId),
        ) { onboarding, channels, roles, settings -> Model(onboarding, channels, roles, settings) }
            .ui(this)
            .subscribe {
                model = it
                clearConfirmedPending(it)
                if (it.onboarding.customizationPrompts.isEmpty() && tab == Tab.CUSTOMIZE && !it.onboarding.loading) {
                    tab = Tab.BROWSE
                    renderTabs()
                }
                renderRows()
            }
    }

    override fun onDestroyView() {
        binding.list.adapter = null
        _binding = null
        super.onDestroyView()
    }

    override fun onStop() {
        if (responsesDirty) {
            responsesDirty = false
            StoreStream.getGuildOnboarding().syncResponses(guildId())
        }
        super.onStop()
    }

    private fun setTab(tab: Tab) {
        this.tab = tab
        renderTabs()
        renderRows()
    }

    private fun renderTabs() {
        if (_binding == null) return
        setActionBarTitle(
            if (tab == Tab.BROWSE) R.string.guild_onboarding_browse_channels
            else R.string.guild_onboarding_channels_and_roles,
        )
        binding.customizeTab.isSelected = tab == Tab.CUSTOMIZE
        binding.browseTab.isSelected = tab == Tab.BROWSE
        binding.customizeTab.alpha = if (tab == Tab.CUSTOMIZE) 1f else 0.6f
        binding.browseTab.alpha = if (tab == Tab.BROWSE) 1f else 0.6f
        binding.searchWrap.visibility = if (tab == Tab.BROWSE) View.VISIBLE else View.GONE
    }

    private fun renderRows() {
        if (_binding == null) return
        val current = model
        binding.loading.visibility = if (current?.onboarding?.loading != false) View.VISIBLE else View.GONE
        binding.error.visibility = if (current?.onboarding?.error == true) View.VISIBLE else View.GONE
        if (current == null || current.onboarding.loading) return
        val items = if (tab == Tab.CUSTOMIZE) customizeRows(current) else browseRows(current)
        rows.submit(items)
        binding.empty.apply {
            visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            setText(
                if (tab == Tab.CUSTOMIZE) R.string.guild_onboarding_no_customization
                else R.string.guild_onboarding_no_channels,
            )
        }
    }

    private fun customizeRows(model: Model): List<Row> = buildList {
        model.onboarding.customizationPrompts.forEach { prompt ->
            add(Row(prompt.title + if (prompt.required) " *" else "", header = true))
            prompt.options.forEach { option ->
                add(
                    Row(
                        title = option.title,
                        subtitle = optionSubtitle(option, model.channels, model.roles),
                        checked = option.id in model.onboarding.selectedOptionIds,
                        option = option,
                        onClick = {
                            val selected = option.id !in StoreStream.getGuildOnboarding().get(guildId()).selectedOptionIds
                            StoreStream.getGuildOnboarding().selectOption(guildId(), prompt.id, option.id, selected)
                            responsesDirty = true
                        },
                    ),
                )
            }
        }
    }

    private fun browseRows(model: Model): List<Row> {
        val channels = model.channels.values.filterNot(Channel::isThread)
        val categories = channels.filter(Channel::isCategory).sortedBy(Channel::position)
        val children = channels.filterNot(Channel::isCategory).groupBy(Channel::parentId)
        return buildList {
            categories.forEach { category ->
                val allChildren = children[category.id].orEmpty().sortedBy(Channel::position)
                val visibleChildren = allChildren.filter(::matchesQuery)
                if (visibleChildren.isEmpty() && !matchesQuery(category)) return@forEach
                val selected = isSelected(category, model)
                add(
                    Row(
                        title = category.name.orEmpty(),
                        checked = selected,
                        header = true,
                        followCategory = true,
                        onClick = { toggleChannels(listOf(category) + allChildren, !selected, model) },
                    ),
                )
                visibleChildren.forEachIndexed { index, channel ->
                    add(channelRow(channel, model, index == 0, index == visibleChildren.lastIndex))
                }
            }
            val uncategorized = children[0L].orEmpty().sortedBy(Channel::position).filter(::matchesQuery)
            if (uncategorized.isNotEmpty()) {
                add(Row(getString(R.string.guild_onboarding_uncategorized), header = true))
                uncategorized.forEachIndexed { index, channel ->
                    add(channelRow(channel, model, index == 0, index == uncategorized.lastIndex))
                }
            }
        }
    }

    private fun channelRow(
        channel: Channel,
        model: Model,
        first: Boolean,
        last: Boolean,
    ): Row {
        val selected = isSelected(channel, model)
        return Row(
            title = channel.name.orEmpty(),
            subtitle = channel.topic?.takeIf(String::isNotBlank),
            checked = selected,
            icon = channelIcon(channel),
            first = first,
            last = last,
            onClick = { toggleChannels(listOf(channel), !selected, model) },
        )
    }

    private fun toggleChannels(channels: List<Channel>, selected: Boolean, model: Model) {
        val affectedIds = channels.mapTo(hashSetOf(), Channel::id)
        val selections = if (!model.settings.isOptInChannelsEnabled) {
            model.channels.values.filterNot(Channel::isThread).associate { it.id to (it.id !in affectedIds || selected) }
        } else {
            affectedIds.associateWith { selected }
        }
        pendingChannels.putAll(selections)
        renderRows()
        StoreStream.getUserGuildSettings().setChannelsOptedIn(
            requireContext(),
            guildId(),
            selections,
            enableOptIn = !model.settings.isOptInChannelsEnabled,
        )
    }

    private fun channelIcon(channel: Channel): Int = when {
        channel.isAnnouncementChannel() -> R.drawable.ic_channel_announcements_16dp
        channel.isGuildVocalChannel() -> R.drawable.ic_channel_voice_grey_18dp
        else -> R.drawable.ic_channel_text_grey_18dp
    }

    private fun isSelected(channel: Channel, model: Model): Boolean {
        pendingChannels[channel.id]?.let { return it }
        if (!model.settings.isOptInChannelsEnabled) return true
        if (model.settings.getChannelOverride(channel.id)?.isOptedIn == true) return true
        return channel.parentId.takeUnless { it == 0L }?.let { parentId ->
            pendingChannels[parentId] ?: model.settings.getChannelOverride(parentId)?.isOptedIn
        } == true
    }

    private fun clearConfirmedPending(model: Model) {
        if (!model.settings.isOptInChannelsEnabled) return
        pendingChannels.entries.removeAll { (id, selected) ->
            val serverSelected = model.settings.getChannelOverride(id)?.isOptedIn == true
            serverSelected == selected
        }
    }

    private fun optionSubtitle(
        option: GuildOnboardingPromptOption,
        channels: Map<Long, Channel>,
        roles: Map<Long, GuildRole>,
    ): String? = buildList {
        option.description?.takeIf(String::isNotBlank)?.let(::add)
        option.roleIds.mapNotNull(roles::get).map(GuildRole::name).takeIf(List<String>::isNotEmpty)?.let {
            add(getString(R.string.guild_onboarding_receive_roles, it.joinToString(", ")))
        }
        option.channelIds.mapNotNull(channels::get).mapNotNull(Channel::name).takeIf(List<String>::isNotEmpty)?.let {
            add(getString(R.string.guild_onboarding_receive_channels, it.joinToString(", ") { name -> "#$name" }))
        }
    }.joinToString("\n").takeIf(String::isNotEmpty)

    private fun matchesQuery(channel: Channel): Boolean =
        query.isBlank() || channel.name?.contains(query, ignoreCase = true) == true

    private fun guildId() = mostRecentIntent.getLongExtra(ARG_GUILD_ID, 0L)

    private class RowsAdapter : RecyclerView.Adapter<RowHolder>() {
        private var items = emptyList<Row>()

        fun submit(items: List<Row>) {
            this.items = items
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size
        override fun getItemViewType(position: Int) = when {
            items[position].header -> 1
            items[position].option != null -> 2
            else -> 0
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder = when (viewType) {
            1 -> HeaderHolder(WidgetChannelsAndRolesHeaderBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            2 -> OnboardingOptionHolder(
                WidgetGuildOnboardingOptionBinding.inflate(LayoutInflater.from(parent.context), parent, false),
            )
            else -> ItemHolder(WidgetChannelsAndRolesItemBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }
        override fun onBindViewHolder(holder: RowHolder, position: Int) = holder.bind(items[position])
        override fun onViewRecycled(holder: RowHolder) {
            holder.recycle()
            super.onViewRecycled(holder)
        }
    }

    private abstract class RowHolder(view: View) : RecyclerView.ViewHolder(view) {
        abstract fun bind(row: Row)
        open fun recycle() = Unit
    }

    private class HeaderHolder(private val binding: WidgetChannelsAndRolesHeaderBinding) : RowHolder(binding.root) {
        override fun bind(row: Row) {
            binding.title.text = row.title
            binding.checkbox.visibility = if (row.checked == null) View.GONE else View.VISIBLE
            binding.checkbox.isChecked = row.checked == true
            binding.actionLabel.visibility = if (row.followCategory) View.VISIBLE else View.GONE
            binding.root.setOnClickListener { row.onClick?.invoke() }
            binding.checkbox.setOnClickListener { row.onClick?.invoke() }
        }
    }

    private class ItemHolder(private val binding: WidgetChannelsAndRolesItemBinding) : RowHolder(binding.root) {
        override fun bind(row: Row) {
            binding.title.text = row.title
            binding.subtitle.text = row.subtitle
            binding.subtitle.visibility = if (row.subtitle == null) View.GONE else View.VISIBLE
            binding.checkbox.visibility = if (row.checked == null) View.GONE else View.VISIBLE
            binding.checkbox.isChecked = row.checked == true
            binding.icon.visibility = if (row.icon == null) View.GONE else View.VISIBLE
            row.icon?.let(binding.icon::setImageResource)
            binding.icon.imageTintList = android.content.res.ColorStateList.valueOf(
                com.discord.views.ThemedColor.resolve(binding.root.context, R.attr.colorInteractiveNormal),
            )
            binding.divider.visibility = if (row.last) View.GONE else View.VISIBLE
            binding.root.setBackgroundResource(
                when {
                    row.first && row.last -> R.drawable.bg_channels_roles_card_single
                    row.first -> R.drawable.bg_channels_roles_card_top
                    row.last -> R.drawable.bg_channels_roles_card_bottom
                    else -> R.drawable.bg_channels_roles_card_middle
                },
            )
            binding.root.isClickable = row.onClick != null
            binding.root.setOnClickListener { row.onClick?.invoke() }
            binding.checkbox.setOnClickListener { row.onClick?.invoke() }
        }
    }

    private class OnboardingOptionHolder(
        private val binding: WidgetGuildOnboardingOptionBinding,
    ) : RowHolder(binding.root) {
        private val optionBinder = GuildOnboardingOptionBinder(binding)

        override fun bind(row: Row) {
            optionBinder.bind(
                option = requireNotNull(row.option),
                selected = row.checked == true,
                description = row.subtitle,
                onClick = { row.onClick?.invoke() },
            )
        }

        override fun recycle() = optionBinder.recycle()
    }

    companion object {
        private const val ARG_GUILD_ID = "ARG_GUILD_ID"

        fun launch(context: Context, guildId: Long) {
            AppScreenRouter.launch(
                context,
                WidgetChannelsAndRoles::class.java,
                Intent().putExtra(ARG_GUILD_ID, guildId),
            )
        }
    }
}

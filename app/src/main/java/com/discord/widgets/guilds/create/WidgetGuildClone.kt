package com.discord.widgets.guilds.create

import android.content.Context
import android.content.Intent
import android.util.TypedValue
import android.view.View
import com.discord.R
import com.discord.app.AppScreenRouter
import com.discord.databinding.WidgetGuildCloneBinding
import com.discord.stores.StoreGuildTemplates
import com.discord.stores.StoreStream
import com.discord.utilities.color.setDrawableColor

class WidgetGuildClone : WidgetGuildCreate(R.layout.widget_guild_clone) {

    private val binding: WidgetGuildCloneBinding
        get() = WidgetGuildCloneBinding.bind(requireView())

    override val views: GuildCreateCloneViews
        get() = GuildCreateCloneViews.from(binding)

    override fun createViewModelFactory() = WidgetGuildCreateViewModel(
        defaultGuildNameFormatRes = R.string.create_server_default_server_name_format,
        guildTemplateCode = mostRecentIntent.getStringExtra(INTENT_GUILD_TEMPLATE_CODE),
        showChannelPrompt = false,
        analyticsLocation = options.analyticsLocation,
    )

    override fun configureUi(state: WidgetGuildCreateViewModel.ViewState) {
        super.configureUi(state)
        StoreStream.getGuildTemplates().clearDynamicLinkGuildTemplateCode()
        val flipper = binding.guildCreateFlipper
        val initialized = state as? WidgetGuildCreateViewModel.ViewState.Initialized
            ?: return flipper.setDisplayedChild(LOADING)
        when (val templateState = initialized.guildTemplate) {
            StoreGuildTemplates.GuildTemplateState.Loading -> flipper.setDisplayedChild(LOADING)
            StoreGuildTemplates.GuildTemplateState.None,
            StoreGuildTemplates.GuildTemplateState.Invalid,
            StoreGuildTemplates.GuildTemplateState.LoadFailed
            -> flipper.setDisplayedChild(INVALID)
            is StoreGuildTemplates.GuildTemplateState.Resolved -> {
                val guild = templateState.guildTemplate.serializedSourceGuild
                    ?: return flipper.setDisplayedChild(INVALID)
                flipper.setDisplayedChild(READY)
                binding.guildTemplateName.apply {
                    text = templateState.guildTemplate.name
                    setDrawableColor(themedColor(R.attr.colorHeaderSecondary))
                }
                binding.guildTemplatePreviewChannels.updateView(
                    guild.channels.orEmpty().sortedBy { templateChannelOrder(it.id, it.parentId) },
                )
                val roles = guild.roles.orEmpty().filterNot { it.name == "@everyone" }.sortedByDescending { it.id }
                binding.guildTemplatePreviewRolesLayout.visibility = if (roles.isEmpty()) View.GONE else View.VISIBLE
                binding.guildTemplatePreviewRoles.updateView(roles, themedColor(R.attr.primary_300), guild.id)
            }
        }
    }

    private fun themedColor(attribute: Int): Int = TypedValue().also {
        requireContext().theme.resolveAttribute(attribute, it, true)
    }.data

    companion object {
        private const val LOADING = 0
        private const val INVALID = 1
        private const val READY = 2
        private const val INTENT_GUILD_TEMPLATE_CODE = "guild_template_code"

        internal fun templateChannelOrder(id: Long, parentId: Long) =
            if (parentId == 0L) id * 10_000L else parentId * 10_000L + id

        fun show(context: Context, guildTemplateCode: String?, location: String) {
            val intent = Intent().putExtra(EXTRA_OPTIONS, Options(analyticsLocation = location))
            guildTemplateCode?.let { intent.putExtra(INTENT_GUILD_TEMPLATE_CODE, it) }
            AppScreenRouter.launch(
                context,
                WidgetGuildClone::class.java,
                intent,
            )
        }
    }
}

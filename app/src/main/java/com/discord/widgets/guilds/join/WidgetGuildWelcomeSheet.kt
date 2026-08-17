package com.discord.widgets.guilds.join

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppBottomSheet
import com.discord.app.AppViewFlipper
import com.discord.app.appViewModels
import com.discord.i18n.i18nFormat
import com.discord.utilities.icon.IconUtils
import com.discord.widgets.guildscheduledevent.WidgetGuildScheduledEventDetailsBottomSheet
import com.discord.views.VitoImageView
import androidx.recyclerview.widget.RecyclerView
import rx.subscriptions.CompositeSubscription
import com.discord.i18n.i18nRender

class WidgetGuildWelcomeSheet : AppBottomSheet() {
    private val guildId by lazy { argumentsOrDefault.getLong(EXTRA_GUILD_ID) }
    private val viewModel: WidgetGuildWelcomeSheetViewModel by appViewModels {
        WidgetGuildWelcomeSheetViewModel(guildId)
    }
    private val channelsAdapter = WidgetGuildWelcomeSheetChannelAdapter()

    private lateinit var flipper: AppViewFlipper
    private lateinit var icon: VitoImageView
    private lateinit var iconName: TextView
    private lateinit var name: TextView
    private lateinit var description: TextView

    override fun getContentViewResId() = R.layout.widget_guild_welcome_sheet

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        flipper = view.findViewById(R.id.guild_welcome_sheet_flipper)
        icon = view.findViewById(R.id.guild_welcome_sheet_icon)
        iconName = view.findViewById(R.id.guild_welcome_sheet_icon_name)
        name = view.findViewById(R.id.guild_welcome_sheet_name)
        description = view.findViewById(R.id.guild_welcome_sheet_description)
        view.findViewById<RecyclerView>(R.id.guild_welcome_sheet_channels).adapter = channelsAdapter
    }

    override fun bindSubscriptions(compositeSubscription: CompositeSubscription) {
        compositeSubscription.add(viewModel.observeViewState().subscribe(::configureUI))
    }

    private fun configureUI(state: WidgetGuildWelcomeSheetViewModel.ViewState) {
        when (state) {
            WidgetGuildWelcomeSheetViewModel.ViewState.Invalid -> dismiss()
            WidgetGuildWelcomeSheetViewModel.ViewState.Loading -> flipper.displayedChild = LOADING_VIEW_INDEX
            is WidgetGuildWelcomeSheetViewModel.ViewState.Loaded -> {
                flipper.displayedChild = LOADED_VIEW_INDEX
                val channels = state.welcomeChannelsData ?: return
                name.text = requireContext().i18nRender(
                    R.string.welcome_screen_title,
                    "guildName" to state.guildName,
                )
                description.text = state.guildDescription
                description.visibility = if (state.guildDescription == null) View.GONE else View.VISIBLE

                val iconUrl = IconUtils.getForGuild(state.guildId, state.guildIcon, allowAnimation = true)
                IconUtils.setIcon(
                    icon,
                    iconUrl ?: DEFAULT_GUILD_ICON,
                    R.dimen.avatar_size_xxlarge,
                )
                iconName.text = state.guildShortName
                iconName.visibility = if (iconUrl == null) View.VISIBLE else View.GONE

                channelsAdapter.setData(channels.mapIndexed { index, channel ->
                    ChannelItem.ChannelData(
                        channel,
                        dismissSheet = ::dismiss,
                        guildId = state.guildId,
                        goToChannel = { channelId, itemIndex ->
                            viewModel.onClickChannel(state.guildId, channelId, itemIndex, channels)
                        },
                        index = index,
                    )
                })
            }
        }
    }

    override fun dismiss() {
        super.dismiss()
        argumentsOrDefault.getLong(EXTRA_EVENT_ID).takeIf { it != 0L }
            ?.let { WidgetGuildScheduledEventDetailsBottomSheet.showForGuild(parentFragmentManager, it) }
    }

    companion object {
        private const val EXTRA_GUILD_ID = "com.discord.intent.extra.EXTRA_GUILD_ID"
        private const val EXTRA_EVENT_ID = "com.discord.intent.extra.EXTRA_GUILD_SCHEDULED_EVENT_ID"
        private const val LOADING_VIEW_INDEX = 0
        private const val LOADED_VIEW_INDEX = 1
        private const val DEFAULT_GUILD_ICON = "asset://asset/images/default_icon_selected.jpg"

        @JvmStatic
        @JvmOverloads
        fun show(fragmentManager: FragmentManager, guildId: Long, guildScheduledEventId: Long? = null) {
            WidgetGuildWelcomeSheet().apply {
                arguments = bundleOf(EXTRA_GUILD_ID to guildId).apply {
                    guildScheduledEventId?.let { putLong(EXTRA_EVENT_ID, it) }
                }
            }.show(fragmentManager, WidgetGuildWelcomeSheet::class.java.name)
        }
    }
}

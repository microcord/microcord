package com.discord.widgets.settings

import android.content.Context
import android.content.Intent
import android.view.View
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.databinding.WidgetSettingsChannelSidebarCustomizationBinding
import com.discord.utilities.feature.ChannelSidebarCustomization
import com.discord.utilities.feature.ChannelSidebarCustomization.Button
import com.discord.utilities.feature.ChannelSidebarCustomization.Surface
import com.discord.views.channelsidebar.GuildChannelSideBarActionsView
import com.discord.views.channelsidebar.PrivateChannelSideBarActionsView
import com.google.android.material.tabs.TabLayout

/**
 * Visibility toggles for the action row at the top of the channel panel, split into a DM tab and a
 * channel tab so the two surfaces are customized independently. The preview inflates the real
 * sidebar views so each toggle is visible immediately.
 */
class WidgetSettingsChannelSidebarCustomization :
    AppFragment(R.layout.widget_settings_channel_sidebar_customization) {

    private var surface = Surface.DM

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle(R.string.channel_sidebar_customization_title)
        setActionBarDisplayHomeAsUpEnabled()
        val binding = WidgetSettingsChannelSidebarCustomizationBinding.bind(view)
        val toggles = mapOf(
            Button.SEARCH to binding.channelSidebarCustomizationSearch,
            Button.THREADS to binding.channelSidebarCustomizationThreads,
            Button.MEDIA to binding.channelSidebarCustomizationMedia,
            Button.PINS to binding.channelSidebarCustomizationPins,
            Button.NOTIFICATIONS to binding.channelSidebarCustomizationNotifications,
            Button.SETTINGS to binding.channelSidebarCustomizationSettings,
            Button.CALL to binding.channelSidebarCustomizationCall,
            Button.VIDEO to binding.channelSidebarCustomizationVideo,
        )
        val bindToggles = {
            val buttons = ChannelSidebarCustomization.buttons(surface)
            toggles.forEach { (button, toggle) ->
                if (button !in buttons) {
                    toggle.visibility = View.GONE
                    return@forEach
                }
                toggle.visibility = View.VISIBLE
                toggle.setText(requireContext().getString(button.label))
                toggle.setChecked(ChannelSidebarCustomization.visible(surface, button), false)
                toggle.setOnCheckedListener { visible ->
                    ChannelSidebarCustomization.setVisible(surface, button, visible)
                    bindPreview(binding)
                }
                toggle.setContainerClickListener {
                    toggle.setChecked(!toggle.isChecked, true)
                }
            }
            bindPreview(binding)
        }
        binding.channelSidebarCustomizationTabs.apply {
            addTab(newTab().setText(R.string.direct_messages), true)
            addTab(newTab().setText(R.string.channels))
            addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) {
                    surface = if (tab.position == 0) Surface.DM else Surface.CHANNEL
                    bindToggles()
                }

                override fun onTabUnselected(tab: TabLayout.Tab) = Unit
                override fun onTabReselected(tab: TabLayout.Tab) = Unit
            })
        }
        bindToggles()
    }

    private fun bindPreview(binding: WidgetSettingsChannelSidebarCustomizationBinding) {
        binding.channelSidebarCustomizationPreview.removeAllViews()
        if (surface == Surface.DM) {
            binding.channelSidebarCustomizationPreview.addView(PrivateChannelSideBarActionsView(requireContext()).apply { configure() })
        } else {
            binding.channelSidebarCustomizationPreview.addView(
                GuildChannelSideBarActionsView(requireContext()).apply { configure() },
            )
        }
    }

    companion object {
        fun launch(context: Context) = AppScreenRouter.launch(
            context,
            WidgetSettingsChannelSidebarCustomization::class.java,
            Intent(),
        )
    }
}

package com.discord.widgets.channels.permissions

import com.discord.app.AppViewModel

class WidgetChannelSettingsPermissionsOverviewViewModel(isStageChannel: Boolean) :
    AppViewModel<WidgetChannelSettingsPermissionsOverviewViewModel.ViewState>(generateInitialViewState(isStageChannel)) {
    enum class Tab { ADVANCED, MODERATOR }
    data class ViewState(val selectedTab: Tab, val availableTabs: List<Tab>)

    fun selectTab(tab: Tab) {
        val state = requireViewState()
        if (tab in state.availableTabs) updateViewState(state.copy(selectedTab = tab))
    }
}

private fun generateInitialViewState(isStageChannel: Boolean) =
    if (isStageChannel) {
        WidgetChannelSettingsPermissionsOverviewViewModel.ViewState(
            WidgetChannelSettingsPermissionsOverviewViewModel.Tab.MODERATOR,
            listOf(
                WidgetChannelSettingsPermissionsOverviewViewModel.Tab.MODERATOR,
                WidgetChannelSettingsPermissionsOverviewViewModel.Tab.ADVANCED,
            ),
        )
    } else {
        WidgetChannelSettingsPermissionsOverviewViewModel.ViewState(
            WidgetChannelSettingsPermissionsOverviewViewModel.Tab.ADVANCED,
            listOf(WidgetChannelSettingsPermissionsOverviewViewModel.Tab.ADVANCED),
        )
    }

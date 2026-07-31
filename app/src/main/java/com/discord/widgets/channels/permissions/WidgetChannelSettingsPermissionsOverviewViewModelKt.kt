package com.discord.widgets.channels.permissions

internal fun generateInitialViewState(isStageChannel: Boolean) =
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

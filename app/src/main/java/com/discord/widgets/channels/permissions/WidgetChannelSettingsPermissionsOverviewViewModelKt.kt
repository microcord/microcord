package com.discord.widgets.channels.permissions

/**
 * Stage channels open on Moderators; other channels only have Advanced, or with the modern screen
 * Basic and Advanced, opening on whichever was used last as React Native does.
 */
internal fun generateInitialViewState(isStageChannel: Boolean, modern: Boolean = false, startAdvanced: Boolean = false) =
    when {
        isStageChannel -> WidgetChannelSettingsPermissionsOverviewViewModel.ViewState(
            WidgetChannelSettingsPermissionsOverviewViewModel.Tab.MODERATOR,
            listOf(
                WidgetChannelSettingsPermissionsOverviewViewModel.Tab.MODERATOR,
                WidgetChannelSettingsPermissionsOverviewViewModel.Tab.ADVANCED,
            ),
        )
        modern -> WidgetChannelSettingsPermissionsOverviewViewModel.ViewState(
            if (startAdvanced) {
                WidgetChannelSettingsPermissionsOverviewViewModel.Tab.ADVANCED
            } else {
                WidgetChannelSettingsPermissionsOverviewViewModel.Tab.BASIC
            },
            listOf(
                WidgetChannelSettingsPermissionsOverviewViewModel.Tab.BASIC,
                WidgetChannelSettingsPermissionsOverviewViewModel.Tab.ADVANCED,
            ),
        )
        else -> WidgetChannelSettingsPermissionsOverviewViewModel.ViewState(
            WidgetChannelSettingsPermissionsOverviewViewModel.Tab.ADVANCED,
            listOf(WidgetChannelSettingsPermissionsOverviewViewModel.Tab.ADVANCED),
        )
    }

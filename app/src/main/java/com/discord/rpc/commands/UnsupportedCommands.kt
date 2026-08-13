package com.discord.rpc.commands

import com.discord.rpc.RpcCommand
import com.discord.rpc.RpcCommands

/** Answered with `INVALID_COMMAND`; registered last so they override same-named handlers. */
val unsupportedCommands: Map<String, RpcCommand> = listOf(
    RpcCommands.SET_USER_VOICE_SETTINGS,
    RpcCommands.SET_USER_VOICE_SETTINGS_2,
    RpcCommands.PUSH_TO_TALK,
    RpcCommands.SET_VOICE_SETTINGS_2,
    RpcCommands.SET_VOICE_SETTINGS,
    RpcCommands.SEND_ACTIVITY_JOIN_INVITE,
    RpcCommands.CLOSE_ACTIVITY_JOIN_REQUEST,
    RpcCommands.ACTIVITY_INVITE_USER,
    RpcCommands.ACCEPT_ACTIVITY_INVITE,
    RpcCommands.OPEN_SHARE_MOMENT_DIALOG,
    RpcCommands.INVITE_BROWSER,
    RpcCommands.DEEP_LINK,
    RpcCommands.CONNECTIONS_CALLBACK,
    RpcCommands.BILLING_POPUP_BRIDGE_CALLBACK,
    RpcCommands.BRAINTREE_POPUP_BRIDGE_CALLBACK,
    RpcCommands.GIFT_CODE_BROWSER,
    RpcCommands.GUILD_TEMPLATE_BROWSER,
    RpcCommands.BROWSER_HANDOFF,
    RpcCommands.SET_OVERLAY_LOCKED,
    RpcCommands.OPEN_OVERLAY_ACTIVITY_INVITE,
    RpcCommands.OPEN_OVERLAY_GUILD_INVITE,
    RpcCommands.OPEN_OVERLAY_VOICE_SETTINGS,
    RpcCommands.VALIDATE_APPLICATION,
    RpcCommands.GET_ENTITLEMENT_TICKET,
    RpcCommands.START_PURCHASE,
    RpcCommands.START_PREMIUM_PURCHASE,
    RpcCommands.ENCOURAGE_HW_ACCELERATION,
    RpcCommands.TOGGLE_VIDEO,
    RpcCommands.TOGGLE_SCREENSHARE,
    RpcCommands.OPEN_MESSAGE,
    RpcCommands.OPEN_INVITE,
    RpcCommands.SHARE_INTERACTION,
    RpcCommands.SET_SUPPRESS_NOTIFICATIONS,
).associateWith { unsupportedCommand } + mapOf(
    RpcCommands.SEND_GENERIC_EVENT to deprecatedCommand,
)

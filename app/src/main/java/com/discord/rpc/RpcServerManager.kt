package com.discord.rpc

import com.discord.rpc.commands.activityCommands
import com.discord.rpc.commands.authCommands
import com.discord.rpc.commands.channelCommands
import com.discord.rpc.commands.guildCommands
import com.discord.rpc.commands.inviteCommands
import com.discord.rpc.commands.localOnlyCommands
import com.discord.rpc.commands.providerCommands
import com.discord.rpc.commands.questCommands
import com.discord.rpc.commands.soundboardCommands
import com.discord.rpc.commands.storeCommands
import com.discord.rpc.commands.setActivityCommand
import com.discord.rpc.commands.subscriptionCommands
import com.discord.rpc.commands.unsupportedCommands
import com.discord.rpc.commands.userCommands
import com.discord.rpc.commands.voiceSettingsCommands
import com.discord.rpc.events.rpcEventHandlers
import com.discord.rpc.transport.PostMessageTransport

/** Registration order matters: the unsupported table goes last so it wins over same-named handlers. */
object RpcServerManager {

    fun create(config: RpcServerConfig, transport: PostMessageTransport): RpcServer {
        val server = RpcServer(config)

        val commands = channelCommands +
            guildCommands +
            userCommands +
            activityCommands +
            setActivityCommand +
            storeCommands +
            questCommands +
            soundboardCommands +
            providerCommands +
            inviteCommands +
            localOnlyCommands +
            voiceSettingsCommands +
            subscriptionCommands +
            authCommands +
            unsupportedCommands

        commands.forEach(server::setCommandHandler)
        rpcEventHandlers.forEach(server::setEventHandler)
        server.registerTransport(transport)
        return server
    }
}

package com.discord.rpc.commands

import com.discord.api.soundboard.SoundboardSound
import com.discord.models.user.CoreUser
import com.discord.restapi.RestAPIParams
import com.discord.rpc.OAuth2Scopes
import com.discord.rpc.RpcCommand
import com.discord.rpc.RpcCommands
import com.discord.rpc.RpcError
import com.discord.rpc.RpcErrors
import com.discord.rpc.RpcScopeConfig
import com.discord.rpc.RpcScopes
import com.discord.rpc.RpcString
import com.discord.rpc.rpcSchema
import com.discord.rpc.transformUser
import com.discord.stores.StoreStream
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.soundboard.SoundboardConstants.DEFAULT_SOUND_GUILD_ID
import com.discord.utilities.soundboard.SoundboardUtils
import com.discord.utilities.user.UserUtils.isPremiumTier2
import org.json.JSONArray
import org.json.JSONObject

private fun transformSound(sound: SoundboardSound): JSONObject = JSONObject()
    .put("name", sound.name ?: JSONObject.NULL)
    .put("sound_id", sound.soundId?.toString() ?: JSONObject.NULL)
    .put("guild_id", sound.guildId?.toString() ?: JSONObject.NULL)
    .put("volume", sound.volume ?: 1.0)
    .put("emoji_id", sound.emojiId?.toString() ?: JSONObject.NULL)
    .put("emoji_name", sound.emojiName ?: JSONObject.NULL)
    .put("available", sound.available ?: true)
    .put("user_id", sound.userId?.toString() ?: JSONObject.NULL)
    .apply { sound.user?.let { put("user", transformUser(CoreUser(it))) } }

val soundboardCommands: Map<String, RpcCommand> = mapOf(
    RpcCommands.GET_SOUNDBOARD_SOUNDS to rpcCommand(
        scope = RpcScopeConfig.Any(listOf(OAuth2Scopes.RPC, RpcScopes.LOCAL)),
    ) {
        StoreStream.getSoundboard().fetchSounds().map { sounds ->
            JSONArray().apply { sounds.values.flatten().forEach { put(transformSound(it)) } } as Any?
        }
    },

    RpcCommands.PLAY_SOUNDBOARD_SOUND to rpcCommand(
        scope = RpcScopeConfig.All(listOf(OAuth2Scopes.RPC, OAuth2Scopes.RPC_VOICE_WRITE)),
        validation = rpcSchema {
            put("guild_id", RpcString())
            put("sound_id", RpcString())
        },
    ) { context ->
        val channelId = StoreStream.getVoiceChannelSelected().getSelectedVoiceChannelId()
        val channel = StoreStream.getChannels().getChannel(channelId)
        if (channelId == 0L || channel == null) throw RpcError.error(RpcErrors.INVALID_CHANNEL, "Invalid Channel.")
        val soundId = (context.args.opt("sound_id") as? String)?.toLongOrNull()
            ?: throw RpcError.error(RpcErrors.INVALID_SOUND, "Invalid Sound.")
        val soundGuildId = (context.args.opt("guild_id") as? String)?.toLongOrNull()
            ?: DEFAULT_SOUND_GUILD_ID
        val permissions = StoreStream.getPermissions().getPermissionsByChannel()[channelId]
        val soundboard = StoreStream.getSoundboard()
        soundboard.fetchSounds().flatMap {
            val sound = soundboard.getSound(soundGuildId, soundId)
            val canUseSound = sound != null && SoundboardUtils.canUseSoundboardSound(
                sound,
                StoreStream.getUsers().getMe().isPremiumTier2,
                channel,
                permissions,
            )
            if (!canUseSound) throw RpcError.error(RpcErrors.INVALID_SOUND, "Invalid Sound.")
            if (!PermissionUtils.canUseSoundboard(channel, permissions)) {
                throw RpcError.error(RpcErrors.INVALID_PERMISSIONS, "Invalid Permissions.")
            }
            RestAPI.api.sendSoundboardSound(
                channelId,
                RestAPIParams.SendSoundboardSound(soundId, SoundboardUtils.sourceGuildId(soundGuildId)),
            ).map { null as Any? }
        }
    },
)

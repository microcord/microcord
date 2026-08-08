package com.discord.widgets.channels.permissions

import com.discord.R
import com.discord.api.permission.Permission
import com.discord.api.permission.PermissionBit
import com.discord.api.permission.PermissionOverwrite
import com.discord.api.role.GuildRole
import com.discord.models.guild.Guild
import com.discord.models.member.GuildMember
import com.discord.models.user.MeUser
import com.discord.models.user.User
import com.discord.utilities.permissions.PermissionUtils
import com.discord.widgets.channels.SimpleMembersAdapter

internal object PermissionEditor {
    const val TYPE_USER = 0
    const val TYPE_ROLE = 1

    val permissionByViewId: Map<Int, PermissionBit> = mapOf(
        R.id.channel_permission_general_create_instant_invite to Permission.CREATE_INSTANT_INVITE,
        R.id.channel_permission_general_manage_channel to Permission.MANAGE_CHANNELS,
        R.id.channel_permission_general_manage_threads to Permission.MANAGE_THREADS,
        R.id.channel_permission_general_manage_permissions to Permission.MANAGE_ROLES,
        R.id.channel_permission_general_manage_webhooks to Permission.MANAGE_WEBHOOKS,
        R.id.channel_permission_text_read_messages to Permission.VIEW_CHANNEL,
        R.id.channel_permission_text_send_messages to Permission.SEND_MESSAGES,
        R.id.channel_permission_text_send_messages_in_threads to Permission.SEND_MESSAGES_IN_THREADS,
        R.id.channel_permission_text_create_public_threads to Permission.CREATE_PUBLIC_THREADS,
        R.id.channel_permission_text_create_private_threads to Permission.CREATE_PRIVATE_THREADS,
        R.id.channel_permission_text_embed_links to Permission.EMBED_LINKS,
        R.id.channel_permission_text_attach_files to Permission.ATTACH_FILES,
        R.id.channel_permission_text_add_reactions to Permission.ADD_REACTIONS,
        R.id.channel_permission_text_use_external_emojis to Permission.USE_EXTERNAL_EMOJIS,
        R.id.channel_permission_text_use_external_stickers to Permission.USE_EXTERNAL_STICKERS,
        R.id.channel_permission_text_mention_everyone to Permission.MENTION_EVERYONE,
        R.id.channel_permission_text_manage_messages to Permission.MANAGE_MESSAGES,
        R.id.channel_permission_text_bypass_slowmode to Permission.BYPASS_SLOWMODE,
        R.id.channel_permission_text_read_message_history to Permission.READ_MESSAGE_HISTORY,
        R.id.channel_permission_text_send_tts_messages to Permission.SEND_TTS_MESSAGES,
        R.id.channel_permission_use_application_commands to Permission.USE_APPLICATION_COMMANDS,
        R.id.channel_permission_voice_connect to Permission.CONNECT,
        R.id.channel_permission_voice_speak to Permission.SPEAK,
        R.id.channel_permission_voice_video to Permission.STREAM,
        R.id.channel_permission_voice_use_vad to Permission.USE_VAD,
        R.id.channel_permission_voice_priority_speaker to Permission.PRIORITY_SPEAKER,
        R.id.channel_permission_voice_mute_members to Permission.MUTE_MEMBERS,
        R.id.channel_permission_voice_deafen_members to Permission.DEAFEN_MEMBERS,
        R.id.channel_permission_voice_move_members to Permission.MOVE_MEMBERS,
        R.id.channel_permission_stage_request_to_speak to Permission.REQUEST_TO_SPEAK,
        R.id.channel_permission_events_manage_events to Permission.MANAGE_EVENTS,
        R.id.channel_permission_text_pin_messages to Permission.PIN_MESSAGES,
        R.id.channel_permission_text_manage_official_messages to Permission.MANAGE_OFFICIAL_MESSAGES,
        R.id.channel_permission_text_send_voice_messages to Permission.SEND_VOICE_MESSAGES,
        R.id.channel_permission_text_send_polls to Permission.SEND_POLLS,
        R.id.channel_permission_apps_use_embedded_activities to Permission.USE_EMBEDDED_ACTIVITIES,
        R.id.channel_permission_apps_use_external_apps to Permission.USE_EXTERNAL_APPS,
        R.id.channel_permission_voice_use_soundboard to Permission.USE_SOUNDBOARD,
        R.id.channel_permission_voice_use_external_sounds to Permission.USE_EXTERNAL_SOUNDS,
        R.id.channel_permission_voice_set_channel_status to Permission.SET_VOICE_CHANNEL_STATUS,
        R.id.channel_permission_events_create_events to Permission.CREATE_EVENTS,
    )

    fun status(overwrite: PermissionOverwrite?, permission: PermissionBit): Int = when {
        overwrite?.allow?.and(permission) == permission -> 1
        overwrite?.deny?.and(permission) == permission -> -1
        else -> 0
    }

    fun bits(statuses: Map<PermissionBit, Int>): Pair<Long, Long> = statuses.entries.fold(0L to 0L) { bits, (permission, status) ->
        when (status) {
            1 -> bits.first.or(permission) to bits.second.and(permission.inv())
            -1 -> bits.first.and(permission.inv()) to bits.second.or(permission)
            else -> bits.first.and(permission.inv()) to bits.second.and(permission.inv())
        }
    }

    fun canManage(guild: Guild, me: MeUser, permissions: Long?): Boolean =
        me.id == guild.ownerId ||
            PermissionUtils.canAndIsElevated(Permission.MANAGE_ROLES, permissions, me.mfaEnabled, guild.mfaLevel) ||
            PermissionUtils.canAndIsElevated(Permission.ADMINISTRATOR, permissions, me.mfaEnabled, guild.mfaLevel)

    fun roleItems(channelOverwrites: List<PermissionOverwrite>?, roles: Collection<GuildRole>) =
        roles.filter { role -> channelOverwrites.orEmpty().none { it.id == role.id && it.type == PermissionOverwrite.Type.ROLE } }

    fun memberItems(
        query: String,
        overwrites: List<PermissionOverwrite>?,
        users: Collection<User>,
        members: Map<Long, GuildMember>,
    ): List<SimpleMembersAdapter.MemberItem> = users.asSequence()
        .filter { user -> overwrites.orEmpty().none { it.id == user.id && it.type == PermissionOverwrite.Type.MEMBER } }
        .filter { members.containsKey(it.id) }
        .filter { it.username.contains(query.trim(), ignoreCase = true) }
        .sortedWith { left, right -> GuildMember.compareUserNames(left, right, members[left.id], members[right.id]) }
        .map { SimpleMembersAdapter.MemberItem(it, members[it.id]) }
        .toList()
}

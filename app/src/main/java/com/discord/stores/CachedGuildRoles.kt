package com.discord.stores

import com.discord.api.role.GuildRole
import com.discord.api.role.GuildRoleCacheFormat
import com.esotericsoftware.kryo.DefaultSerializer
import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.Serializer
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output

/**
 * The guild roles cache as written to disk, guild id to role id to [GuildRole], each role through
 * its generated `@CachedFormat` codec. A cache written as a plain map by an older build still reads
 * normally and is rewritten in this format on next save.
 */
@DefaultSerializer(CachedGuildRoles.KryoSerializer::class)
class CachedGuildRoles(capacity: Int = 16) : HashMap<Long, Map<Long, GuildRole>>(capacity) {
    class KryoSerializer : Serializer<CachedGuildRoles>() {
        override fun write(kryo: Kryo, output: Output, map: CachedGuildRoles) {
            CacheHeader.write(output, MAGIC, GuildRoleCacheFormat.LAYOUT_HASH)
            output.writeVarInt(map.size, true)
            map.forEach { (guildId, roles) ->
                output.writeVarLong(guildId, true)
                output.writeVarInt(roles.size, true)
                roles.values.forEach { GuildRoleCacheFormat.write(kryo, output, it) }
            }
        }

        override fun read(kryo: Kryo, input: Input, type: Class<CachedGuildRoles>): CachedGuildRoles {
            CacheHeader.check(input, MAGIC, GuildRoleCacheFormat.LAYOUT_HASH)
            val guilds = input.readVarInt(true)
            return CachedGuildRoles(guilds * 2).apply {
                repeat(guilds) {
                    val guildId = input.readVarLong(true)
                    val count = input.readVarInt(true)
                    put(guildId, HashMap<Long, GuildRole>(count * 2).apply {
                        repeat(count) { GuildRoleCacheFormat.read(kryo, input).let { put(it.id, it) } }
                    })
                }
            }
        }
    }

    internal companion object {
        /** "ROL" and the header revision. */
        const val MAGIC = 0x524F4C03
    }
}

package com.discord.stores

import com.discord.models.guild.Guild
import com.discord.models.guild.GuildCacheFormat
import com.esotericsoftware.kryo.DefaultSerializer
import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.Serializer
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output

/**
 * The guilds cache as written to disk, each [Guild] (with its roles, emojis and stickers) through
 * its generated `@CachedFormat` codec. A cache written as a plain map by an older build still
 * reads normally and is rewritten in this format on next save.
 */
@DefaultSerializer(CachedGuildMap.KryoSerializer::class)
class CachedGuildMap(capacity: Int = 16) : HashMap<Long, Guild>(capacity) {
    class KryoSerializer : Serializer<CachedGuildMap>() {
        override fun write(kryo: Kryo, output: Output, map: CachedGuildMap) {
            CacheHeader.write(output, MAGIC, GuildCacheFormat.LAYOUT_HASH)
            output.writeVarInt(map.size, true)
            map.values.forEach { GuildCacheFormat.write(kryo, output, it) }
        }

        override fun read(kryo: Kryo, input: Input, type: Class<CachedGuildMap>): CachedGuildMap {
            CacheHeader.check(input, MAGIC, GuildCacheFormat.LAYOUT_HASH)
            val size = input.readVarInt(true)
            return CachedGuildMap(size * 2).apply {
                repeat(size) { GuildCacheFormat.read(kryo, input).let { put(it.id, it) } }
            }
        }
    }

    internal companion object {
        /** "GLD" and the header revision. */
        const val MAGIC = 0x474C4403
    }
}

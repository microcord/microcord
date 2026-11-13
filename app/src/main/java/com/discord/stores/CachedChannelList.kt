package com.discord.stores

import com.discord.api.channel.Channel
import com.discord.api.channel.ChannelCacheFormat
import com.esotericsoftware.kryo.DefaultSerializer
import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.Serializer
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output

/**
 * The channels cache as written to disk. A large account holds over ten thousand channels and
 * Kryo's reflective field serializer was most of the startup wait, so each [Channel] goes through
 * its generated `@CachedFormat` codec instead. Kryo records this class's name in the file, so a
 * cache written as a plain `ArrayList` by an older build still reads normally and is rewritten in
 * this format on next save; a file from a different model layout is discarded.
 */
@DefaultSerializer(CachedChannelList.KryoSerializer::class)
class CachedChannelList(capacity: Int = 0) : ArrayList<Channel>(capacity) {
    class KryoSerializer : Serializer<CachedChannelList>() {
        override fun write(kryo: Kryo, output: Output, list: CachedChannelList) {
            CacheHeader.write(output, MAGIC, ChannelCacheFormat.LAYOUT_HASH)
            output.writeVarInt(list.size, true)
            list.forEach { ChannelCacheFormat.write(kryo, output, it) }
        }

        override fun read(kryo: Kryo, input: Input, type: Class<CachedChannelList>): CachedChannelList {
            CacheHeader.check(input, MAGIC, ChannelCacheFormat.LAYOUT_HASH)
            val size = input.readVarInt(true)
            return CachedChannelList(size).apply { repeat(size) { add(ChannelCacheFormat.read(kryo, input)) } }
        }
    }

    internal companion object {
        /** "CHN" and the header revision. */
        const val MAGIC = 0x43484E03
    }
}

package com.discord.utilities.kryo

import com.discord.utilities.collections.SnowflakePartitionMap
import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.Serializer
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output

/**
 * Kryo support for [SnowflakePartitionMap].
 *
 * Persists the partition count and then the flat `(key, value)` pairs; the partitions themselves
 * are re-derived on read from the key's snowflake timestamp. That matters because the partition
 * strategy is an implementation detail — a persisted file written by one build must still load if
 * the strategy changes, and it would not if the partition lists were stored directly.
 *
 * Reference: `com.discord.utilities.kryo.SnowflakePartitionMapSerializer`.
 */
class SnowflakePartitionMapSerializer : Serializer<SnowflakePartitionMap<*>>() {

    override fun write(kryo: Kryo, output: Output, target: SnowflakePartitionMap<*>) {
        output.writeInt(target.numPartitions, true)
        output.writeInt(target.size, true)
        target.entries.forEach { (key, value) ->
            output.writeLong(key)
            kryo.writeClassAndObject(output, value)
        }
    }

    override fun read(
        kryo: Kryo,
        input: Input,
        type: Class<SnowflakePartitionMap<*>>,
    ): SnowflakePartitionMap<*> {
        val map = SnowflakePartitionMap<Any?>(input.readInt(true))
        repeat(input.readInt(true)) {
            map[input.readLong()] = kryo.readClassAndObject(input)
        }
        return map
    }
}

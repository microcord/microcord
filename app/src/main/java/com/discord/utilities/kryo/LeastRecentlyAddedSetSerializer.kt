package com.discord.utilities.kryo

import com.discord.utilities.collections.LeastRecentlyAddedSet
import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.Serializer
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output

/**
 * Kryo support for [LeastRecentlyAddedSet].
 *
 * The default field serializer would persist the backing `LinkedHashSet` and the bound as opaque
 * fields, which breaks the moment the class's shape changes. Writing `maxSize`, then the count,
 * then the elements keeps the format independent of the implementation and — because elements are
 * re-`add`ed on read — restores the eviction order rather than trusting the stored one.
 *
 * `writeInt(…, true)` is Kryo's variable-length encoding; both sides must agree, hence the
 * matching `readInt(true)`.
 *
 * Reference: `com.discord.utilities.kryo.LeastRecentlyAddedSetSerializer`.
 */
class LeastRecentlyAddedSetSerializer : Serializer<LeastRecentlyAddedSet<*>>() {

    override fun write(kryo: Kryo, output: Output, target: LeastRecentlyAddedSet<*>) {
        output.writeInt(target.maxSize, true)
        output.writeInt(target.size, true)
        target.forEach { element -> kryo.writeClassAndObject(output, element) }
    }

    override fun read(
        kryo: Kryo,
        input: Input,
        type: Class<LeastRecentlyAddedSet<*>>,
    ): LeastRecentlyAddedSet<*> {
        val set = LeastRecentlyAddedSet<Any>(input.readInt(true))
        repeat(input.readInt(true)) {
            set.add(kryo.readClassAndObject(input))
        }
        return set
    }
}

package com.discord.stores

import com.esotericsoftware.kryo.KryoException
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output

/**
 * Leads every `@CachedFormat` cache file: which cache it is and the generated layout hash. A
 * mismatch throws, and the persister then discards the file; the gateway refills it at READY.
 */
internal object CacheHeader {
    fun write(output: Output, magic: Int, layoutHash: Int) {
        output.writeInt(magic)
        output.writeInt(layoutHash)
    }

    fun check(input: Input, magic: Int, layoutHash: Int) {
        if (input.readInt() != magic || input.readInt() != layoutHash) throw KryoException("Cache written for another model layout")
    }
}

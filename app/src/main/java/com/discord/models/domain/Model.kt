package com.discord.models.domain

import com.google.gson.Gson
import com.google.gson.TypeAdapter
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.io.Closeable
import java.io.IOException
import java.io.Reader

/**
 * Contract for the hand-rolled streaming parsers used by the `models/domain` layer.
 *
 * Unlike the Gson reflective/tree adapters used for the `api` DTOs, a [Model] is filled in field by
 * field straight off the token stream, so a large payload (READY, GUILD_MEMBER_LIST_UPDATE, search
 * results) never has to be materialised as an intermediate `JsonObject` tree.
 */
interface Model {

    /** Reads and assigns exactly one field of this model, then returns. */
    @Throws(IOException::class)
    fun assignField(reader: JsonReader)

    /** A stateless parser that builds an immutable model out of the token stream. */
    fun interface Parser<T> {
        fun parse(reader: JsonReader): T
    }

    /**
     * Thin, null-tolerant wrapper around [com.google.gson.stream.JsonReader].
     *
     * Every `nextXxx(default)` accessor consumes a `null` token and yields the supplied default,
     * every `nextXxxOrNull()` accessor yields `null` instead; that is what lets the generated
     * `assignField` bodies stay free of null checks.
     */
    class JsonReader(@JvmField val `in`: com.google.gson.stream.JsonReader) : Closeable {

        constructor(reader: Reader) : this(com.google.gson.stream.JsonReader(reader))

        /** Produces one element of an array; returning `null` drops the element. */
        fun interface ItemFactory<T> {
            @Throws(IOException::class)
            fun get(): T?
        }

        /** Derives the map key for an element parsed by an [ItemFactory]. */
        fun interface KeySelector<K, V> {
            fun get(value: V): K
        }

        @Throws(IOException::class)
        override fun close() = `in`.close()

        @Throws(IOException::class)
        fun hasNext(): Boolean = `in`.hasNext()

        @Throws(IOException::class)
        fun peek(): JsonToken = `in`.peek()

        @Throws(IOException::class)
        fun skipValue() = `in`.skipValue()

        @Throws(IOException::class)
        fun nextName(): String = `in`.nextName()

        @Throws(IOException::class)
        fun nextNull() = `in`.nextNull()

        @Throws(IOException::class)
        fun nextBoolean(defaultValue: Boolean): Boolean =
            if (consumedNull()) defaultValue else `in`.nextBoolean()

        @Throws(IOException::class)
        fun nextBooleanOrNull(): Boolean? = if (consumedNull()) null else `in`.nextBoolean()

        @Throws(IOException::class)
        fun nextDouble(defaultValue: Double): Double =
            if (consumedNull()) defaultValue else `in`.nextDouble()

        @Throws(IOException::class)
        fun nextInt(defaultValue: Int): Int = if (consumedNull()) defaultValue else `in`.nextInt()

        @Throws(IOException::class)
        fun nextIntOrNull(): Int? = if (consumedNull()) null else `in`.nextInt()

        @Throws(IOException::class)
        fun nextLong(defaultValue: Long): Long = if (consumedNull()) defaultValue else `in`.nextLong()

        @Throws(IOException::class)
        fun nextLongOrNull(): Long? = if (consumedNull()) null else `in`.nextLong()

        @Throws(IOException::class)
        fun nextString(defaultValue: String?): String? =
            if (consumedNull()) defaultValue else `in`.nextString()

        @Throws(IOException::class)
        fun nextStringOrNull(): String? = if (consumedNull()) null else `in`.nextString()

        /** Reads an array, dropping elements the [itemFactory] rejected by returning `null`. */
        @Throws(IOException::class)
        fun <T> nextList(itemFactory: ItemFactory<T>): List<T> {
            val items = ArrayList<T>()
            `in`.beginArray()
            while (`in`.hasNext()) {
                itemFactory.get()?.let(items::add)
            }
            `in`.endArray()
            return items
        }

        /** Reads an array and indexes it by [keySelector]; later duplicates win. */
        @Throws(IOException::class)
        fun <K, V> nextListAsMap(
            itemFactory: ItemFactory<V>,
            keySelector: KeySelector<K, V>,
        ): HashMap<K, V> = nextListAsMap(itemFactory, keySelector) { HashMap() }

        @Throws(IOException::class)
        fun <K, V, M : MutableMap<K, V>> nextListAsMap(
            itemFactory: ItemFactory<V>,
            keySelector: KeySelector<K, V>,
            mapFactory: () -> M,
        ): M {
            val map = mapFactory()
            `in`.beginArray()
            while (`in`.hasNext()) {
                itemFactory.get()?.let { map[keySelector.get(it)] = it }
            }
            `in`.endArray()
            return map
        }

        /** Reads a positional array, running [readers]`[i]` for element `i` and skipping the rest. */
        @Throws(IOException::class)
        fun nextListIndexed(vararg readers: Runnable) {
            `in`.beginArray()
            var index = 0
            while (`in`.hasNext()) {
                if (index < readers.size) readers[index].run() else `in`.skipValue()
                index++
            }
            `in`.endArray()
        }

        /** Reads an object, invoking [onField] once per key with the reader positioned on its value. */
        @Throws(IOException::class)
        fun nextObject(onField: (String) -> Unit) {
            `in`.beginObject()
            while (hasNext()) {
                onField(`in`.nextName())
            }
            `in`.endObject()
        }

        /** Fills [model] from an object value, or consumes a `null` and returns `null`. */
        @Throws(IOException::class)
        fun <T : Model> parse(model: T): T? {
            if (consumedNull()) return null
            `in`.beginObject()
            while (`in`.hasNext()) {
                model.assignField(this)
            }
            `in`.endObject()
            return model
        }

        /**
         * Reads a value of unannounced shape: object or array via [itemFactory], string,
         * number-as-long or boolean. A `null` is consumed and yields `null`.
         *
         * An object has to go through [itemFactory] rather than being skipped: a value left
         * unconsumed leaves the reader on a `BEGIN_OBJECT` where the enclosing [parse] loop expects
         * a name, which fails the whole payload rather than the one field.
         */
        @Throws(IOException::class)
        fun <T> parseUnknown(itemFactory: ItemFactory<T>): Any? = when (`in`.peek()) {
            JsonToken.BEGIN_OBJECT -> itemFactory.get()
            JsonToken.BEGIN_ARRAY -> nextList(itemFactory)
            JsonToken.STRING -> nextStringOrNull()
            JsonToken.NUMBER -> nextLongOrNull()
            JsonToken.BOOLEAN -> nextBooleanOrNull()
            else -> {
                if (`in`.peek() == JsonToken.NULL) `in`.nextNull()
                null
            }
        }

        private fun consumedNull(): Boolean {
            if (`in`.peek() != JsonToken.NULL) return false
            `in`.nextNull()
            return true
        }
    }

    /** Routes every [Model] implementation with a no-argument constructor through [JsonReader.parse]. */
    class TypeAdapterFactory : com.google.gson.TypeAdapterFactory {
        override fun <T : Any?> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T>? {
            if (!Model::class.java.isAssignableFrom(type.rawType)) return null
            return object : TypeAdapter<T>() {
                @Suppress("UNCHECKED_CAST")
                override fun read(reader: com.google.gson.stream.JsonReader): T? = try {
                    JsonReader(reader).parse(type.rawType.newInstance() as Model) as T?
                } catch (e: IOException) {
                    throw e
                } catch (e: Exception) {
                    throw IOException("Unable to parse model.", e)
                }

                override fun write(writer: JsonWriter, value: T) {
                    throw IOException("Writer incorrectly invoked for object.$value")
                }
            }
        }
    }
}

package com.discord.utilities.collections

/**
 * Positional equality between two collections using a caller supplied element comparator.
 *
 * Both collections must have the same size and every element at the same iteration index must
 * satisfy [comparator].
 */
fun <T> Collection<T>.equals(collection: Collection<T>, comparator: (T, T) -> Boolean): Boolean {
    if (size != collection.size) return false
    forEachIndexed { index, element ->
        if (!comparator(element, collection.elementAt(index))) return false
    }
    return true
}

/** Drops every entry whose value is null, producing a non-null valued map. */
fun <K, V> Map<K, V?>.filterNonNullValues(): Map<K, V> {
    val result = HashMap<K, V>(size)
    for ((key, value) in this) {
        if (value != null) result[key] = value
    }
    return result
}

/**
 * Copies this map into a fresh [HashMap] so callers can hand the copy to another thread without
 * exposing the live backing map.
 */
fun <K, V> Map<K, V>.snapshot(
    initialCapacity: Int = size.coerceAtLeast(1),
    loadFactor: Float = 0.75f,
): HashMap<K, V> {
    val snapshot = HashMap<K, V>(initialCapacity, loadFactor)
    snapshot.putAll(this)
    return snapshot
}

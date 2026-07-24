package com.discord.utilities.cache

import android.content.SharedPreferences

fun SharedPreferences.getString(key: String): String? = getString(key, null)

fun SharedPreferences.getStringNonNull(key: String, defValue: String): String =
    getString(key, defValue) ?: defValue

fun SharedPreferences.getStringSetNonNull(
    key: String,
    defValues: Set<String> = emptySet(),
): Set<String> = getStringSet(key, defValues) ?: emptySet()

fun <K, V> SharedPreferences.getStringEntrySetAsMap(
    cacheKey: String,
    delimiter: String = ",",
    transformer: (String, String) -> Pair<K, V>,
): Map<K, V> = getStringSetNonNull(cacheKey).associate { entry ->
    val parts = entry.split(delimiter, limit = 2)
    transformer(parts[0], parts[1])
}

fun <K, V> SharedPreferences.Editor.putStringEntrySetAsMap(
    cacheKey: String,
    value: Map<K, V>,
    delimiter: String = ",",
    transformer: (V) -> String = { it.toString() },
) {
    putStringSet(cacheKey, value.mapTo(mutableSetOf()) { "${it.key}$delimiter${transformer(it.value)}" })
}

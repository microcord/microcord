@file:JvmName("InputAutocompletablesKt")

package com.discord.widgets.chat.input.autocomplete

fun Map<LeadingIdentifier, List<Autocompletable>>.flatten(): List<Autocompletable> = values.flatten()

internal fun MutableMap<LeadingIdentifier, java.util.TreeSet<Autocompletable>>.merge(
    merge: Map<LeadingIdentifier, java.util.TreeSet<Autocompletable>>,
) {
    merge.forEach { (identifier, values) ->
        getOrPut(identifier) { java.util.TreeSet(AutocompletableComparator()) }.addAll(values)
    }
}

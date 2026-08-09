package com.discord.utilities.search

/**
 * The `fuzzysearch` npm package the reference filters ban and member lists with.
 *
 * A needle matches when its characters appear in the haystack in order, not necessarily adjacent —
 * so "jsmth" finds "john smith". Callers lowercase both sides first, as the reference does.
 */
fun fuzzyMatches(needle: String, haystack: String): Boolean {
    if (needle.isEmpty()) return true
    if (needle.length > haystack.length) return false
    var next = 0
    for (character in haystack) {
        if (character == needle[next] && ++next == needle.length) return true
    }
    return false
}

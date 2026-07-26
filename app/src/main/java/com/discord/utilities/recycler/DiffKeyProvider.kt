package com.discord.utilities.recycler

/**
 * Supplies the stable identity of a list row.
 *
 * [key] answers "is this the same row?" for [DiffCreator]; row equality answers "did its contents
 * change?".
 */
interface DiffKeyProvider {
    val key: String
}

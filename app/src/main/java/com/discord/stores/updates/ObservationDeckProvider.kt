package com.discord.stores.updates

object ObservationDeckProvider {
    private val instance by lazy(::ObservationDeck)

    @JvmStatic
    fun get(): ObservationDeck = instance
}

package com.discord.widgets.hubs

import com.discord.app.AppViewModel
import com.discord.models.hubs.DirectoryEntryCategory
import com.discord.restapi.RestAPIInterface
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreChannels
import com.discord.stores.StoreGuilds
import com.discord.stores.StoreStream
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.stores.utilities.executeRequest
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.ui
import rx.Observable

/**
 * Describes and categorises one server inside a hub directory, for both "add" and "edit".
 *
 * The store half only supplies the directory channel (which decides the category set) and the hub's
 * name (which the title interpolates); the description and category the user is editing live in the
 * view state, so a store update must not overwrite them — [handleStoreUpdate] copies exactly the two
 * store-owned fields across.
 */
class WidgetHubDescriptionViewModel(
    val channelId: Long,
    val guildId: Long,
    val isEditing: Boolean,
    primaryCategoryId: Int?,
    private val restAPI: RestAPIInterface = RestAPI.api,
    storeObservable: Observable<HubDescriptionState> = observeStores(channelId, guildId),
) : AppViewModel<HubDescriptionState>(HubDescriptionState(primaryCategoryId = primaryCategoryId)) {

    init {
        storeObservable
            .computationLatest()
            .ui(this)
            .appSubscribe(WidgetHubDescriptionViewModel::class.java) { handleStoreUpdate(it) }
    }

    private fun handleStoreUpdate(state: HubDescriptionState) {
        val current = viewState ?: return
        updateViewState(current.copy(channel = state.channel, guildName = state.guildName))
    }

    /** The category the user picked, resolved against this directory's category set. */
    fun getCategory(): DirectoryEntryCategory? = viewState?.selectedCategory

    fun setCategory(key: Int) {
        viewState?.let { updateViewState(it.copy(primaryCategoryId = key)) }
    }

    /**
     * POSTs a new listing, or PATCHes the existing one when [isEditing].
     *
     * Does nothing when no category has been picked; the screen's [com.discord.utilities.view.validators.ValidationManager]
     * is what tells the user why, so this is a guard rather than the error path.
     */
    fun addServer(channelId: Long, guildId: Long, description: String) {
        val state = viewState ?: return
        val category = state.selectedCategory ?: return
        val body = RestAPIParams.AddServerBody(description = description, primaryCategoryId = category.key)
        val request = if (isEditing) {
            restAPI.modifyServerInHub(channelId, guildId, body)
        } else {
            restAPI.addServerToHub(channelId, guildId, body).unwrapBody()
        }
        request
            .ui(this)
            .executeRequest { async ->
                viewState?.let { updateViewState(it.copy(addServerAsync = async)) }
            }
    }

    companion object {
        /**
         * Directory channel plus hub name, recomputed whenever either store changes.
         *
         * Mirrors `WidgetHubDescriptionViewModel.Companion.observeStores`.
         */
        fun observeStores(
            channelId: Long,
            guildId: Long,
            observationDeck: ObservationDeck = ObservationDeckProvider.get(),
            channelStore: StoreChannels = StoreStream.getChannels(),
            guildStore: StoreGuilds = StoreStream.getGuilds(),
        ): Observable<HubDescriptionState> =
            observationDeck.connectRx(channelStore, guildStore).map {
                HubDescriptionState(
                    channel = channelStore.getChannel(channelId),
                    guildName = guildStore.getGuild(guildId)?.name.orEmpty(),
                )
            }
    }
}

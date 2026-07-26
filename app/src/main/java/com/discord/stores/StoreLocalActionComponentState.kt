package com.discord.stores

import com.discord.api.botuikit.SelectItem
import com.discord.stores.StoreApplicationInteractions.ComponentLocation
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import rx.Observable

/**
 * What the user has picked in a message's select menus *before* the application answers.
 *
 * A select-menu component is optimistic: the chosen items are shown as selected the moment the
 * sheet is dismissed, long before `INTERACTION_SUCCESS` arrives and the bot edits the message.
 * This store owns that optimistic layer, keyed message → component index → chosen items.
 *
 * [StoreApplicationInteractions] is the only thing that clears it, and it does so from three
 * places: the interaction failed, the message was edited (so the component set may have changed),
 * or a second component on the same message was pressed while one was still in flight.
 */
class StoreLocalActionComponentState(
    val dispatcher: Dispatcher,
    private val observationDeck: ObservationDeck = ObservationDeckProvider.get(),
) : StoreV2() {

    /**
     * Nonce → where the in-flight component lives.
     *
     * Public and mutable in the reference too; [StoreApplicationInteractions] keeps the same map
     * under its own name and this copy exists so a component view can resolve a nonce without
     * reaching into the interaction store.
     */
    val componentInteractions: MutableMap<String, ComponentLocation> = LinkedHashMap()

    private val selectComponentSelections = LinkedHashMap<Long, Map<Int, List<SelectItem>>>()
    private var selectComponentSelectionsSnapshot: Map<Long, Map<Int, List<SelectItem>>> = emptyMap()

    fun getSelectComponentSelectionsData(): Map<Long, Map<Int, List<SelectItem>>> =
        selectComponentSelectionsSnapshot

    fun observeSelectComponentSelections(): Observable<Map<Long, Map<Int, List<SelectItem>>>> =
        observationDeck.connectRx(this) { getSelectComponentSelectionsData() }.distinctUntilChanged()

    fun setSelectComponentSelection(
        messageId: Long,
        componentIndex: Int,
        selectedItems: List<SelectItem>,
    ) = dispatcher.schedule {
        val forMessage = selectComponentSelections[messageId]?.toMutableMap() ?: LinkedHashMap()
        forMessage[componentIndex] = selectedItems
        selectComponentSelections[messageId] = forMessage
        markChanged()
    }

    /**
     * Clears one component's optimistic selection, or the whole message's when [componentIndex] is
     * `null`.
     *
     * Note the reference's shape, kept verbatim: a non-null index that is *not* present in the map
     * clears nothing at all and does not mark the store changed. Only an exact hit, or the
     * clear-everything case, mutates.
     */
    @StoreThread
    fun clearState(messageId: Long, componentIndex: Int?) {
        val forMessage = selectComponentSelections[messageId] ?: return
        when {
            componentIndex != null && forMessage.containsKey(componentIndex) -> {
                selectComponentSelections[messageId] =
                    forMessage.toMutableMap().apply { remove(componentIndex) }
                markChanged()
            }

            componentIndex == null -> {
                selectComponentSelections.remove(messageId)
                markChanged()
            }
        }
    }

    @StoreThread
    internal fun handlePreLogout() {
        componentInteractions.clear()
        selectComponentSelections.clear()
        markChanged()
    }

    @StoreThread
    override fun snapshotData() {
        selectComponentSelectionsSnapshot = HashMap(selectComponentSelections)
    }
}

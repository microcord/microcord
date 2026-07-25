package com.discord.stores

import com.discord.models.domain.ModelMessageDelete
import java.util.Collections
import rx.Observable
import rx.subjects.BehaviorSubject

class StoreMessageState(private val dispatcher: Dispatcher) {
    data class State(
        val visibleSpoilerNodeIndices: Set<Int> = emptySet(),
        val visibleSpoilerEmbedMap: Map<Int, Set<String>> = emptyMap(),
    )

    private val states = HashMap<Long, State>()
    private val subject = BehaviorSubject.create<Map<Long, State>>(emptyMap())

    fun getMessageState(): Observable<Map<Long, State>> = subject.distinctUntilChanged()
    @StoreThread fun handleChannelSelected() { states.clear(); publish() }
    @StoreThread fun handleMessageDelete(messageIds: Collection<Long>) { if (states.keys.removeAll(messageIds.toSet())) publish() }
    @StoreThread fun handleMessageDelete(messageDelete: ModelMessageDelete) = handleMessageDelete(messageDelete.messageIds.orEmpty())
    @StoreThread fun handleMessageUpdate(messageId: Long) = handleMessageDelete(listOf(messageId))
    @StoreThread fun handleMessageUpdate(message: com.discord.api.message.Message) = handleMessageUpdate(message.id)

    fun revealSpoiler(messageId: Long, index: Int) {
        if (index >= 0) dispatcher.schedule { update(messageId) { it.copy(visibleSpoilerNodeIndices = it.visibleSpoilerNodeIndices + index) } }
    }

    fun revealSpoilerEmbed(messageId: Long, index: Int) {
        if (index >= 0) dispatcher.schedule { update(messageId) { state ->
            state.copy(visibleSpoilerEmbedMap = state.visibleSpoilerEmbedMap + (index to emptySet()))
        } }
    }

    fun revealSpoilerEmbedData(messageId: Long, index: Int, key: String) {
        if (index >= 0) dispatcher.schedule { update(messageId) { state ->
            state.copy(visibleSpoilerEmbedMap = state.visibleSpoilerEmbedMap +
                (index to (state.visibleSpoilerEmbedMap[index].orEmpty() + key)))
        } }
    }

    private fun update(messageId: Long, transform: (State) -> State) {
        states[messageId] = transform(states[messageId] ?: State())
        publish()
    }

    private fun publish() = subject.onNext(Collections.unmodifiableMap(HashMap(states)))
}

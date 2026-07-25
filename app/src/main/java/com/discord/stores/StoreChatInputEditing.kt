package com.discord.stores

import com.discord.models.message.Message
import rx.Observable
import rx.subjects.BehaviorSubject

class StoreChatInputEditing {
    private val editing = LinkedHashMap<Long, Message>()
    private val subject = BehaviorSubject.create<Map<Long, Message>>(emptyMap())

    @Synchronized fun get(channelId: Long): Message? = editing[channelId]
    fun observe(channelId: Long): Observable<Message?> = subject.map { it[channelId] }.distinctUntilChanged()

    @Synchronized fun start(message: Message) {
        editing[message.channelId] = message
        publish()
    }

    @Synchronized fun stop(channelId: Long) {
        if (editing.remove(channelId) != null) publish()
    }

    @Synchronized fun clear() {
        if (editing.isNotEmpty()) { editing.clear(); publish() }
    }

    private fun publish() = subject.onNext(LinkedHashMap(editing))
}

package com.discord.stores

import com.discord.models.message.Message
import rx.Observable
import rx.subjects.BehaviorSubject

/**
 * Which message the composer is editing, per channel.
 *
 * **Superseded by [StoreChat].** The reference has no such class: editing state lives on
 * `StoreChat` as a single [StoreChat.EditingMessage], dispatcher-confined and snapshotted like
 * the rest of that store. This one survives only because `WidgetChatInput` and `StoreStream`
 * still reference it; migrating those call sites to [StoreChat.setEditingMessage] /
 * [StoreChat.observeEditingMessage] removes it.
 *
 * The keyed-by-channel shape is kept, and the in-progress [Editing.content] added, so nothing is
 * lost in the meantime — the reference's single-slot field is the narrower of the two.
 */
class StoreChatInputEditing {
    /** A message being edited together with whatever is currently typed into the composer. */
    data class Editing(val message: Message, val content: CharSequence)

    private val editing = LinkedHashMap<Long, Editing>()
    private val subject = BehaviorSubject.create<Map<Long, Editing>>(emptyMap())

    @Synchronized fun get(channelId: Long): Message? = editing[channelId]?.message

    /** The uncommitted edit text, which must survive a rotation or a trip to the background. */
    @Synchronized fun getContent(channelId: Long): CharSequence? = editing[channelId]?.content

    fun observe(channelId: Long): Observable<Message?> =
        subject.map { it[channelId]?.message }.distinctUntilChanged()

    fun observeEditing(channelId: Long): Observable<Editing?> =
        subject.map { it[channelId] }.distinctUntilChanged()

    @Synchronized
    @JvmOverloads
    fun start(message: Message, content: CharSequence = message.content.orEmpty()) {
        editing[message.channelId] = Editing(message, content)
        publish()
    }

    /** Records the composer's current text without changing which message is being edited. */
    @Synchronized
    fun setContent(channelId: Long, content: CharSequence) {
        val current = editing[channelId] ?: return
        if (current.content == content) return
        editing[channelId] = current.copy(content = content)
        publish()
    }

    @Synchronized fun stop(channelId: Long) {
        if (editing.remove(channelId) != null) publish()
    }

    @Synchronized fun clear() {
        if (editing.isNotEmpty()) {
            editing.clear()
            publish()
        }
    }

    private fun publish() = subject.onNext(LinkedHashMap(editing))
}

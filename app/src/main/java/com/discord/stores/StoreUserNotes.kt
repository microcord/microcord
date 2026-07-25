package com.discord.stores

import com.discord.models.domain.ModelUserNote
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import rx.Observable

class StoreUserNotes(
    private val dispatcher: Dispatcher,
    private val observationDeck: ObservationDeck = ObservationDeckProvider.get(),
    private val loadNote: (Long, (ModelUserNote) -> Unit, () -> Unit) -> Unit,
    private val saveNote: (Long, String, () -> Unit, () -> Unit) -> Unit,
) : StoreV2() {
    sealed class UserNoteState {
        data object Empty : UserNoteState()
        data object Loading : UserNoteState()
        data class Loaded(val note: ModelUserNote) : UserNoteState()
    }

    private val notesByUserId = HashMap<Long, UserNoteState>()
    private var notesByUserIdSnapshot: Map<Long, UserNoteState> = emptyMap()

    fun getNotesByUserId(): Map<Long, UserNoteState> = notesByUserIdSnapshot

    fun observeUserNote(userId: Long): Observable<UserNoteState> =
        observationDeck.connectRx(this) { notesByUserIdSnapshot[userId] ?: UserNoteState.Empty }
            .distinctUntilChanged()

    fun loadNote(userId: Long) = dispatcher.schedule {
        if (notesByUserId.containsKey(userId)) return@schedule
        notesByUserId[userId] = UserNoteState.Loading
        markChanged()
        loadNote(
            userId,
            { note -> dispatcher.schedule { handleRequestUserNoteSuccess(note) } },
            { dispatcher.schedule { handleRequestUserNoteError(userId) } },
        )
    }

    fun saveNote(userId: Long, note: String) {
        saveNote(
            userId,
            note,
            { dispatcher.schedule { updateNoteInternal(userId, note) } },
            {},
        )
    }

    @StoreThread
    fun handleConnectionOpen() = clear()

    @StoreThread
    fun handlePreLogout() = clear()

    @StoreThread
    fun handleNoteUpdate(update: ModelUserNote.Update) = updateNoteInternal(update.id, update.note)

    override fun snapshotData() {
        notesByUserIdSnapshot = HashMap(notesByUserId)
    }

    @StoreThread
    private fun handleRequestUserNoteSuccess(note: ModelUserNote) =
        updateNoteInternal(note.noteUserId, note.note.orEmpty())

    @StoreThread
    private fun handleRequestUserNoteError(userId: Long) {
        notesByUserId[userId] = UserNoteState.Empty
        markChanged()
    }

    @StoreThread
    private fun updateNoteInternal(userId: Long, note: String) {
        StoreThreadScheduler.assertCurrentThread()
        notesByUserId[userId] = if (note.isBlank()) {
            UserNoteState.Empty
        } else {
            UserNoteState.Loaded(ModelUserNote(userId, note))
        }
        markChanged()
    }

    @StoreThread
    private fun clear() {
        StoreThreadScheduler.assertCurrentThread()
        notesByUserId.clear()
        markChanged()
    }
}

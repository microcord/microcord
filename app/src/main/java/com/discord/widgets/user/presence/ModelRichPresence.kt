package com.discord.widgets.user.presence

import com.discord.api.activity.Activity
import com.discord.api.application.Application
import com.discord.models.presence.Presence
import com.discord.stores.StoreStream
import com.discord.stores.StoreUserPresence
import com.discord.utilities.presence.PresenceUtils
import rx.Observable

/**
 * A user's presence paired with the application behind its primary activity.
 *
 * The presence alone names the activity but not the game's icon or metadata; the rich-presence card
 * needs both, so they are resolved together and delivered as one value.
 */
data class ModelRichPresence(
    val presence: Presence,
    val primaryApplication: Application? = null,
) {

    /** The activity the card renders — the highest-priority non-custom-status activity. */
    val primaryActivity: Activity?
        get() = with(PresenceUtils) { presence.primaryActivity }

    companion object {

        /**
         * Observes [userId]'s rich presence, re-resolving the application whenever the primary
         * activity changes application. Emits `null` while the user has no presence at all.
         *
         * @param observeApplication resolves an application id to its metadata. The reference calls
         *   `StoreStream.getApplication().observeApplication(id)`; `StoreApplication` has not been
         *   reconstructed, so the default resolves to `null` and cards fall back to the activity's
         *   own assets. Pass the real store once it exists.
         */
        @JvmStatic
        @JvmOverloads
        fun get(
            userId: Long,
            storeUserPresence: StoreUserPresence = StoreStream.getPresences(),
            observeApplication: (Long) -> Observable<Application?> = { Observable.just(null) },
        ): Observable<ModelRichPresence?> =
            storeUserPresence.observePresenceForUser(userId)
                .switchMap { presence ->
                    if (presence == null) return@switchMap Observable.just(null)
                    val applicationId = with(PresenceUtils) { presence.primaryActivity }?.applicationId
                        ?: return@switchMap Observable.just(ModelRichPresence(presence))
                    observeApplication(applicationId)
                        .map { application -> ModelRichPresence(presence, application) }
                }
    }
}

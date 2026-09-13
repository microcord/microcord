package com.discord.widgets.user.presence

import com.discord.api.activity.Activity
import com.discord.api.application.Application
import com.discord.models.presence.Presence
import com.discord.stores.StoreStream
import com.discord.stores.StoreUserPresence
import com.discord.utilities.icon.IconUtils
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

    /** A placeholder's visibility does not tell us whether the activity supplied artwork. */
    val applicationIconFallback: String?
        get() {
            val activity = primaryActivity ?: return null
            val assets = activity.assets
            if (listOfNotNull(assets?.largeImage, assets?.smallImage).any {
                    it.isNotBlank() && IconUtils.getAssetImage(activity.applicationId, it) != null
                }) return null
            val application = primaryApplication ?: return null
            return application.icon?.takeIf(String::isNotBlank)?.let { IconUtils.getApplicationIcon(application.id, it) }
        }

    companion object {

        /**
         * Observes [userId]'s rich presence, re-resolving the application whenever the primary
         * activity changes application. Emits `null` while the user has no presence at all.
         *
         * @param observeApplication resolves metadata through the shared application cache/fetcher.
         */
        @JvmStatic
        @JvmOverloads
        fun get(
            userId: Long,
            storeUserPresence: StoreUserPresence = StoreStream.getPresences(),
            observeApplication: (Long) -> Observable<Application?> = { StoreStream.getApplication().observeApplication(it) },
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

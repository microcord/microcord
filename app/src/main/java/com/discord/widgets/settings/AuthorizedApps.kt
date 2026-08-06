package com.discord.widgets.settings

import com.discord.models.domain.ModelOAuth2Token
import com.discord.stores.StoreStream
import com.discord.utilities.rest.RestAPI
import rx.Observable

/**
 * The last `oauth2/tokens` response, so the detail and permissions screens render the app the user
 * just tapped instead of blanking for a round trip.
 *
 * The reference has a real store; this holds one response for the session. It is keyed by user id
 * because logging out does not end the process, and the next account must not see the previous
 * account's applications.
 */
object AuthorizedApps {

    private data class Snapshot(val userId: Long, val tokens: List<ModelOAuth2Token>)

    @Volatile
    private var snapshot: Snapshot? = null

    fun cached(): List<ModelOAuth2Token>? = snapshot?.takeIf { it.userId == currentUserId() }?.tokens

    fun cached(tokenId: Long): ModelOAuth2Token? = cached()?.firstOrNull { it.id == tokenId }

    fun fetch(): Observable<List<ModelOAuth2Token>> = RestAPI.api.getOAuthTokens()
        .doOnNext { snapshot = Snapshot(currentUserId(), it) }

    fun forget(tokenId: Long) {
        snapshot = snapshot?.let { it.copy(tokens = it.tokens.filterNot { token -> token.id == tokenId }) }
    }

    private fun currentUserId(): Long = StoreStream.getUsers().getMe().id
}

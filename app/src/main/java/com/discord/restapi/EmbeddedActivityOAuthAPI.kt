package com.discord.restapi

import com.discord.api.embeddedactivities.EmbeddedActivityOAuthAuthorization
import com.discord.api.embeddedactivities.EmbeddedActivityOAuthAuthorizeBody
import com.discord.api.embeddedactivities.EmbeddedActivityOAuthAuthorizeResponse
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query
import rx.Observable

interface EmbeddedActivityOAuthAPI {
    @POST("oauth2/authorize")
    fun authorize(
        @Query("client_id") clientId: String,
        @Query("response_type") responseType: String,
        @Query("scope") scope: String,
        @Query("state") state: String?,
        @Body body: EmbeddedActivityOAuthAuthorizeBody,
    ): Observable<EmbeddedActivityOAuthAuthorizeResponse>

    @GET("oauth2/@me")
    fun authenticate(
        @Header("Authorization") bearerAccessToken: String,
    ): Observable<EmbeddedActivityOAuthAuthorization>
}

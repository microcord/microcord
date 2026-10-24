package com.discord.restapi

import com.discord.api.applicationdirectory.ApplicationDirectoryApplication
import com.discord.api.applicationdirectory.ApplicationDirectoryCategory
import com.discord.api.applicationdirectory.ApplicationDirectoryCollection
import com.discord.api.applicationdirectory.ApplicationDirectorySearchResponse
import com.discord.api.commands.ApplicationCommandIndex
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import rx.Observable

interface ApplicationDirectoryAPI {
    @GET("application-directory-static/categories")
    fun categories(@Query("locale") locale: String): Observable<List<ApplicationDirectoryCategory>>

    @GET("application-directory-static/search")
    fun search(
        @Query("query") query: String?,
        @Query("guild_id") guildId: Long? = null,
        @Query("page") page: Int,
        @Query("page_size") pageSize: Int,
        @Query("category_id") categoryId: Int? = null,
        @Query("locale") locale: String,
        @Query("integration_type") integrationType: Int? = null,
        @Query("source") source: Int = SOURCE_APPLICATION_DIRECTORY,
    ): Observable<ApplicationDirectorySearchResponse>

    /** The curated shelves the app launcher shows under its own sections. */
    @GET("application-directory-static/collections")
    fun collections(
        @Query("surface") surface: Int,
        @Query("active_state") activeState: Int,
        @Query("platform") platform: Int,
        @Query("locale") locale: String,
        @Query("cache") cache: Boolean = true,
    ): Observable<List<ApplicationDirectoryCollection>>

    @GET("application-directory-static/applications/{applicationId}")
    fun application(
        @Path("applicationId") applicationId: Long,
        @Query("locale") locale: String,
        @Query("with_localizations") withLocalizations: Boolean = false,
    ): Observable<ApplicationDirectoryApplication>

    /** Any application's public profile, including how it installs; not limited to the directory. */
    @GET("applications/{applicationId}/public")
    fun publicApplication(
        @Path("applicationId") applicationId: Long,
        @Query("with_guild") withGuild: Boolean = false,
    ): Observable<ApplicationDirectoryApplication>

    @GET("applications/{applicationId}/application-command-index")
    fun applicationCommandIndex(
        @Path("applicationId") applicationId: Long,
    ): Observable<ApplicationCommandIndex>

    @GET("oauth2/authorize")
    fun previewAuthorization(
        @Query("client_id") clientId: String,
        @Query("state") state: String?,
        @Query("response_type") responseType: String?,
        @Query("redirect_uri") redirectUri: String?,
        @Query("prompt") prompt: String,
        @Query("scope") scope: String?,
        @Query("permissions") permissions: String?,
        @Query("code_challenge") codeChallenge: String?,
        @Query("code_challenge_method") codeChallengeMethod: String?,
        @Query("integration_type") integrationType: Int?,
    ): Observable<RestAPIParams.OAuth2Authorize.ResponseGet>

    @POST("oauth2/authorize")
    fun authorize(
        @Query("client_id") clientId: String,
        @Query("state") state: String?,
        @Query("response_type") responseType: String?,
        @Query("redirect_uri") redirectUri: String?,
        @Query("prompt") prompt: String,
        @Query("scope") scope: String?,
        @Query("code_challenge") codeChallenge: String?,
        @Query("code_challenge_method") codeChallengeMethod: String?,
        @Body body: ApplicationAuthorizationBody,
    ): Observable<RestAPIParams.OAuth2Authorize.ResponsePost>

    companion object {
        const val SOURCE_APPLICATION_DIRECTORY = 0
        const val SOURCE_APP_LAUNCHER = 1
        const val PAGE_SIZE = 48
        const val MAX_PAGE = 100
    }
}

data class ApplicationAuthorizationBody(
    val authorize: Boolean = true,
    val integrationType: Int? = null,
    val permissions: String? = null,
    val guildId: Long? = null,
    val captchaKey: String? = null,
    val captchaRqtoken: String? = null,
)

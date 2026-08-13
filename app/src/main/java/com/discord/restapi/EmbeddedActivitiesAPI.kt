package com.discord.restapi

import com.discord.api.commands.ApplicationCommandIndex
import com.discord.api.embeddedactivities.EmbeddedActivityAttachment
import com.discord.api.embeddedactivities.EmbeddedActivityDiscordConfig
import com.discord.api.embeddedactivities.EmbeddedActivityInstance
import com.discord.api.embeddedactivities.EmbeddedActivityLaunchBody
import com.discord.api.embeddedactivities.EmbeddedActivityProxyTicket
import com.discord.api.embeddedactivities.EmbeddedActivityProxyTicketRequest
import com.discord.api.embeddedactivities.EmbeddedActivitySessionBody
import com.discord.api.embeddedactivities.EmbeddedActivityShelf
import com.discord.api.embeddedactivities.EmbeddedActivityTrustedLink
import retrofit2.Response
import okhttp3.MultipartBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import rx.Observable

interface EmbeddedActivitiesAPI {
    @GET("activities/shelf")
    fun shelf(@Query("guild_id") guildId: Long? = null): Observable<EmbeddedActivityShelf>

    @POST("activities/{channelId}/{applicationId}")
    fun launch(
        @Path("channelId") channelId: Long,
        @Path("applicationId") applicationId: Long,
        @Body body: EmbeddedActivityLaunchBody,
    ): Observable<Response<Void>>

    @GET("applications/{applicationId}/application-command-index")
    fun commandIndex(
        @Path("applicationId") applicationId: Long,
    ): Observable<ApplicationCommandIndex>

    @POST("interactions")
    fun interaction(
        @Body body: RestAPIParams.ApplicationCommand,
    ): Observable<Response<Void>>

    @POST("activities/applications/{applicationId}/activity-instances/{instanceId}/join")
    fun join(
        @Path("applicationId") applicationId: Long,
        @Path("instanceId") instanceId: String,
        @Body body: EmbeddedActivitySessionBody,
    ): Observable<EmbeddedActivityInstance>

    @POST("applications/{applicationId}/activities/{locationId}/instances/{instanceId}/leave")
    fun leave(
        @Path("applicationId") applicationId: Long,
        @Path("locationId") locationId: String,
        @Path("instanceId") instanceId: Long,
        @Body body: EmbeddedActivitySessionBody,
    ): Observable<Response<Void>>

    @GET("activities/discord-config/{applicationId}")
    fun discordConfig(
        @Path("applicationId") applicationId: Long,
    ): Observable<EmbeddedActivityDiscordConfig>

    @GET("applications/{applicationId}/trusted-links/check")
    fun trustedLink(
        @Path("applicationId") applicationId: Long,
        @Query("url") url: String,
    ): Observable<EmbeddedActivityTrustedLink>

    @POST("applications/{applicationId}/proxy-tickets")
    fun proxyTicket(
        @Path("applicationId") applicationId: Long,
        @Body body: EmbeddedActivityProxyTicketRequest,
    ): Observable<EmbeddedActivityProxyTicket>

    /** Backs `INITIATE_IMAGE_UPLOAD`; answers the CDN url the activity is handed. */
    @Multipart
    @POST("applications/{applicationId}/attachment")
    fun uploadAttachment(
        @Path("applicationId") applicationId: Long,
        @Part file: MultipartBody.Part,
    ): Observable<EmbeddedActivityAttachment>
}

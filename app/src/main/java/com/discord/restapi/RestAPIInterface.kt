package com.discord.restapi

import com.discord.api.auth.RegisterResponse
import com.discord.api.channel.Channel
import com.discord.api.fingerprint.FingerprintResponse
import com.discord.api.friendsuggestions.BulkAddFriendsResponse
import com.discord.api.friendsuggestions.FriendSuggestion
import com.discord.api.message.Message
import com.discord.api.user.User
import com.discord.api.user.UserProfile
import com.discord.models.domain.ModelLocationMetadata
import com.discord.models.domain.ModelUserAffinities
import com.discord.models.domain.ModelUserNote
import com.discord.models.domain.ModelUserRelationship
import com.discord.models.domain.ModelUserSettings
import com.discord.models.domain.ModelGateway
import com.discord.models.domain.ModelPhoneVerificationToken
import com.discord.models.domain.auth.ModelLoginResult
import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import rx.Observable

interface RestAPIInterface {
    @GET("gateway")
    fun getGateway(): Observable<ModelGateway>

    @GET("channels/{channelId}")
    fun getChannel(@Path("channelId") channelId: Long): Observable<Channel>

    @GET("channels/{channelId}/messages")
    fun getChannelMessages(
        @Path("channelId") channelId: Long,
        @Query("before") before: Long? = null,
        @Query("after") after: Long? = null,
        @Query("limit") limit: Int? = null,
    ): Observable<List<Message>>

    @GET("channels/{channelId}/messages")
    fun getChannelMessagesAround(
        @Path("channelId") channelId: Long,
        @Query("limit") limit: Int,
        @Query("around") around: Long,
    ): Observable<List<Message>>

    @POST("channels/{channelId}/messages")
    fun sendMessage(
        @Path("channelId") channelId: Long,
        @Body body: RestAPIParams.Message,
    ): Observable<Message>

    @Multipart
    @POST("channels/{channelId}/messages")
    fun sendMessage(
        @Path("channelId") channelId: Long,
        @Part("payload_json") payloadJson: PayloadJSON<RestAPIParams.Message>,
        @Part files: Array<MultipartBody.Part>,
    ): Observable<Message>

    @PATCH("channels/{channelId}/messages/{messageId}")
    fun editMessage(
        @Path("channelId") channelId: Long,
        @Path("messageId") messageId: Long,
        @Body body: RestAPIParams.Message,
    ): Observable<Message>

    @DELETE("channels/{channelId}/messages/{messageId}")
    fun deleteMessage(
        @Path("channelId") channelId: Long,
        @Path("messageId") messageId: Long,
    ): Observable<Void>

    @POST("channels/{channelId}/messages/{messageId}/ack")
    fun postChannelMessagesAck(
        @Path("channelId") channelId: Long,
        @Path("messageId") messageId: Long,
        @Body body: RestAPIParams.ChannelMessagesAck,
    ): Observable<Void>

    @POST("channels/{channelId}/typing")
    fun setUserTyping(
        @Path("channelId") channelId: Long,
        @Body body: RestAPIParams.EmptyBody = RestAPIParams.EmptyBody(),
    ): Observable<Void>

    @GET("channels/{channelId}/pins")
    fun getChannelPins(@Path("channelId") channelId: Long): Observable<List<Message>>

    @GET("users/@me/mentions")
    fun getMentions(
        @Query("limit") limit: Int,
        @Query("roles") roles: Boolean,
        @Query("everyone") everyone: Boolean,
        @Query("guild_id") guildId: Long? = null,
        @Query("before") before: Long? = null,
    ): Observable<List<Message>>

    @PUT("channels/{channelId}/pins/{messageId}")
    fun addChannelPin(
        @Path("channelId") channelId: Long,
        @Path("messageId") messageId: Long,
    ): Observable<Void>

    @DELETE("channels/{channelId}/pins/{messageId}")
    fun deleteChannelPin(
        @Path("channelId") channelId: Long,
        @Path("messageId") messageId: Long,
    ): Observable<Void>

    @PUT("channels/{channelId}/messages/{messageId}/reactions/{reaction}/@me")
    fun addReaction(
        @Path("channelId") channelId: Long,
        @Path("messageId") messageId: Long,
        @Path(value = "reaction", encoded = true) reaction: String,
    ): Observable<Void>

    @DELETE("channels/{channelId}/messages/{messageId}/reactions/{reaction}/@me")
    fun removeSelfReaction(
        @Path("channelId") channelId: Long,
        @Path("messageId") messageId: Long,
        @Path(value = "reaction", encoded = true) reaction: String,
    ): Observable<Void>

    @DELETE("channels/{channelId}/messages/{messageId}/reactions/{reaction}/{userId}")
    fun removeReaction(
        @Path("channelId") channelId: Long,
        @Path("messageId") messageId: Long,
        @Path(value = "reaction", encoded = true) reaction: String,
        @Path("userId") userId: Long,
    ): Observable<Void>

    @DELETE("channels/{channelId}/messages/{messageId}/reactions")
    fun removeAllReactions(
        @Path("channelId") channelId: Long,
        @Path("messageId") messageId: Long,
    ): Observable<Void>

    @GET("channels/{channelId}/messages/{messageId}/reactions/{emoji}")
    fun getReactionUsers(
        @Path("channelId") channelId: Long,
        @Path("messageId") messageId: Long,
        @Path(value = "emoji", encoded = true) emoji: String,
        @Query("limit") limit: Int? = null,
    ): Observable<List<User>>

    @POST("auth/fingerprint")
    fun postAuthFingerprint(@Body body: RestAPIParams.EmptyBody): Observable<FingerprintResponse>

    @POST("auth/login")
    fun postAuthLogin(@Body body: RestAPIParams.AuthLogin): Observable<Response<ModelLoginResult>>

    @POST("auth/register")
    fun postAuthRegister(@Body body: RestAPIParams.AuthRegister): Observable<Response<RegisterResponse>>

    @POST("auth/mfa/totp")
    fun postMFACode(@Body body: RestAPIParams.MFALogin): Observable<Response<ModelLoginResult>>

    @POST("auth/logout")
    fun logout(@Body body: RestAPIParams.UserDevices): Observable<Response<Void>>

    @POST("auth/authorize-ip")
    fun authorizeIP(@Body body: RestAPIParams.AuthorizeIP): Observable<Response<Void>>

    @POST("auth/forgot")
    fun forgotPassword(@Body body: RestAPIParams.ForgotPassword): Observable<Response<Void>>

    @POST("auth/verify/resend")
    fun postAuthVerifyResend(@Body body: RestAPIParams.EmptyBody): Observable<Response<Void>>

    @POST("phone-verifications/verify")
    fun phoneVerificationsVerify(@Body body: RestAPIParams.VerificationCode): Observable<Response<ModelPhoneVerificationToken>>

    @POST("phone-verifications/resend")
    fun phoneVerificationsResend(@Body body: RestAPIParams.VerificationCodeResend): Observable<Void>

    @POST("users/@me/phone")
    fun userPhoneWithToken(@Body body: RestAPIParams.VerificationPhoneCode): Observable<Void>

    @GET("auth/location-metadata")
    fun getLocationMetadata(): Observable<ModelLocationMetadata>

    @GET("users/{userId}")
    fun userGet(@Path("userId") userId: Long): Observable<User>

    @GET("users/{userId}/profile")
    fun userProfileGet(
        @Path("userId") userId: Long,
        @Query("with_mutual_guilds") withMutualGuilds: Boolean = false,
        @Query("guild_id") guildId: Long? = null,
    ): Observable<UserProfile>

    @GET("users/{userId}/relationships")
    fun getRelationships(@Path("userId") userId: Long): Observable<List<ModelUserRelationship>>

    @PUT("users/@me/relationships/{userId}")
    fun addRelationship(
        @Path("userId") userId: Long,
        @Body body: RestAPIParams.UserRelationship,
        @Header("X-Context-Properties") context: String,
    ): Observable<Void>

    @DELETE("users/@me/relationships/{userId}")
    fun removeRelationship(
        @Path("userId") userId: Long,
        @Header("X-Context-Properties") context: String,
    ): Observable<Void>

    @POST("users/@me/relationships")
    fun sendRelationshipRequest(
        @Body body: RestAPIParams.UserRelationship.Add,
        @Header("X-Context-Properties") context: String,
    ): Observable<Void>

    @POST("users/@me/relationships/bulk")
    fun bulkAddRelationships(@Body body: RestAPIParams.UserBulkRelationship): Observable<Response<BulkAddFriendsResponse>>

    @GET("users/@me/notes/{userId}")
    fun getUserNote(@Path("userId") userId: Long): Observable<ModelUserNote>

    @PUT("users/@me/notes/{userId}")
    fun updateUserNotes(
        @Path("userId") userId: Long,
        @Body body: RestAPIParams.UserNoteUpdate,
    ): Observable<Void>

    @GET("friend-suggestions")
    fun getFriendSuggestions(): Observable<List<FriendSuggestion>>

    @DELETE("friend-suggestions/{userId}")
    fun ignoreFriendSuggestion(@Path("userId") userId: Long): Observable<Void>

    @GET("users/@me/affinities/users")
    fun getUserAffinities(): Observable<ModelUserAffinities>

    @POST("users/@me/channels")
    fun userCreateChannel(@Body body: RestAPIParams.CreateChannel): Observable<Channel>

    @PATCH("users/@me/settings")
    fun updateUserSettings(@Body body: RestAPIParams.GuildFoldersUpdate): Observable<ModelUserSettings>

    @PATCH("users/@me/settings")
    fun updateUserSettingsStatus(@Body body: RestAPIParams.UserSettingsStatus): Observable<ModelUserSettings>

    @PATCH("users/@me/settings")
    fun updateUserSettingsCustomStatus(@Body body: RestAPIParams.UserSettingsCustomStatus): Observable<ModelUserSettings>
}

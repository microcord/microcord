package com.discord.restapi

import com.discord.api.activity.ActivityActionConfirmation
import com.discord.api.activity.ActivityMetadata
import com.discord.api.application.Application
import com.discord.api.application.ApplicationAsset
import com.discord.api.auth.AuthSessionsResponse
import com.discord.api.auth.ForgotPasswordResponse
import com.discord.api.auth.PasswordStrength
import com.discord.api.auth.RegisterResponse
import com.discord.api.auth.UsernameEligibility
import com.discord.api.auth.UsernameSuggestion
import com.discord.api.auth.mfa.Authenticator
import com.discord.api.auth.mfa.DisableMfaResponse
import com.discord.api.auth.mfa.EnableMfaResponse
import com.discord.api.auth.mfa.FinishMfaRequestBody
import com.discord.api.auth.mfa.FinishMfaResponse
import com.discord.api.auth.mfa.GetBackupCodesRequestBody
import com.discord.api.auth.mfa.GetBackupCodesResponse
import com.discord.api.auth.mfa.GetBackupCodesSendVerificationKeyRequestBody
import com.discord.api.auth.mfa.GetBackupCodesSendVerificationKeyResponse
import com.discord.api.auth.mfa.GetBackupCodesVerificationRequestBody
import com.discord.api.auth.mfa.SendMfaSmsResponse
import com.discord.api.billing.BillingCountryCode
import com.discord.api.bugreport.BugReportConfig
import com.discord.api.channel.Channel
import com.discord.api.collectibles.CollectibleProduct
import com.discord.api.collectibles.ProfileFrameProduct
import com.discord.api.commands.ApplicationCommandData
import com.discord.api.connectedaccounts.ConnectedAccount
import com.discord.api.creatormonetization.CreatorMonetizationEligibilityRequirements
import com.discord.api.creatormonetization.CreatorMonetizationEnableRequest
import com.discord.api.directory.DirectoryEntryEvent
import com.discord.api.directory.DirectoryEntryGuild
import com.discord.api.fingerprint.FingerprintResponse
import com.discord.api.forum.ForumPostFirstMessages
import com.discord.api.friendsuggestions.BulkAddFriendsResponse
import com.discord.api.friendsuggestions.BulkFriendSuggestions
import com.discord.api.friendsuggestions.FriendSuggestion
import com.discord.api.guild.Guild
import com.discord.api.game.Game
import com.discord.api.guild.GuildOnboarding
import com.discord.api.guild.GuildOnboardingResponses
import com.discord.api.guild.PruneCountResponse
import com.discord.api.guild.VanityUrlResponse
import com.discord.api.guild.preview.GuildPreview
import com.discord.api.guild.profile.GuildProfile
import com.discord.api.guild.welcome.GuildWelcomeScreen
import com.discord.api.guildmember.GuildMember
import com.discord.api.guildmember.PatchGuildMemberBody
import com.discord.api.guildrolesubscription.GuildRoleSubscriptionGroupListing
import com.discord.api.guildrolesubscription.GuildRoleSubscriptionTierFreeTrial
import com.discord.api.guildrolesubscription.GuildRoleSubscriptionTierListing
import com.discord.api.guildrolesubscription.PayoutGroup
import com.discord.api.guildscheduledevent.ApiGuildScheduledEventUser
import com.discord.api.guildscheduledevent.GuildScheduledEvent
import com.discord.api.guildscheduledevent.GuildScheduledEventBroadcast
import com.discord.api.guildscheduledevent.GuildScheduledEventMeUser
import com.discord.api.handoff.CreateHandoffTokenRequest
import com.discord.api.handoff.HandoffToken
import com.discord.api.hubs.EmailVerification
import com.discord.api.hubs.EmailVerificationCode
import com.discord.api.hubs.WaitlistSignup
import com.discord.api.message.Message
import com.discord.api.message.PollVoters
import com.discord.api.premium.ClaimedOutboundPromotion
import com.discord.api.premium.OutboundPromotion
import com.discord.api.report.MenuAPIResponse
import com.discord.api.report.ReportReason
import com.discord.api.report.ReportSubmissionBody
import com.discord.api.role.GuildRole
import com.discord.api.science.Science
import com.discord.api.stageinstance.RecommendedStageInstance
import com.discord.api.stageinstance.StageInstance
import com.discord.api.sticker.Sticker
import com.discord.api.soundboard.SoundboardSound
import com.discord.api.thread.ThreadListing
import com.discord.api.thread.ThreadMember
import com.discord.api.user.PatchUserBody
import com.discord.api.user.PatchUserProfileBody
import com.discord.api.user.User
import com.discord.api.user.UserApplicationIdentities
import com.discord.api.user.UserProfile
import com.discord.api.user.UserSurveyFetchResponse
import com.discord.models.domain.Consents
import com.discord.models.domain.UserSettingsProtoPatch
import com.discord.models.domain.UserSettingsProtoResponse
import com.discord.models.domain.Harvest
import com.discord.models.domain.ModelApplicationStreamPreview
import com.discord.models.domain.ModelAppliedGuildBoost
import com.discord.models.domain.ModelAuditLog
import com.discord.models.domain.ModelBan
import com.discord.models.domain.ModelCall
import com.discord.models.domain.ModelChannelFollowerStatsDto
import com.discord.models.domain.ModelConnectionAccessToken
import com.discord.models.domain.ModelConnectionState
import com.discord.models.domain.ModelEmailChangeConfirm
import com.discord.models.domain.ModelEntitlement
import com.discord.models.domain.ModelGateway
import com.discord.models.domain.ModelGift
import com.discord.models.domain.ModelGuildBoostSlot
import com.discord.models.domain.ModelGuildIntegration
import com.discord.models.domain.ModelGuildTemplate
import com.discord.models.domain.ModelInvite
import com.discord.models.domain.ModelLibraryApplication
import com.discord.models.domain.ModelLocationMetadata
import com.discord.models.domain.ModelMemberVerificationForm
import com.discord.models.domain.ModelMemberVerificationFormResponse
import com.discord.models.domain.ModelNotificationSettings
import com.discord.models.domain.ModelOAuth2Token
import com.discord.models.domain.ModelPhoneVerificationToken
import com.discord.models.domain.ModelRemoteAuthHandshake
import com.discord.models.domain.ModelRtcLatencyRegion
import com.discord.models.domain.ModelSearchResponse
import com.discord.models.domain.ModelSubscription
import com.discord.models.domain.ModelTypingResponse
import com.discord.models.domain.ModelUrl
import com.discord.models.domain.ModelUserAffinities
import com.discord.models.domain.ModelUserNote
import com.discord.models.domain.ModelUserRelationship
import com.discord.models.domain.ModelUserSettings
import com.discord.models.domain.ModelVoiceRegion
import com.discord.models.domain.PatchPaymentSourceRaw
import com.discord.models.domain.PaymentSourceRaw
import com.discord.models.domain.auth.ModelLoginResult
import com.discord.models.domain.billing.ModelInvoicePreview
import com.discord.models.domain.emoji.ModelEmojiGuild
import com.discord.models.domain.spotify.ModelSpotifyTrack
import com.discord.models.experiments.dto.UnauthenticatedUserExperimentsDto
import com.discord.models.gifpicker.dto.GifDto
import com.discord.models.gifpicker.dto.TrendingGifCategoriesResponseDto
import com.discord.models.sticker.dto.ModelStickerPack
import com.discord.models.sticker.dto.ModelStickerStoreDirectory
import com.discord.utilities.captcha.CaptchaRequestPolicy
import com.google.gson.JsonObject
import com.discord.api.attachment.CreateAttachmentsBody
import com.discord.api.attachment.CreateAttachmentsResponse
import com.discord.api.attachment.RefreshAttachmentUrlsBody
import com.discord.api.attachment.RefreshAttachmentUrlsResponse
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Tag
import retrofit2.http.Url
import rx.Observable

/**
 * The Discord v9 REST surface.
 */
interface RestAPIInterface {

    // -- Authentication -------------------------------------------------------------------------

    @POST("auth/fingerprint")
    fun postAuthFingerprint(@Body body: RestAPIParams.EmptyBody): Observable<FingerprintResponse>

    @POST("auth/register")
    fun postAuthRegister(@Body body: RestAPIParams.AuthRegister): Observable<RegisterResponse>

    @POST("auth/register/phone")
    fun postAuthRegisterPhone(@Body body: RestAPIParams.AuthRegisterPhone): Observable<Unit>

    @POST("auth/login")
    fun postAuthLogin(@Body body: RestAPIParams.AuthLogin): Observable<Response<ModelLoginResult>>

    @POST("auth/logout")
    fun logout(@Body body: RestAPIParams.UserDevices): Observable<Unit>

    @POST("auth/forgot")
    fun forgotPassword(@Body body: RestAPIParams.ForgotPassword): Observable<ForgotPasswordResponse>

    /**
     * Redeems the ticket from a `discord.com/login/one-time?token=` mail.
     *
     * The only credential recovery completed in-app; `auth/forgot` answering `password_reset`
     * instead mails a `/reset#token=` link, and a fragment is something no client parses.
     */
    @POST("auth/one-time-login")
    fun oneTimeLogin(@Body body: RestAPIParams.OneTimeLogin): Observable<ModelLoginResult>

    @POST("auth/verify/resend")
    fun postAuthVerifyResend(@Body body: RestAPIParams.EmptyBody): Observable<Unit>

    @GET("auth/sessions")
    fun getAuthSessions(): Observable<AuthSessionsResponse>

    /** MFA-gated. */
    @POST("auth/sessions/logout")
    fun logoutAuthSessions(@Body body: RestAPIParams.LogoutAuthSessions): Observable<Unit>

    /** [authenticatorType] is an [com.discord.api.auth.mfa.MfaMethodType] wire name. */
    @POST("auth/mfa/{authenticatorType}")
    fun postMFACode(
        @Path("authenticatorType") authenticatorType: String,
        @Body body: RestAPIParams.MFALogin,
    ): Observable<Response<ModelLoginResult>>

    @POST("auth/mfa/sms/send")
    fun sendMFASms(@Body body: RestAPIParams.MFASendSms): Observable<SendMfaSmsResponse>

    /** Elevates the session for a sensitive action; the JWT goes back as `X-Discord-MFA-Authorization`. */
    @POST("mfa/finish")
    fun finishMFA(@Body body: FinishMfaRequestBody): Observable<FinishMfaResponse>

    @POST("auth/authorize-ip")
    fun authorizeIP(@Body body: RestAPIParams.AuthorizeIP): Observable<Unit>

    @POST("users/@me/mfa/codes")
    fun getBackupCodes(@Body body: GetBackupCodesRequestBody): Observable<GetBackupCodesResponse>

    @POST("auth/verify/view-backup-codes-challenge")
    fun getBackupCodesSendVerificationKey(
        @Body body: GetBackupCodesSendVerificationKeyRequestBody,
    ): Observable<GetBackupCodesSendVerificationKeyResponse>

    @POST("users/@me/mfa/codes-verification")
    fun getBackupCodesVerification(
        @Body body: GetBackupCodesVerificationRequestBody,
    ): Observable<GetBackupCodesResponse>

    /** MFA-gated: [com.discord.restapi.MfaInterceptor] supplies the verification, not a body field. */
    @POST("users/@me/mfa/totp/disable")
    fun disableMFA(): Observable<DisableMfaResponse>

    @POST("users/@me/mfa/totp/enable")
    fun enableMFA(@Body body: RestAPIParams.EnableMFA): Observable<EnableMfaResponse>

    @POST("users/@me/mfa/sms/enable")
    fun enableMfaSMS(@Body body: RestAPIParams.ActivateMfaSMS): Observable<Void>

    @POST("users/@me/mfa/sms/disable")
    fun disableMfaSMS(@Body body: RestAPIParams.ActivateMfaSMS): Observable<Void>

    /** No POST counterpart: creating a key needs platform attestation this build cannot produce. */
    @GET("users/@me/mfa/webauthn/credentials")
    fun getAuthenticators(): Observable<List<Authenticator>>

    /** MFA-gated. */
    @PATCH("users/@me/mfa/webauthn/credentials/{authenticatorId}")
    fun renameAuthenticator(
        @Path("authenticatorId") authenticatorId: String,
        @Body body: RestAPIParams.RenameAuthenticator,
    ): Observable<Authenticator>

    /** MFA-gated. Removing the last one disables MFA for the account. */
    @DELETE("users/@me/mfa/webauthn/credentials/{authenticatorId}")
    fun deleteAuthenticator(@Path("authenticatorId") authenticatorId: String): Observable<Unit>

    @GET("auth/location-metadata")
    fun getLocationMetadata(): Observable<ModelLocationMetadata>

    @POST("auth/password/validate")
    fun validatePassword(@Body body: RestAPIParams.ValidatePassword): Observable<PasswordStrength>

    /** Unauthenticated twins of the pomelo endpoints, for use before an account exists. */
    @GET("unique-username/username-suggestions-unauthed")
    fun getUsernameSuggestion(@Query("global_name") globalName: String?): Observable<UsernameSuggestion>

    @POST("unique-username/username-attempt-unauthed")
    fun getUsernameEligibility(@Body body: RestAPIParams.UsernameAttempt): Observable<UsernameEligibility>

    // -- OAuth2 ---------------------------------------------------------------------------------

    @GET("oauth2/tokens")
    fun getOAuthTokens(): Observable<List<ModelOAuth2Token>>

    @DELETE("oauth2/tokens/{oauthId}")
    fun deleteOAuthToken(@Path("oauthId") oauthId: Long): Observable<Void>

    @GET("oauth2/authorize")
    fun getOauth2Authorize(
        @Query("client_id") clientId: String,
        @Query("state") state: String?,
        @Query("response_type") responseType: String? = null,
        @Query("redirect_uri") redirectUrl: String?,
        @Query("prompt") prompt: String = PROMPT_CONSENT,
        @Query("scope") scope: String,
        @Query("permissions") permissions: String? = null,
    ): Observable<RestAPIParams.OAuth2Authorize.ResponseGet>

    @POST("oauth2/authorize")
    fun postOauth2Authorize(
        @Query("client_id") clientId: String,
        @Query("state") state: String?,
        @Query("response_type") responseType: String? = null,
        @Query("redirect_uri") redirectUrl: String?,
        @Query("prompt") prompt: String = PROMPT_CONSENT,
        @Query("scope") scope: String,
        @Query("permissions") permissions: String? = null,
        @Query("code_challenge") codeChallenge: String? = null,
        @Query("code_challenge_method") codeChallengeMethod: String? = null,
        @Body body: Map<String, String> = mapOf("authorize" to "true"),
    ): Observable<RestAPIParams.OAuth2Authorize.ResponsePost>

    @GET("oauth2/samsung/authorize")
    fun getOauth2SamsungAuthorize(
        @Query("client_id") clientId: String,
        @Query("state") state: String?,
        @Query("response_type") responseType: String? = null,
        @Query("redirect_uri") redirectUrl: String?,
        @Query("prompt") prompt: String = PROMPT_CONSENT,
        @Query("scope") scope: String,
    ): Observable<Void>

    @GET("oauth2/applications/{applicationId}/assets")
    fun getApplicationAssets(@Path("applicationId") applicationId: Long): Observable<List<ApplicationAsset>>

    // -- Account --------------------------------------------------------------------------------

    @GET("users/{userId}")
    fun userGet(@Path("userId") userId: Long): Observable<User>

    @POST("users/@me/channels")
    fun userCreateChannel(@Body body: RestAPIParams.CreateChannel): Observable<Channel>

    @POST("users/@me/devices")
    fun userCreateDevice(@Body body: RestAPIParams.UserDevices): Observable<Void>

    @POST("users/@me/phone")
    fun userAddPhone(@Body body: RestAPIParams.Phone): Observable<Void>

    @POST("users/@me/phone/verify")
    fun userAddPhoneNoPassword(@Body body: RestAPIParams.VerificationCodeOnly): Observable<Void>

    @POST("phone-verifications/verify")
    fun phoneVerificationsVerify(
        @Body body: RestAPIParams.VerificationCode,
    ): Observable<ModelPhoneVerificationToken>

    @POST("phone-verifications/resend")
    fun phoneVerificationsResend(@Body body: RestAPIParams.VerificationCodeResend): Observable<Void>

    @POST("users/@me/phone")
    fun userPhoneWithToken(@Body body: RestAPIParams.VerificationPhoneCode): Observable<Void>

    /**
     * Same two-step shape as `users/@me/phone`, for a `REQUIRE_REVERIFIED_PHONE` gate.
     *
     * The number supplied must match the one already on the account; this proves ownership rather
     * than changing it.
     */
    @POST("users/@me/phone/reverify")
    fun userReverifyPhone(@Body body: RestAPIParams.Phone): Observable<Void>

    @POST("users/@me/phone/reverify")
    fun userReverifyPhoneWithToken(@Body body: RestAPIParams.VerificationPhoneCode): Observable<Void>

    @HTTP(method = "DELETE", path = "users/@me/phone", hasBody = true)
    fun userPhoneDelete(@Body body: RestAPIParams.DeletePhone): Observable<Void>

    @PUT("users/@me/email")
    fun userEmail(): Observable<Void>

    @POST("users/@me/email/verify-code")
    fun userEmailVerifyCode(
        @Body body: RestAPIParams.UserEmailConfirmCode,
    ): Observable<ModelEmailChangeConfirm>

    @POST("users/@me/captcha/verify")
    fun userCaptchaVerify(@Body body: RestAPIParams.CaptchaCode): Observable<Void>

    @GET("users/{userId}/profile")
    fun userProfileGet(
        @Path("userId") userId: Long,
        @Query("with_mutual_guilds") withMutualGuilds: Boolean = false,
        @Query("guild_id") guildId: Long? = null,
    ): Observable<UserProfile>

    @GET("users/{userId}/application-identities")
    fun userApplicationIdentitiesGet(
        @Path("userId") userId: Long,
        @Query("with_profiles") withProfiles: Boolean = true,
    ): Observable<UserApplicationIdentities>

    @GET("games")
    fun getGames(@Query("game_ids") gameIds: List<Long>): Observable<List<Game>>

    @PATCH("users/@me/agreements")
    fun userAgreements(@Body body: RestAPIParams.UserAgreements): Observable<Void>

    @PATCH("users/@me")
    fun patchUser(@Body userInfo: RestAPIParams.UserInfo): Observable<User>

    @PATCH("users/@me")
    fun patchUser(@Body patchUserBody: PatchUserBody): Observable<User>

    @PATCH("users/@me/profile")
    fun patchUserProfile(@Body body: PatchUserProfileBody): Observable<UserProfile.ProfileMetadata>

    @GET("users/@me/notes/{userId}")
    fun getUserNote(@Path("userId") userId: Long): Observable<ModelUserNote>

    @PUT("users/@me/notes/{userId}")
    fun updateUserNotes(
        @Path("userId") userId: Long,
        @Body userNoteUpdate: RestAPIParams.UserNoteUpdate,
    ): Observable<Void>

    @PATCH("users/@me/guilds/{guildId}/settings")
    fun updateUserGuildSettings(
        @Path("guildId") guildId: Long,
        @Body userGuildSettings: RestAPIParams.UserGuildSettings,
    ): Observable<ModelNotificationSettings>

    @PATCH("users/@me/guilds/@me/settings")
    fun updatePrivateChannelSettings(
        @Body userGuildSettings: RestAPIParams.UserGuildSettings,
    ): Observable<ModelNotificationSettings>

    @DELETE("users/@me/guilds/{guildId}")
    fun leaveGuild(@Path("guildId") guildId: Long): Observable<Void>

    @HTTP(method = "DELETE", path = "users/@me/guilds/{guildId}", hasBody = true)
    fun leaveGuild(
        @Path("guildId") guildId: Long,
        @Body leaveGuildBody: RestAPIParams.LeaveGuildBody,
    ): Observable<Void>

    @PATCH("users/@me/settings")
    fun updateUserSettings(
        @Body userSettings: RestAPIParams.UserSettings,
    ): Observable<ModelUserSettings>

    @PATCH("users/@me/settings")
    fun updateUserSettingsCustomStatus(
        @Body userSettingsCustomStatus: RestAPIParams.UserSettingsCustomStatus,
    ): Observable<ModelUserSettings>

    @GET("users/@me/settings-proto/2")
    fun getFrecencyUserSettings(): Observable<UserSettingsProtoResponse>

    @PATCH("users/@me/settings-proto/2")
    fun patchFrecencyUserSettings(
        @Body body: UserSettingsProtoPatch,
    ): Observable<UserSettingsProtoResponse>

    // -- Relationships --------------------------------------------------------------------------

    @POST("users/@me/relationships")
    fun sendRelationshipRequest(
        @Body relationship: RestAPIParams.UserRelationship.Add,
        @Header(X_CONTEXT_PROPERTIES) context: String,
    ): Observable<Void>

    @POST("users/@me/relationships/bulk")
    fun bulkAddRelationships(
        @Body body: RestAPIParams.UserBulkRelationship,
    ): Observable<Response<BulkAddFriendsResponse>>

    @DELETE("users/@me/relationships/{userId}")
    fun removeRelationship(
        @Path("userId") userId: Long,
        @Header(X_CONTEXT_PROPERTIES) context: String,
    ): Observable<Void>

    @PUT("users/@me/relationships/{userId}")
    fun addRelationship(
        @Path("userId") userId: Long,
        @Body body: RestAPIParams.UserRelationship,
        @Header(X_CONTEXT_PROPERTIES) context: String,
    ): Observable<Void>

    /** Sets or clears the per-friend nickname. An empty [RestAPIParams.FriendNickname.nickname] clears it. */
    @PATCH("users/@me/relationships/{userId}")
    fun updateRelationshipNickname(
        @Path("userId") userId: Long,
        @Body body: RestAPIParams.FriendNickname,
    ): Observable<Void>

    @GET("users/{userId}/relationships")
    fun getRelationships(@Path("userId") userId: Long): Observable<List<ModelUserRelationship>>

    @GET("users/@me/mentions")
    fun getMentions(
        @Query("limit") limit: Int,
        @Query("roles") roles: Boolean,
        @Query("everyone") everyone: Boolean,
        @Query("guild_id") guildId: Long? = null,
        @Query("before") before: Long? = null,
    ): Observable<List<Message>>

    // -- Rich presence --------------------------------------------------------------------------

    @GET("users/{userId}/sessions/{sessionId}/activities/{applicationId}/metadata")
    fun getActivityMetadata(
        @Path("userId") userId: Long,
        @Path("sessionId") sessionId: String,
        @Path("applicationId") applicationId: Long,
    ): Observable<ActivityMetadata>

    @GET("users/{userId}/sessions/{sessionId}/activities/{applicationId}/{actionType}")
    fun userActivityAction(
        @Path("userId") userId: Long,
        @Path("applicationId") application: Long,
        @Path("sessionId") sessionId: String,
        @Path("actionType") actionType: Int?,
        @Query("channel_id") channelId: Long?,
        @Query("message_id") messageId: Long?,
    ): Observable<ActivityActionConfirmation>

    // -- Connected accounts ---------------------------------------------------------------------

    @GET("users/@me/connections")
    fun getConnections(): Observable<List<ConnectedAccount>>

    @GET("connections/{connection}/authorize")
    fun authorizeConnection(@Path("connection") connection: String): Observable<ModelUrl>

    @PATCH("users/@me/connections/{connection}/{connectionId}")
    fun updateConnection(
        @Path("connection") connection: String,
        @Path("connectionId") connectionId: String,
        @Body connectedAccount: RestAPIParams.ConnectedAccount,
    ): Observable<Response<ConnectedAccount>>

    @PUT("users/@me/connections/contacts/@me")
    fun createConnectionContacts(
        @Body connectedAccountContacts: RestAPIParams.ConnectedAccountContacts,
    ): Observable<ConnectedAccount>

    @PATCH("users/@me/connections/{connection}/{connectionId}")
    fun updateConnectionName(
        @Path("connection") connection: String,
        @Path("connectionId") connectionId: String,
        @Body connectedAccountName: RestAPIParams.ConnectedAccountNameOnly,
    ): Observable<Response<ConnectedAccount>>

    @DELETE("users/@me/connections/{connection}/{connectionId}")
    fun deleteConnection(
        @Path("connection") connection: String,
        @Path("connectionId") connectionId: String,
    ): Observable<Response<Void>>

    @GET("users/@me/connections/{platformType}/{accountId}/access-token")
    fun getConnectionAccessToken(
        @Path("platformType") platformType: String,
        @Path("accountId") accountId: String,
    ): Observable<ModelConnectionAccessToken>

    @PUT("users/@me/connections/contacts/@me/external-friend-list-entries")
    fun uploadContacts(
        @Body uploadContacts: RestAPIParams.UploadContacts,
    ): Observable<Response<BulkFriendSuggestions>>

    @GET("connections/{connection}/callback-continuation/{pinNumber}")
    fun getConnectionState(
        @Path("connection") connection: String,
        @Path("pinNumber") pinNumber: String,
    ): Observable<ModelConnectionState>

    @POST("connections/{connection}/callback")
    fun submitConnectionState(
        @Path("connection") connection: String,
        @Body state: RestAPIParams.ConnectionState,
    ): Observable<Void>

    // -- Friend suggestions, consents, harvest ---------------------------------------------------

    @GET("friend-suggestions")
    fun getFriendSuggestions(): Observable<List<FriendSuggestion>>

    @DELETE("friend-suggestions/{userId}")
    fun ignoreFriendSuggestion(@Path("userId") userId: Long): Observable<Void>

    @GET("users/@me/consent")
    fun getConsents(): Observable<Consents>

    @POST("users/@me/consent")
    fun setConsents(@Body body: RestAPIParams.Consents): Observable<Void>

    @GET("users/@me/harvest")
    fun getHarvestStatus(): Observable<Harvest>

    @POST("users/@me/harvest")
    fun requestHarvest(): Observable<Harvest>

    @POST("users/@me/delete")
    fun deleteAccount(@Body body: RestAPIParams.DisableAccount): Observable<Void>

    @POST("users/@me/disable")
    fun disableAccount(@Body body: RestAPIParams.DisableAccount): Observable<Void>

    // -- Remote authentication -------------------------------------------------------------------

    @POST("users/@me/remote-auth")
    fun postRemoteAuthInitialize(
        @Body body: RestAPIParams.RemoteAuthInitialize,
    ): Observable<ModelRemoteAuthHandshake>

    @POST("users/@me/remote-auth/cancel")
    fun postRemoteAuthCancel(@Body body: RestAPIParams.RemoteAuthCancel): Observable<Void>

    @POST("users/@me/remote-auth/finish")
    fun postRemoteAuthFinish(@Body body: RestAPIParams.RemoteAuthFinish): Observable<Void>

    @GET("users/@me/affinities/users")
    fun getUserAffinities(): Observable<ModelUserAffinities>

    @GET("users/@me/survey")
    fun getUserSurvey(): Observable<Response<UserSurveyFetchResponse>>

    @POST("integrations/{integrationId}/join")
    fun joinGuildFromIntegration(@Path("integrationId") integrationId: String): Observable<Void>

    // -- Interactions ----------------------------------------------------------------------------

    @POST("interactions")
    fun sendComponentInteraction(
        @Body body: RestAPIParams.ComponentInteraction,
    ): Observable<Void>

    @POST("interactions")
    fun sendApplicationCommand(@Body body: RestAPIParams.ApplicationCommand): Observable<Void>

    @POST("interactions")
    fun sendModalInteraction(@Body body: RestAPIParams.ModalInteraction): Observable<Void>

    @Multipart
    @POST("interactions")
    fun sendModalInteraction(
        @Part("payload_json") payloadJson: PayloadJSON<RestAPIParams.ModalInteraction>,
        @Part files: Array<MultipartBody.Part>,
    ): Observable<Void>

    @Multipart
    @POST("interactions")
    fun sendApplicationCommand(
        @Part("payload_json") payloadJson: PayloadJSON<RestAPIParams.ApplicationCommand>,
        @Part files: Array<MultipartBody.Part>,
    ): Observable<Void>

    @GET("applications/{botId}/commands")
    fun getApplicationCommands(
        @Path("botId") botId: Long,
    ): Observable<List<com.discord.api.commands.ApplicationCommand>>

    @GET("guilds/{guildId}/application-command-index")
    fun getGuildApplicationCommandIndex(
        @Path("guildId") guildId: Long,
    ): Observable<com.discord.api.commands.ApplicationCommandIndex>

    @GET("channels/{channelId}/application-command-index")
    fun getChannelApplicationCommandIndex(
        @Path("channelId") channelId: Long,
    ): Observable<com.discord.api.commands.ApplicationCommandIndex>

    @GET("users/@me/application-command-index")
    fun getUserApplicationCommandIndex(): Observable<com.discord.api.commands.ApplicationCommandIndex>

    @GET("channels/{channelId}/messages/{messageId}/interaction-data")
    fun getInteractionData(
        @Path("channelId") channelId: Long,
        @Path("messageId") messageId: Long,
    ): Observable<ApplicationCommandData>

    // -- Messages --------------------------------------------------------------------------------

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
        @Body message: RestAPIParams.Message,
        @Tag captcha: CaptchaRequestPolicy?,
    ): Observable<Message>

    @POST("channels/{channelId}/greet")
    fun sendGreetMessage(
        @Path("channelId") channelId: Long,
        @Body body: RestAPIParams.GreetMessage,
    ): Observable<Message>

    @Multipart
    @POST("channels/{channelId}/messages")
    fun sendMessage(
        @Path("channelId") channelId: Long,
        @Part("payload_json") payloadJson: PayloadJSON<RestAPIParams.Message>,
        @Part files: Array<MultipartBody.Part>,
        @Tag captcha: CaptchaRequestPolicy?,
    ): Observable<Message>

    /**
     * Reserves a storage slot per file and returns a presigned URL to PUT each one to.
     *
     * The upload happens before the message exists; the message then claims the finished files by
     * `uploaded_filename`, so a failed upload costs no message and leaves nothing half-sent.
     */
    @POST("channels/{channelId}/attachments")
    fun createAttachments(
        @Path("channelId") channelId: Long,
        @Body body: CreateAttachmentsBody,
    ): Observable<CreateAttachmentsResponse>

    /** Releases a reserved upload the message never claimed. */
    @DELETE("attachments/{uploadFilename}")
    fun deleteAttachment(@Path("uploadFilename", encoded = true) uploadFilename: String): Observable<Void>

    /** Re-signs CDN links whose signature has expired. Accepts 1-50 at a time. */
    @POST("attachments/refresh-urls")
    fun refreshAttachmentUrls(
        @Body body: RefreshAttachmentUrlsBody,
    ): Observable<RefreshAttachmentUrlsResponse>

    @PUT("channels/{channelId}/polls/{messageId}/answers/@me")
    fun votePoll(
        @Path("channelId") channelId: Long,
        @Path("messageId") messageId: Long,
        @Body body: RestAPIParams.PollVote,
    ): Observable<Void>

    @POST("channels/{channelId}/polls/{messageId}/expire")
    fun expirePoll(
        @Path("channelId") channelId: Long,
        @Path("messageId") messageId: Long,
    ): Observable<Void>

    @GET("channels/{channelId}/polls/{messageId}/answers/{answerId}")
    fun getPollVoters(
        @Path("channelId") channelId: Long,
        @Path("messageId") messageId: Long,
        @Path("answerId") answerId: Int,
        @Query("limit") limit: Int = 100,
        @Query("after") after: Long? = null,
    ): Observable<PollVoters>

    @POST("channels/{channelId}/messages/{messageId}/ack")
    fun postChannelMessagesAck(
        @Path("channelId") channelId: Long,
        @Path("messageId") messageId: Long?,
        @Body body: RestAPIParams.ChannelMessagesAck,
    ): Observable<ChannelMessagesAckResponse>

    @POST("guilds/{guildId}/ack/{ackType}/{ackedId}")
    fun postGuildFeatureAck(
        @Path("guildId") guildId: Long,
        @Path("ackType") ackType: Int,
        @Path("ackedId") ackedId: Long,
        @Body body: RestAPIParams.GuildFeatureAck,
    ): Observable<Void>

    @POST("channels/{channelId}/messages/{messageId}/crosspost")
    fun crosspostMessage(
        @Path("channelId") channelId: Long,
        @Path("messageId") messageId: Long?,
    ): Observable<Void>

    @POST("channels/{channelId}/invites")
    fun postChannelInvite(
        @Path("channelId") channelId: Long,
        @Body invite: RestAPIParams.Invite,
    ): Observable<ModelInvite>

    @DELETE("channels/{channelId}/messages/{messageId}")
    fun deleteMessage(
        @Path("channelId") channelId: Long,
        @Path("messageId") messageId: Long,
    ): Observable<Void>

    @PATCH("channels/{channelId}/messages/{messageId}")
    fun editMessage(
        @Path("channelId") channelId: Long,
        @Path("messageId") messageId: Long,
        @Body message: RestAPIParams.Message,
    ): Observable<Message>

    // -- Channels --------------------------------------------------------------------------------

    @GET("channels/{channelId}")
    fun getChannel(@Path("channelId") channelId: Long): Observable<Channel>

    @GET("soundboard-default-sounds")
    fun getDefaultSoundboardSounds(): Observable<List<SoundboardSound>>

    @POST("channels/{channelId}/send-soundboard-sound")
    fun sendSoundboardSound(
        @Path("channelId") channelId: Long,
        @Body body: RestAPIParams.SendSoundboardSound,
    ): Observable<Unit>

    @PATCH("channels/{channelId}")
    fun editTextChannel(
        @Path("channelId") channelId: Long,
        @Body body: RestAPIParams.TextChannel,
    ): Observable<Channel>

    @PATCH("channels/{channelId}")
    fun editTopicalChannel(
        @Path("channelId") channelId: Long,
        @Body body: RestAPIParams.TopicalChannel,
    ): Observable<Channel>

    @PATCH("channels/{channelId}")
    fun editVoiceChannel(
        @Path("channelId") channelId: Long,
        @Body body: RestAPIParams.VoiceChannel,
    ): Observable<Channel>

    @PATCH("channels/{channelId}")
    fun editGroupDM(
        @Path("channelId") channelId: Long,
        @Body body: RestAPIParams.GroupDM,
    ): Observable<Channel>

    @PUT("channels/{channelId}/recipients/{recipientId}")
    fun convertDMToGroup(
        @Path("channelId") channelId: Long,
        @Path("recipientId") recipientId: Long,
    ): Observable<Channel>

    @PUT("channels/{channelId}/recipients/{recipientId}")
    fun addChannelRecipient(
        @Path("channelId") channelId: Long,
        @Path("recipientId") recipientId: Long,
    ): Observable<Void>

    @DELETE("channels/{channelId}/recipients/{recipientId}")
    fun removeChannelRecipient(
        @Path("channelId") channelId: Long,
        @Path("recipientId") recipientId: Long,
    ): Observable<Void>

    @PUT("channels/{channelId}/recipients/@me")
    fun updateMessageRequest(
        @Path("channelId") channelId: Long,
        @Body body: RestAPIParams.MessageRequestConsentStatus,
    ): Observable<Void>

    @DELETE("channels/{channelId}/recipients/@me")
    fun rejectMessageRequest(@Path("channelId") channelId: Long): Observable<Void>

    @POST("channels/{channelId}/typing")
    fun setUserTyping(
        @Path("channelId") channelId: Long,
        @Body body: RestAPIParams.EmptyBody = RestAPIParams.EmptyBody(),
    ): Observable<ModelTypingResponse>

    @DELETE("channels/{channelId}")
    fun deleteChannel(@Path("channelId") channelId: Long): Observable<Channel>

    @GET("channels/{channelId}/call")
    fun call(@Path("channelId") channelId: Long): Observable<ModelCall.Ringable>

    @POST("channels/{channelId}/call/stop-ringing")
    fun stopRinging(
        @Path("channelId") channelId: Long,
        @Body body: RestAPIParams.Ring,
        @Header(X_CONTEXT_PROPERTIES) context: String,
    ): Observable<Void>

    @POST("channels/{channelId}/call/ring")
    fun ring(
        @Path("channelId") channelId: Long,
        @Body body: RestAPIParams.Ring,
        @Header(X_CONTEXT_PROPERTIES) context: String,
    ): Observable<Void>

    @PUT("channels/{channelId}/permissions/{targetId}")
    fun updatePermissionOverwrites(
        @Path("channelId") channelId: Long,
        @Path("targetId") targetId: Long,
        @Body body: RestAPIParams.ChannelPermissionOverwrites,
    ): Observable<Void>

    @DELETE("channels/{channelId}/permissions/{targetId}")
    fun deletePermissionOverwrites(
        @Path("channelId") channelId: Long,
        @Path("targetId") targetId: Long,
    ): Observable<Void>

    // -- Pins and reactions -----------------------------------------------------------------------

    @POST("channels/{channelId}/pins/ack")
    fun ackPins(@Path("channelId") channelId: Long): Observable<Void>

    @GET("channels/{channelId}/pins")
    fun getChannelPins(@Path("channelId") channelId: Long): Observable<List<Message>>

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
    ): Observable<Void?>

    @DELETE("channels/{channelId}/messages/{messageId}/reactions/{reaction}/@me")
    fun removeSelfReaction(
        @Path("channelId") channelId: Long,
        @Path("messageId") messageId: Long,
        @Path(value = "reaction", encoded = true) reaction: String,
    ): Observable<Void?>

    @DELETE("channels/{channelId}/messages/{messageId}/reactions/{reaction}/{userId}")
    fun removeReaction(
        @Path("channelId") channelId: Long,
        @Path("messageId") messageId: Long,
        @Path(value = "reaction", encoded = true) reaction: String,
        @Path("userId") userId: Long,
    ): Observable<Void?>

    @DELETE("channels/{channelId}/messages/{messageId}/reactions")
    fun removeAllReactions(
        @Path("channelId") channelId: Long,
        @Path("messageId") messageId: Long,
    ): Observable<Void?>

    @GET("channels/{channelId}/messages/{messageId}/reactions/{emoji}")
    fun getReactionUsers(
        @Path("channelId") channelId: Long,
        @Path("messageId") messageId: Long,
        @Path(value = "emoji", encoded = true) emoji: String,
        @Query("limit") limit: Int? = null,
    ): Observable<List<User>>

    // -- Search and followed channels --------------------------------------------------------------

    @GET("channels/{channelId}/messages/search")
    fun searchChannelMessages(
        @Path("channelId") channelId: Long,
        @Query("max_id") oldestMessageId: Long?,
        @Query("author_id") authorIds: List<String>?,
        @Query("mentions") mentionsIds: List<String>?,
        @Query("has") has: List<String>?,
        @Query("content") content: List<String>?,
        @Query("attempts") attempts: Int?,
        @Query("include_nsfw") includeNsfw: Boolean?,
    ): Observable<ModelSearchResponse>

    @GET("channels/{channelId}/follower-stats")
    fun getChannelFollowerStats(
        @Path("channelId") channelId: Long,
    ): Observable<ModelChannelFollowerStatsDto>

    @POST("channels/{channelId}/followers")
    fun createChannelFollower(
        @Path("channelId") channelId: Long,
        @Body body: RestAPIParams.ChannelFollowerPost,
    ): Observable<Void>

    // -- Threads and forums -------------------------------------------------------------------------

    @PATCH("channels/{channelId}/thread-members/@me/settings")
    fun updateThreadMemberSettings(
        @Path("channelId") channelId: Long,
        @Body body: RestAPIParams.ThreadMemberSettings,
    ): Observable<ThreadMember>

    @POST("channels/{channelId}/threads")
    fun createThread(
        @Path("channelId") channelId: Long,
        @Query("location") location: String?,
        @Body body: RestAPIParams.ThreadCreationSettings,
    ): Observable<Channel>

    @Multipart
    @POST("channels/{channelId}/threads?has_message=true")
    fun createThreadWithMessage(
        @Path("channelId") channelId: Long,
        @Part("name") name: String,
        @Part("content") content: String,
        @Part("applied_tags") appliedTags: List<Long>,
        @Part("sticker_ids") stickerIds: List<Long>,
        @Part("type") type: Int,
        @Part("auto_archive_duration") autoArchiveDuration: Int?,
        @Part files: Array<MultipartBody.Part>,
    ): Observable<Channel>

    /** `use_nested_fields` is what makes the server read the nested `message` object. */
    @POST("channels/{channelId}/threads?use_nested_fields=true")
    fun createForumPost(
        @Path("channelId") channelId: Long,
        @Body body: RestAPIParams.ForumThreadCreation,
    ): Observable<Channel>

    @POST("channels/{channelId}/messages/{messageId}/threads")
    fun createThreadFromMessage(
        @Path("channelId") channelId: Long,
        @Path("messageId") messageId: Long,
        @Query("location") location: String?,
        @Body body: RestAPIParams.ThreadCreationSettings,
    ): Observable<Channel>

    @PATCH("channels/{channelId}")
    fun editThread(
        @Path("channelId") channelId: Long,
        @Body body: RestAPIParams.ThreadSettings,
    ): Observable<Channel>

    @DELETE("channels/{channelId}/thread-members/@me")
    fun leaveThread(
        @Path("channelId") channelId: Long,
        @Query("location") location: String?,
    ): Observable<Void>

    @POST("channels/{channelId}/thread-members/@me")
    fun joinThread(
        @Path("channelId") channelId: Long,
        @Query("location") location: String?,
        @Body body: RestAPIParams.EmptyBody,
    ): Observable<Void>

    @GET("channels/{channelId}/users/@me/threads/archived/private")
    fun getMyPrivateArchivedThreads(
        @Path("channelId") channelId: Long,
        @Query("before") before: Long?,
    ): Observable<ThreadListing>

    @GET("channels/{channelId}/threads/archived/public")
    fun getAllPublicArchivedThreads(
        @Path("channelId") channelId: Long,
        @Query("before") before: String?,
    ): Observable<ThreadListing>

    @GET("channels/{channelId}/threads/archived/private")
    fun getAllPrivateArchivedThreads(
        @Path("channelId") channelId: Long,
        @Query("before") before: String?,
    ): Observable<ThreadListing>

    @GET("channels/{channelId}/threads/search")
    fun searchForumThreads(
        @Path("channelId") channelId: Long,
        @Query("name") name: String? = null,
        @Query("tag_setting") tagSetting: String? = null,
        @Query("archived") archived: Boolean? = null,
        @Query("sort_by") sortBy: String? = null,
        @Query("sort_order") sortOrder: String? = null,
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null,
    ): Observable<ThreadListing>

    @POST("channels/{channelId}/tags")
    fun createForumTag(
        @Path("channelId") channelId: Long,
        @Body body: RestAPIParams.ForumTag,
    ): Observable<Channel>

    @PUT("channels/{channelId}/tags/{tagId}")
    fun updateForumTag(
        @Path("channelId") channelId: Long,
        @Path("tagId") tagId: Long,
        @Body body: RestAPIParams.ForumTag,
    ): Observable<Channel>

    @DELETE("channels/{channelId}/tags/{tagId}")
    fun deleteForumTag(
        @Path("channelId") channelId: Long,
        @Path("tagId") tagId: Long,
    ): Observable<Channel>

    @POST("channels/{channelId}/post-data")
    fun getForumPostData(
        @Path("channelId") channelId: Long,
        @Body body: RestAPIParams.GetForumPostData,
    ): Observable<ForumPostFirstMessages>

    // -- Guilds ---------------------------------------------------------------------------------------

    @POST("guilds/{guildId}/ack")
    fun ackGuild(@Path("guildId") guildId: Long): Observable<Void>

    @POST("read-states/ack-bulk")
    fun bulkAck(@Body body: RestAPIParams.BulkAck): Observable<Void>

    @POST("guilds/{guildId}/channels")
    fun createGuildChannel(
        @Path("guildId") guildId: Long,
        @Body body: RestAPIParams.CreateGuildChannel,
    ): Observable<Response<Channel>>

    @POST("guilds")
    fun createGuild(@Body body: RestAPIParams.CreateGuild): Observable<Response<Guild>>

    @POST("guilds/{guildId}/delete")
    fun deleteGuild(
        @Path("guildId") guildId: Long,
        @Body body: RestAPIParams.DeleteGuild,
    ): Observable<Void>

    @GET("guilds/{guildId}/bans")
    fun getBans(@Path("guildId") guildId: Long): Observable<List<ModelBan>>

    @DELETE("guilds/{guildId}/bans/{userId}")
    fun unbanUser(
        @Path("guildId") guildId: Long,
        @Path("userId") userId: Long,
    ): Observable<Void>

    @GET("guilds/{guildId}/regions")
    fun getGuildVoiceRegions(@Path("guildId") guildId: Long): Observable<List<ModelVoiceRegion>>

    @GET("guilds/{guildId}/emojis")
    fun getGuildEmojis(@Path("guildId") guildId: Long): Observable<List<ModelEmojiGuild>>

    @POST("guilds/{guildId}/emojis")
    fun postGuildEmoji(
        @Path("guildId") guildId: Long,
        @Body body: RestAPIParams.PostGuildEmoji,
    ): Observable<ModelEmojiGuild>

    @PATCH("guilds/{guildId}/emojis/{emojiId}")
    fun patchGuildEmoji(
        @Path("guildId") guildId: Long,
        @Path("emojiId") emojiId: Long,
        @Body body: RestAPIParams.PatchGuildEmoji,
    ): Observable<ModelEmojiGuild>

    @DELETE("guilds/{guildId}/emojis/{emojiId}")
    fun deleteGuildEmoji(
        @Path("guildId") guildId: Long,
        @Path("emojiId") emojiId: Long,
    ): Observable<Void>

    @GET("emojis/{emojiId}/guild")
    fun getEmojiGuild(@Path("emojiId") emojiId: Long): Observable<Guild>

    @PATCH("guilds/{guildId}")
    fun updateGuild(
        @Path("guildId") guildId: Long,
        @Body body: RestAPIParams.UpdateGuild,
    ): Observable<Guild>

    @PUT("guilds/{guildId}/members/@me")
    fun joinGuild(
        @Path("guildId") guildId: Long,
        @Query("lurker") isLurker: Boolean,
        @Query("session_id") sessionId: String?,
        @Query("directory_channel_id") directoryChannelId: Long? = null,
        @Body body: RestAPIParams.InviteCode? = null,
        @Header(X_CONTEXT_PROPERTIES) context: String? = null,
    ): Observable<Guild>

    // -- Guild members ----------------------------------------------------------------------------------

    @PATCH("guilds/{guildId}/members/@me/nick")
    fun changeGuildNickname(
        @Path("guildId") guildId: Long,
        @Body body: RestAPIParams.Nick,
    ): Observable<Void>

    @PATCH("guilds/{guildId}/members/@me")
    fun updateMeGuildMember(
        @Path("guildId") guildId: Long,
        @Body body: PatchGuildMemberBody,
    ): Observable<GuildMember>

    @PATCH("guilds/{guildId}/profile/@me")
    fun patchGuildProfile(
        @Path("guildId") guildId: Long,
        @Body body: PatchUserProfileBody,
    ): Observable<UserProfile.ProfileMetadata>

    @PUT("guilds/{guildId}/bans/{userId}")
    fun banGuildMember(
        @Path("guildId") guildId: Long,
        @Path("userId") userId: Long,
        @Body body: RestAPIParams.BanGuildMember,
        @Header(X_AUDIT_LOG_REASON) reason: String?,
    ): Observable<Void>

    @DELETE("guilds/{guildId}/members/{userId}")
    fun kickGuildMember(
        @Path("guildId") guildId: Long,
        @Path("userId") userId: Long,
        @Header(X_AUDIT_LOG_REASON) reason: String?,
    ): Observable<Void>

    @PATCH("guilds/{guildId}/members/{userId}")
    fun disableGuildCommunication(
        @Path("guildId") guildId: Long,
        @Path("userId") userId: Long,
        @Body body: RestAPIParams.DisableGuildCommunication,
        @Header(X_AUDIT_LOG_REASON) reason: String?,
    ): Observable<Response<Void>>

    @PATCH("guilds/{guildId}/members/{userId}")
    fun changeGuildMember(
        @Path("guildId") guildId: Long,
        @Path("userId") userId: Long,
        @Body body: RestAPIParams.GuildMember,
    ): Observable<Void>

    @PATCH("guilds/{guildId}/members/{userId}")
    fun disconnectGuildMember(
        @Path("guildId") guildId: Long,
        @Path("userId") userId: Long,
        @Body body: RestAPIParams.GuildMemberDisconnect,
    ): Observable<Void>

    // -- Guild roles --------------------------------------------------------------------------------------

    @POST("guilds/{guildId}/roles")
    fun createRole(@Path("guildId") guildId: Long): Observable<GuildRole>

    @PATCH("guilds/{guildId}/roles/{roleId}")
    fun updateRole(
        @Path("guildId") guildId: Long,
        @Path("roleId") roleId: Long,
        @Body body: RestAPIParams.Role,
    ): Observable<Void?>

    @PATCH("guilds/{guildId}/roles")
    fun batchUpdateRole(
        @Path("guildId") guildId: Long,
        @Body body: List<RestAPIParams.Role>,
    ): Observable<Void?>

    @DELETE("guilds/{guildId}/roles/{roleId}")
    fun deleteRole(
        @Path("guildId") guildId: Long,
        @Path("roleId") roleId: Long,
    ): Observable<Void?>

    @GET("guilds/{guildId}/roles/{roleId}/member-ids")
    fun getGuildRoleMemberIds(
        @Path("guildId") guildId: Long,
        @Path("roleId") roleId: Long,
    ): Observable<List<Long>>

    @GET("guilds/{guildId}/roles/member-counts")
    fun getGuildRoleMemberCounts(@Path("guildId") guildId: Long): Observable<Map<Long, Int>>

    // -- Guild moderation ----------------------------------------------------------------------------------

    @POST("guilds/{guildId}/mfa")
    fun setMfaLevel(
        @Path("guildId") guildId: Long,
        @Body body: RestAPIParams.GuildMFA,
    ): Observable<Void>

    @GET("guilds/{guildId}/prune")
    fun getPruneCount(
        @Path("guildId") guildId: Long,
        @Query("days") days: Int,
    ): Observable<PruneCountResponse>

    @POST("guilds/{guildId}/prune")
    fun pruneMembers(
        @Path("guildId") guildId: Long,
        @Body body: RestAPIParams.PruneGuild,
    ): Observable<Void>

    @PATCH("guilds/{guildId}")
    fun transferGuildOwnership(
        @Path("guildId") guildId: Long,
        @Body transferGuildOwnership: RestAPIParams.TransferGuildOwnership,
    ): Observable<Void>

    // -- Guild integrations, invites, vanity URL -------------------------------------------------------------

    @GET("guilds/{guildId}/integrations")
    fun getGuildIntegrations(
        @Path("guildId") guildId: Long,
    ): Observable<List<ModelGuildIntegration>>

    @POST("guilds/{guildId}/integrations/{integrationId}/sync")
    fun syncIntegration(
        @Path("guildId") guildId: Long,
        @Path("integrationId") integrationId: Long,
    ): Observable<Void>

    @PATCH("guilds/{guildId}/integrations/{integrationId}")
    fun updateGuildIntegration(
        @Path("guildId") guildId: Long,
        @Path("integrationId") integrationId: Long,
        @Body body: RestAPIParams.GuildIntegration,
    ): Observable<Void>

    @POST("guilds/{guildId}/integrations")
    fun enableIntegration(
        @Path("guildId") guildId: Long,
        @Body body: RestAPIParams.EnableIntegration,
    ): Observable<Void>

    @DELETE("guilds/{guildId}/integrations/{integrationId}")
    fun deleteGuildIntegration(
        @Path("guildId") guildId: Long,
        @Path("integrationId") integrationId: Long,
    ): Observable<Void>

    @GET("guilds/{guildId}/invites")
    fun getGuildInvites(@Path("guildId") guildId: Long): Observable<List<ModelInvite>>

    @DELETE("invites/{inviteCode}")
    fun revokeInvite(@Path("inviteCode") inviteCode: String): Observable<ModelInvite>

    @GET("guilds/{guildId}/vanity-url")
    fun getVanityUrl(@Path("guildId") guildId: Long): Observable<VanityUrlResponse>

    @PATCH("guilds/{guildId}/vanity-url")
    fun updateVanityUrl(
        @Path("guildId") guildId: Long,
        @Body body: RestAPIParams.VanityUrl,
    ): Observable<VanityUrlResponse>

    @PATCH("guilds/{guildId}/channels")
    fun reorderChannels(
        @Path("guildId") guildId: Long,
        @Body body: List<RestAPIParams.ChannelPosition>,
    ): Observable<Void?>

    @GET("guilds/{guildId}/messages/search")
    fun searchGuildMessages(
        @Path("guildId") guildId: Long,
        @Query("max_id") oldestMessageId: Long?,
        @Query("author_id") authorIds: List<String>?,
        @Query("mentions") mentionsIds: List<String>?,
        @Query("channel_id") inChannelIds: List<String>?,
        @Query("has") has: List<String>?,
        @Query("content") content: List<String>?,
        @Query("attempts") attempts: Int?,
        @Query("include_nsfw") includeNsfw: Boolean?,
    ): Observable<ModelSearchResponse>

    @GET("guilds/{guildId}/audit-logs")
    fun getAuditLogs(
        @Path("guildId") guildId: Long,
        @Query("limit") limit: Int,
        @Query("before") before: Long?,
        @Query("user_id") userId: Long?,
        @Query("action_type") actionType: Int?,
    ): Observable<ModelAuditLog>

    // -- Guild discovery, welcome screen, membership screening ---------------------------------------------------

    @GET("guilds/{guildId}/preview")
    fun getGuildPreview(@Path("guildId") guildId: Long): Observable<GuildPreview>

    @GET("guilds/{guildId}/profile")
    fun getGuildProfile(@Path("guildId") guildId: Long): Observable<GuildProfile>

    @GET("guilds/{guildId}/welcome-screen")
    fun getGuildWelcomeScreen(@Path("guildId") guildId: Long): Observable<GuildWelcomeScreen>

    @GET("guilds/{guildId}/onboarding")
    fun getGuildOnboarding(@Path("guildId") guildId: Long): Observable<GuildOnboarding>

    @POST("guilds/{guildId}/onboarding-responses")
    fun completeGuildOnboarding(
        @Path("guildId") guildId: Long,
        @Body body: GuildOnboardingResponses,
    ): Observable<Response<GuildOnboarding>>

    @GET("guilds/{guildId}/member-verification")
    fun getGuildMemberVerificationForm(
        @Path("guildId") guildId: Long,
    ): Observable<ModelMemberVerificationForm>

    @PUT("guilds/{guildId}/requests/@me")
    fun createGuildJoinRequest(
        @Path("guildId") guildId: Long,
        @Body body: RestAPIParams.MemberVerificationForm,
    ): Observable<ModelMemberVerificationFormResponse>

    @POST("guilds/{guildId}/requests/@me")
    fun resetGuildJoinRequest(
        @Path("guildId") guildId: Long,
    ): Observable<ModelMemberVerificationFormResponse>

    @DELETE("guilds/{guildId}/requests/@me")
    fun deleteGuildJoinRequest(
        @Path("guildId") guildId: Long,
    ): Observable<ModelMemberVerificationFormResponse>

    @PATCH("guilds/{guildId}/requests/@me/ack")
    fun ackGuildJoinRequest(@Path("guildId") guildId: Long): Observable<Void>

    @GET("users/@me/join-request-guilds")
    fun getUserJoinRequestGuilds(): Observable<List<Guild>>

    // -- Guild voice states -----------------------------------------------------------------------------------------

    @PATCH("guilds/{guildId}/voice-states/{userId}")
    fun updateUserVoiceStates(
        @Path("guildId") guildId: Long,
        @Path("userId") userId: Long,
        @Body body: RestAPIParams.ChannelVoiceStateUpdate,
    ): Observable<Void>

    @PATCH("guilds/{guildId}/voice-states/@me")
    fun updateMyVoiceStates(
        @Path("guildId") guildId: Long,
        @Body body: RestAPIParams.ChannelVoiceStateUpdate,
    ): Observable<Void>

    // -- Guild scheduled events --------------------------------------------------------------------------------------

    @GET("guilds/{guildId}/scheduled-events/{eventId}")
    fun getGuildScheduledEvent(
        @Path("guildId") guildId: Long,
        @Path("eventId") eventId: Long,
    ): Observable<GuildScheduledEvent>

    @PATCH("guilds/{guildId}/scheduled-events/{eventId}")
    fun updateGuildScheduledEvent(
        @Path("guildId") guildId: Long,
        @Path("eventId") eventId: Long,
        @Body body: RestAPIParams.UpdateGuildScheduledEventBody,
    ): Observable<Unit>

    @GET("guilds/{guildId}/scheduled-events")
    fun getGuildScheduledEvents(
        @Path("guildId") guildId: Long,
        @Query("with_user_count") withUserCount: Boolean,
    ): Observable<List<GuildScheduledEvent>>

    @GET("users/@me/scheduled-events")
    fun getMeGuildScheduledEvents(
        @Query("guild_ids") guildIds: Long,
    ): Observable<List<GuildScheduledEventMeUser>>

    @PUT("guilds/{guildId}/scheduled-events/{eventId}/users/@me")
    fun createGuildScheduledEventRsvp(
        @Path("guildId") guildId: Long,
        @Path("eventId") eventId: Long,
    ): Observable<Unit>

    @DELETE("guilds/{guildId}/scheduled-events/{eventId}/users/@me")
    fun deleteGuildScheduledEventRsvp(
        @Path("guildId") guildId: Long,
        @Path("eventId") eventId: Long,
    ): Observable<Void>

    @POST("guilds/{guildId}/scheduled-events")
    fun createGuildScheduledEvent(
        @Path("guildId") guildId: Long,
        @Body event: RestAPIParams.CreateGuildScheduledEventBody,
    ): Observable<GuildScheduledEvent>

    @DELETE("guilds/{guildId}/scheduled-events/{eventId}")
    fun deleteGuildScheduledEvent(
        @Path("guildId") guildId: Long,
        @Path("eventId") eventId: Long,
    ): Observable<Void>

    @GET("guilds/{guildId}/scheduled-events/{eventId}/users")
    fun getGuildScheduledEventUsers(
        @Path("guildId") guildId: Long,
        @Path("eventId") eventId: Long,
        @Query("limit") limit: Int,
        @Query("with_member") withMember: Boolean,
        @Query("upgrade_response_type") upgradeResponseType: Boolean,
    ): Observable<List<ApiGuildScheduledEventUser>>

    // -- Invites and templates -----------------------------------------------------------------------------------------

    @POST("invites/{code}")
    fun postInviteCode(
        @Path("code") code: String,
        @Body body: RestAPIParams.InviteCode,
        @Header(X_CONTEXT_PROPERTIES) context: String,
    ): Observable<ModelInvite>

    @GET("invites/{code}")
    fun getInviteCode(
        @Path("code") code: String,
        @Query("with_counts") withCounts: Boolean,
        @Query("guild_scheduled_event_id") guildScheduledEventId: Long? = null,
    ): Observable<ModelInvite>

    @GET("guilds/templates/{guildTemplateCode}")
    fun getGuildTemplateCode(
        @Path("guildTemplateCode") guildTemplateCode: String,
    ): Observable<ModelGuildTemplate>

    @POST("guilds/templates/{guildTemplateCode}")
    fun createGuildFromTemplate(
        @Path("guildTemplateCode") guildTemplateCode: String,
        @Body body: RestAPIParams.CreateGuildFromTemplate,
    ): Observable<Guild>

    // -- Applications and analytics ---------------------------------------------------------------------------------------

    @GET("applications/public")
    fun getApplications(@Query("application_ids") appIds: Long): Observable<List<Application>>

    @GET("guilds/{guildId}/applications")
    fun getGuildApplications(
        @Path("guildId") guildId: Long,
        @Query("include_team") includeTeam: Boolean,
    ): Observable<List<Application>>

    @GET("applications/{applicationId}/payment-payout-groups")
    fun getPaymentPayoutGroups(
        @Path("applicationId") applicationId: Long,
    ): Observable<List<PayoutGroup>>

    @POST("science")
    fun science(@Body body: Science): Observable<Void>

    @GET("users/@me/library")
    fun getLibrary(): Observable<List<ModelLibraryApplication>>

    @POST("debug-logs/4/{filename}")
    fun uploadLog(@Path("filename") filename: String, @Body content: String): Observable<Void>

    @Multipart
    @POST("debug-logs/multi/4")
    fun uploadLogs(@Part files: Array<MultipartBody.Part>): Observable<Void>

    // -- Gifting and entitlements --------------------------------------------------------------------------------------------

    @GET("entitlements/gift-codes/{code}")
    fun resolveGiftCode(
        @Path("code") code: String,
        @Query("with_application") withApplication: Boolean,
        @Query("with_subscription_plan") withSubscription: Boolean,
    ): Observable<ModelGift>

    @GET("users/@me/entitlements/gift-codes")
    fun resolveSkuIdGift(
        @Query("sku_id") skuId: Long,
        @Query("subscription_plan_id") subscriptionPlanId: Long?,
    ): Observable<List<ModelGift>>

    @DELETE("users/@me/entitlements/gift-codes/{code}")
    fun revokeGiftCode(@Path("code") code: String): Observable<Void>

    @POST("users/@me/entitlements/gift-codes")
    fun generateGiftCode(@Body body: RestAPIParams.GenerateGiftCode): Observable<ModelGift>

    @POST("entitlements/gift-codes/{code}/redeem")
    fun acceptGift(@Path("code") code: String): Observable<Void>

    @GET("users/@me/entitlements/gifts")
    fun getGifts(): Observable<List<ModelEntitlement>>

    @GET("users/@me/applications/{applicationId}/entitlements")
    fun getMyEntitlements(
        @Path("applicationId") applicationId: Long,
        @Query("exclude_consumed") excludeConsumed: Boolean = true,
    ): Observable<List<ModelEntitlement>>

    // -- Outbound promotions ------------------------------------------------------------------------------------------------

    @GET("outbound-promotions")
    fun getAllActiveOutboundPromotions(): Observable<List<OutboundPromotion>>

    @GET("outbound-promotions/preview")
    fun getAllPreviewPromotions(): Observable<List<OutboundPromotion>>

    @GET("users/@me/outbound-promotions/codes")
    fun getClaimedOutboundPromotions(): Observable<List<ClaimedOutboundPromotion>>

    @POST("outbound-promotions/{promotionId}/claim")
    fun claimOutboundPromotion(
        @Path("promotionId") promotionId: Long,
    ): Observable<ClaimedOutboundPromotion>

    @GET("tracks/{id}")
    fun getSpotifyTrack(@Path("id") id: String): Observable<ModelSpotifyTrack>

    // -- Billing --------------------------------------------------------------------------------------------------------------

    @GET("users/@me/billing/country-code")
    fun getBillingCountryCode(): Observable<BillingCountryCode>

    @GET("users/@me/billing/payment-sources")
    fun getPaymentSources(): Observable<List<PaymentSourceRaw>>

    @PATCH("users/@me/billing/payment-sources/{paymentSourceId}")
    fun updatePaymentSource(
        @Path("paymentSourceId") paymentSourceId: String,
        @Body patchPaymentSourceRaw: PatchPaymentSourceRaw,
    ): Observable<Void>

    @DELETE("users/@me/billing/payment-sources/{paymentSourceId}")
    fun deletePaymentSource(
        @Path("paymentSourceId") paymentSourceId: String,
    ): Observable<Void>

    @GET("users/@me/billing/subscriptions")
    fun getSubscriptions(): Observable<List<ModelSubscription>>

    @PATCH("users/@me/billing/subscriptions/{subscriptionId}")
    fun updateSubscription(
        @Path("subscriptionId") subscriptionId: String,
        @Body updateSubscription: RestAPIParams.UpdateSubscription,
    ): Observable<Void>

    @DELETE("users/@me/billing/subscriptions/{subscriptionId}")
    fun deleteSubscription(@Path("subscriptionId") subscriptionId: String): Observable<Void>

    @POST("users/@me/billing/invoices/preview")
    fun getInvoicePreview(
        @Body invoicePreviewBody: RestAPIParams.InvoicePreviewBody,
    ): Observable<ModelInvoicePreview>

    @POST("google-play/verify-purchase-token")
    fun verifyPurchaseToken(
        @Body verifyPurchaseBody: RestAPIParams.VerifyPurchaseTokenBody,
    ): Observable<RestAPIParams.VerifyPurchaseResponse>

    /**
     * The reference declares this response as a bare `Object`: the endpoint answers with an opaque
     * acknowledgement the client never inspects.
     */
    @POST("google-play/downgrade-subscription")
    fun downgradeSubscription(
        @Body downgradeSubscriptionBody: RestAPIParams.DowngradeSubscriptionBody,
    ): Observable<Any>

    @POST("google-play/purchase-metadata")
    fun createPurchaseMetadata(
        @Body purchaseMetadataBody: RestAPIParams.PurchaseMetadataBody,
    ): Observable<Unit>

    // -- Streams ---------------------------------------------------------------------------------------------------------------

    @GET("streams/{streamKey}/preview")
    fun getStreamPreview(
        @Path("streamKey") streamKey: String,
        @Query("version") version: Long,
    ): Observable<ModelApplicationStreamPreview>

    @POST("streams/{streamKey}/preview")
    fun postStreamPreview(
        @Path("streamKey") streamKey: String,
        @Body thumbnail: RestAPIParams.Thumbnail,
    ): Observable<Void>

    // -- Guild boosts -------------------------------------------------------------------------------------------------------------

    @PUT("guilds/{guildId}/premium/subscriptions")
    fun subscribeToGuild(
        @Path("guildId") guildId: Long,
        @Body guildBoosting: RestAPIParams.GuildBoosting,
    ): Observable<List<ModelAppliedGuildBoost>>

    @DELETE("guilds/{guildId}/premium/subscriptions/{subscriptionId}")
    fun unsubscribeToGuild(
        @Path("guildId") guildId: Long,
        @Path("subscriptionId") subscriptionId: Long,
    ): Observable<Void>

    @GET("users/@me/guilds/premium/subscription-slots")
    fun getSubscriptionSlots(): Observable<List<ModelGuildBoostSlot>>

    @POST("users/@me/guilds/premium/subscription-slots/{subscriptionSlotId}/cancel")
    fun cancelSubscriptionSlot(
        @Path("subscriptionSlotId") slotId: Long,
    ): Observable<ModelGuildBoostSlot>

    @POST("users/@me/guilds/premium/subscription-slots/{subscriptionSlotId}/uncancel")
    fun uncancelSubscriptionSlot(
        @Path("subscriptionSlotId") slotId: Long,
    ): Observable<ModelGuildBoostSlot>

    // -- GIF picker ------------------------------------------------------------------------------------------------------------------

    @GET("gifs/trending")
    fun getTrendingGifCategories(
        @Query("provider") provider: String,
        @Query("locale") locale: String,
        @Query("media_format") mediaFormat: String,
    ): Observable<TrendingGifCategoriesResponseDto>

    @GET("gifs/trending-gifs")
    fun getTrendingGifCategory(
        @Query("provider") provider: String,
        @Query("locale") locale: String,
        @Query("media_format") mediaFormat: String,
        @Query("limit") limit: Int,
    ): Observable<List<GifDto>>

    @GET("gifs/search")
    fun getGifSearchResults(
        @Query("q") query: String,
        @Query("provider") provider: String,
        @Query("locale") locale: String,
        @Query("media_format") mediaFormat: String,
        @Query("limit") limit: Int,
    ): Observable<List<GifDto>>

    @GET("gifs/trending-search")
    fun getGifTrendingSearchTerms(
        @Query("provider") provider: String,
        @Query("locale") locale: String,
        @Query("limit") limit: Int,
    ): Observable<List<String>>

    @GET("gifs/suggest")
    fun getGifSuggestedSearchTerms(
        @Query("provider") provider: String,
        @Query("q") query: String,
        @Query("locale") locale: String,
        @Query("limit") limit: Int,
    ): Observable<List<String>>

    // -- Reporting -------------------------------------------------------------------------------------------------------------------------

    @GET("reporting/menu/{reportType}")
    fun getReportMenu(@Path("reportType") reportType: String): Observable<MenuAPIResponse>

    @POST("reporting/{reportType}")
    fun submitReport(
        @Path("reportType") reportType: String,
        @Body body: ReportSubmissionBody,
    ): Observable<Unit>

    @GET("report")
    fun report(
        @Query("channel_id") channelId: Long? = null,
        @Query("guild_id") guildId: Long? = null,
        @Query("message_id") messageId: Long? = null,
        @Query("user_id") userId: Long? = null,
    ): Observable<List<ReportReason>>

    @POST("report")
    fun report(@Body body: RestAPIParams.Report): Observable<Unit>

    // -- Stickers and store ---------------------------------------------------------------------------------------------------------------------

    @GET("sticker-packs")
    fun getStickerPacks(): Observable<ModelStickerStoreDirectory>

    @GET("sticker-packs/{packId}")
    fun getStickerPack(@Path("packId") packId: Long): Observable<ModelStickerPack>

    @GET("stickers/{stickerId}")
    fun getSticker(@Path("stickerId") stickerId: Long): Observable<Sticker>

    @GET("stickers/{stickerId}/guild")
    fun getStickerGuild(@Path("stickerId") stickerId: Long): Observable<Guild>

    @GET("users/@me/collectibles-purchases")
    fun getCollectiblesPurchases(): Observable<List<CollectibleProduct>>

    @GET("collectibles-products/{skuId}")
    fun getCollectiblesProduct(
        @Path("skuId") skuId: Long,
        @Query("locale") locale: String,
    ): Observable<UserProfile.ProfileEffectProduct>

    /** The same product, read as a frame; the response carries every item type behind one shape. */
    @GET("collectibles-products/{skuId}")
    fun getProfileFrameProduct(
        @Path("skuId") skuId: Long,
        @Query("locale") locale: String,
    ): Observable<ProfileFrameProduct>

    @POST("store/skus/{skuId}/purchase")
    fun claimSku(
        @Path("skuId") skuId: Long,
        @Body emptyBody: RestAPIParams.EmptyBody,
    ): Observable<Unit>

    @GET("store/price-tiers")
    fun getPriceTiers(@Query("price_tier_type") priceTierType: Int): Observable<List<Int>>

    // -- Stage instances -----------------------------------------------------------------------------------------------------------------------------

    @GET("stage-instances/extra")
    fun getStageInstancesForChannels(
        @Query("channel_ids") channelIds: Set<Long>,
    ): Observable<List<RecommendedStageInstance>>

    @GET("stage-instances")
    fun getRecommendedStageInstances(): Observable<Response<List<RecommendedStageInstance>>>

    @POST("stage-instances")
    fun startStageInstance(
        @Body body: RestAPIParams.StartStageInstanceBody,
    ): Observable<StageInstance>

    @PATCH("stage-instances/{channelId}")
    fun updateStageInstance(
        @Path("channelId") channelId: Long,
        @Body body: RestAPIParams.UpdateStageInstanceBody,
    ): Observable<StageInstance>

    @DELETE("stage-instances/{channelId}")
    fun endStageInstance(@Path("channelId") channelId: Long): Observable<Unit>

    // -- Hubs --------------------------------------------------------------------------------------------------------------------------------------------

    @POST("guilds/automations/email-domain-lookup")
    fun verifyEmail(
        @Body body: RestAPIParams.VerifyEmail,
    ): Observable<Response<EmailVerification>>

    @POST("guilds/automations/email-domain-lookup/verify-code")
    fun verifyEmailCode(
        @Body body: RestAPIParams.VerifyEmailCode,
    ): Observable<Response<EmailVerificationCode>>

    @POST("hub-waitlist/signup")
    fun joinHubWaitlist(@Body body: RestAPIParams.HubWaitlist): Observable<WaitlistSignup>

    // -- Internal bug reports ------------------------------------------------------------------------------------------------------------------------------

    @Multipart
    @POST("private/bug-reports")
    fun sendBugReport(
        @Part("name") name: String,
        @Part("description") description: String?,
        @Part("priority") priority: Int,
        @Part("asana_inbox_id") asanaInboxId: Long?,
        @Part screenshot: MultipartBody.Part?,
    ): Observable<Unit>

    @GET("private/bug-reports")
    fun getBugReportConfig(): Observable<BugReportConfig>

    // -- Directories ------------------------------------------------------------------------------------------------------------------------------------------

    @GET("channels/{channelId}/directory-entries")
    fun getDirectoryEntries(
        @Path("channelId") channelId: Long,
    ): Observable<List<DirectoryEntryGuild>>

    @GET("channels/{channelId}/directory-entries")
    fun getDirectoryGuildScheduledEvents(
        @Path("channelId") channelId: Long,
        @Query("type") type: Int,
    ): Observable<List<DirectoryEntryEvent>>

    @GET("guilds/{guildId}/directory-entries/broadcast")
    fun getDirectoryEntryBroadcastInfo(
        @Path("guildId") guildId: Long,
        @Query("entity_id") entityId: Long?,
        @Query("type") type: Int,
    ): Observable<GuildScheduledEventBroadcast>

    @POST("channels/{channelId}/directory-entry/{guildId}")
    fun addServerToHub(
        @Path("channelId") channelId: Long,
        @Path("guildId") guildId: Long,
        @Body body: RestAPIParams.AddServerBody,
    ): Observable<Response<DirectoryEntryGuild>>

    @PATCH("channels/{channelId}/directory-entry/{guildId}")
    fun modifyServerInHub(
        @Path("channelId") channelId: Long,
        @Path("guildId") guildId: Long,
        @Body body: RestAPIParams.AddServerBody,
    ): Observable<DirectoryEntryGuild>

    @DELETE("channels/{channelId}/directory-entry/{guildId}")
    fun removeServerFromHub(
        @Path("channelId") channelId: Long,
        @Path("guildId") guildId: Long,
    ): Observable<Response<DirectoryEntryGuild>>

    @GET("channels/{channelId}/directory-entries/search")
    fun searchServers(
        @Path("channelId") channelId: Long,
        @Query("query") query: String,
    ): Observable<List<DirectoryEntryGuild>>

    @GET("channels/{channelId}/directory-entries/counts")
    fun getEntryCounts(@Path("channelId") channelId: Long): Observable<Map<Int, Int>>

    // -- Creator monetization ---------------------------------------------------------------------------------------------------------------------------------------

    @GET("guilds/{guildId}/creator-monetization/requirements")
    fun getCreatorMonetizationEligibilityRequirements(
        @Path("guildId") guildId: Long,
    ): Observable<CreatorMonetizationEligibilityRequirements>

    @POST("guilds/{guildId}/creator-monetization/enable-requests")
    fun createCreatorMonetizationEnableRequest(
        @Path("guildId") guildId: Long,
    ): Observable<CreatorMonetizationEnableRequest>

    @POST("guilds/{guildId}/creator-monetization/{requestId}/accept-terms")
    fun acceptCreatorMonetizationTerms(
        @Path("guildId") guildId: Long,
        @Path("requestId") requestId: Long,
    ): Observable<CreatorMonetizationEnableRequest>

    // -- Guild role subscriptions -------------------------------------------------------------------------------------------------------------------------------------

    @POST("guilds/{guildId}/role-subscriptions/group-listings")
    fun createGuildRoleSubscriptionGroupListing(
        @Path("guildId") guildId: Long,
        @Body body: RestAPIParams.CreateGuildRoleSubscriptionGroupListing,
    ): Observable<GuildRoleSubscriptionGroupListing>

    @GET("guilds/{guildId}/role-subscriptions/group-listings")
    fun getGuildRoleSubscriptionGroupListings(
        @Path("guildId") guildId: Long,
    ): Observable<List<GuildRoleSubscriptionGroupListing>>

    @GET("guilds/{guildId}/role-subscriptions/group-listings/{groupListingId}")
    fun getGuildRoleSubscriptionGroupListing(
        @Path("guildId") guildId: Long,
        @Path("groupListingId") groupListingId: Long,
    ): Observable<GuildRoleSubscriptionGroupListing>

    @PATCH("guilds/{guildId}/role-subscriptions/group-listings/{groupListingId}")
    fun updateGuildRoleSubscriptionGroupListing(
        @Path("guildId") guildId: Long,
        @Path("groupListingId") groupListingId: Long,
        @Body body: RestAPIParams.UpdateGuildRoleSubscriptionGroupListing,
    ): Observable<GuildRoleSubscriptionGroupListing>

    @POST("guilds/{guildId}/role-subscriptions/group-listings/{groupListingId}/subscription-listings")
    fun createGuildRoleSubscriptionTier(
        @Path("guildId") guildId: Long,
        @Path("groupListingId") groupListingId: Long,
        @Body body: RestAPIParams.CreateGuildRoleSubscriptionTierListing,
    ): Observable<GuildRoleSubscriptionTierListing>

    @PATCH("guilds/{guildId}/role-subscriptions/group-listings/{groupListingId}/subscription-listings/{listingId}")
    fun updateGuildRoleSubscriptionTierListing(
        @Path("guildId") guildId: Long,
        @Path("groupListingId") groupListingId: Long,
        @Path("listingId") tierListingId: Long,
        @Body body: RestAPIParams.UpdateGuildRoleSubscriptionTierListing,
    ): Observable<GuildRoleSubscriptionTierListing>

    @DELETE("guilds/{guildId}/role-subscriptions/group-listings/{groupListingId}/subscription-listings/{listingId}")
    fun deleteGuildRoleSubscriptionTierListing(
        @Path("guildId") guildId: Long,
        @Path("groupListingId") groupListingId: Long,
        @Path("listingId") tierListingId: Long,
    ): Observable<Void>

    @GET("guilds/{guildId}/role-subscriptions/trials")
    fun getGuildRoleSubscriptionFreeTrials(
        @Path("guildId") guildId: Long,
    ): Observable<List<GuildRoleSubscriptionTierFreeTrial>>

    @PATCH("guilds/{guildId}/role-subscriptions/subscription-listings/{listingId}/trial")
    fun updateGuildRoleSubscriptionTierFreeTrial(
        @Path("guildId") guildId: Long,
        @Path("listingId") listingId: Long,
        @Body body: RestAPIParams.UpdateGuildRoleSubscriptionTierFreeTrial,
    ): Observable<GuildRoleSubscriptionTierFreeTrial>

    // -- Handoff and experiments -------------------------------------------------------------------------------------------------------------------------------------------

    @POST("auth/handoff")
    fun createHandoffToken(@Body body: CreateHandoffTokenRequest): Observable<HandoffToken>

    @get:GET("experiments")
    val experiments: Observable<UnauthenticatedUserExperimentsDto>

    // -- Reconstruction-only declarations ------------------------------------------------------------------------------------------------------------------------------------
    //
    // 126.21 serves `GET gateway` from `com.discord.gateway.rest.RestApi`, and routes every
    // `PATCH users/@me/settings` write through `RestAPIParams.UserSettings`. These declarations
    // exist because the reconstructed gateway bootstrap and settings stores already call them; they
    // produce byte-identical requests to their reference counterparts.

    @GET("gateway")
    fun getGateway(): Observable<ModelGateway>

    @PATCH("users/@me/settings")
    fun updateUserSettings(@Body body: RestAPIParams.GuildFoldersUpdate): Observable<ModelUserSettings>

    @PATCH("users/@me/settings")
    fun updateUserSettingsStatus(@Body body: RestAPIParams.UserSettingsStatus): Observable<ModelUserSettings>

    /** Absolute-URL fetch used for CDN/attachment JSON that does not live under the API base URL. */
    interface Dynamic {
        @GET
        fun get(@Url url: String): Observable<JsonObject>
    }

    /** Absolute-URL fetch returning raw bytes (attachments, avatars, log uploads). */
    interface Files {
        @GET
        fun getFile(@Url url: String): Observable<ResponseBody>
    }

    /**
     * The presigned Google Cloud Storage upload.
     *
     * Deliberately a separate interface with no API base URL and no auth interceptor: the URL
     * already carries its own credentials, and sending the Discord token to a storage bucket would
     * leak it to a third party.
     */
    interface CloudUpload {
        @PUT
        fun put(
            @Url url: String,
            @Body body: RequestBody,
            @Header("Content-Range") contentRange: String?,
        ): Observable<Response<Void>>
    }

    /** Absolute-URL fetch for the voice-region latency probe list. */
    interface RtcLatency {
        @GET
        fun get(@Url url: String): Observable<List<ModelRtcLatencyRegion>>
    }

    companion object {
        const val X_CONTEXT_PROPERTIES = "X-Context-Properties"
        const val X_AUDIT_LOG_REASON = "X-Audit-Log-Reason"
        const val PROMPT_CONSENT = "consent"
    }
}

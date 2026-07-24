package com.discord.restapi

import com.discord.api.auth.RegisterResponse
import com.discord.api.channel.Channel
import com.discord.api.fingerprint.FingerprintResponse
import com.discord.models.domain.ModelLocationMetadata
import com.discord.models.domain.ModelUserSettings
import com.discord.models.domain.ModelGateway
import com.discord.models.domain.ModelPhoneVerificationToken
import com.discord.models.domain.auth.ModelLoginResult
import retrofit2.http.GET
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.PATCH
import retrofit2.Response
import rx.Observable

interface RestAPIInterface {
    @GET("gateway")
    fun getGateway(): Observable<ModelGateway>

    @GET("channels/{channelId}")
    fun getChannel(@Path("channelId") channelId: Long): Observable<Channel>

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

    @PATCH("users/@me/settings")
    fun updateUserSettings(@Body body: RestAPIParams.GuildFoldersUpdate): Observable<ModelUserSettings>
}

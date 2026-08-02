package com.discord.restapi

import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

class RestMetadataLoggingInterceptorTest {
    @Test
    fun `debug build enables logging and release build disables it`() {
        val debugClient = OkHttpClient.Builder().addDebugRestLogging(true).build()
        val releaseClient = OkHttpClient.Builder().addDebugRestLogging(false).build()

        assertTrue(debugClient.interceptors.any { it is RestMetadataLoggingInterceptor })
        assertFalse(releaseClient.interceptors.any { it is RestMetadataLoggingInterceptor })
    }

    @Test
    fun `logs only route metadata without secrets or bodies`() {
        val server = MockWebServer()
        val logs = mutableListOf<String>()
        server.enqueue(
            MockResponse()
                .setHeader("Set-Cookie", "session=response-cookie-secret")
                .setBody("private response body"),
        )
        val client = OkHttpClient.Builder()
            .cookieJar(CookieJar.NO_COOKIES)
            .addDebugRestLogging(true, logs::add)
            .build()
        val api = Retrofit.Builder()
            .baseUrl(server.url("/api/v9/"))
            .client(client)
            .build()
            .create(LoggingApi::class.java)

        val response = api.postMessage(
            channelId = "123456789",
            queryToken = "query-token-secret",
            authorization = "Bearer authorization-secret",
            cookie = "session=request-cookie-secret",
            body = "private request body".toRequestBody("application/json".toMediaType()),
        ).execute()
        assertTrue(response.isSuccessful)
        response.body()?.close()
        server.shutdown()

        assertEquals(1, logs.size)
        val log = logs.single()
        assertTrue(log.startsWith("method=POST path=/channels/{channelId}/messages status=200 durationMs="))
        listOf(
            "123456789",
            "query-token-secret",
            "authorization-secret",
            "request-cookie-secret",
            "response-cookie-secret",
            "private request body",
            "private response body",
        ).forEach { secret -> assertFalse("leaked $secret in $log", log.contains(secret)) }
    }

    private interface LoggingApi {
        @POST("channels/{channelId}/messages")
        fun postMessage(
            @Path("channelId") channelId: String,
            @Query("token") queryToken: String,
            @Header("Authorization") authorization: String,
            @Header("Cookie") cookie: String,
            @Body body: RequestBody,
        ): Call<ResponseBody>
    }
}

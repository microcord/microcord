package com.discord.restapi

import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.MultipartBody
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
import retrofit2.http.Multipart
import retrofit2.http.Part
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

class RestMetadataLoggingInterceptorTest {
    @Test
    fun `saved message summaries and notes are redacted`() {
        val log = RestLogRedactor.redact(
            """{"save_data":{"channel_id":"10","message_id":"20","author_summary":"private author", "channel_summary":"private channel", "message_summary":"private text", "notes":"private note"}}""",
        )
        assertFalse(log.contains("private"))
        assertTrue(log.contains("\"channel_id\":\"10\""))
        assertTrue(log.contains("\"message_id\":\"20\""))
    }

    @Test
    fun `release build logs only while the developer switch is on`() {
        val server = MockWebServer()
        repeat(2) { server.enqueue(MockResponse()) }
        val logs = mutableListOf<String>()
        var switchOn = false
        val client = OkHttpClient.Builder().addDebugRestLogging(false, logs::add) { switchOn }.build()
        fun call() = client.newCall(okhttp3.Request.Builder().url(server.url("/api/v9/users/@me")).build()).execute().close()

        call()
        assertTrue(logs.isEmpty())
        switchOn = true
        call()
        assertTrue(logs.any { it.contains("path=/api/v9/users/@me status=200") })
        server.shutdown()
    }

    @Test
    fun `logs concrete path without query secrets or bodies`() {
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
        assertTrue(log.startsWith("method=POST path=/api/v9/channels/123456789/messages status=200 durationMs="))
        listOf(
            "query-token-secret",
            "authorization-secret",
            "request-cookie-secret",
            "response-cookie-secret",
            "private request body",
            "private response body",
        ).forEach { secret -> assertFalse("leaked $secret in $log", log.contains(secret)) }
    }

    @Test
    fun `verbose logging keeps API errors and redacts multipart secrets`() {
        val server = MockWebServer()
        val logs = mutableListOf<String>()
        server.enqueue(
            MockResponse().setResponseCode(403).setBody(
                """{"code":50001,"message":"Missing Access","content":"private","token":"response-secret"}""",
            ),
        )
        val client = OkHttpClient.Builder()
            .cookieJar(CookieJar.NO_COOKIES)
            .addDebugRestLogging(true, logs::add) { true }
            .build()
        val api = Retrofit.Builder().baseUrl(server.url("/api/v9/")).client(client).build()
            .create(LoggingApi::class.java)
        val payload = """{"name":"private title","content":"private body","nonce":"nonce-secret","applied_tags":["42"]}"""
            .toRequestBody("application/json".toMediaType())
        val file = MultipartBody.Part.createFormData(
            "files[0]",
            "private-filename.png",
            "private-file-bytes".toRequestBody("image/png".toMediaType()),
        )

        api.createPost("123456789", "Bearer authorization-secret", payload, file).execute().body()?.close()
        server.shutdown()

        val log = logs.joinToString("\n")
        assertTrue(log.contains("\"code\":50001"))
        assertTrue(log.contains("Missing Access"))
        assertTrue(log.contains("\"applied_tags\":[\"42\"]"))
        listOf(
            "authorization-secret", "private title", "private body", "nonce-secret",
            "private-filename.png", "private-file-bytes", "response-secret",
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

        @Multipart
        @POST("channels/{channelId}/threads")
        fun createPost(
            @Path("channelId") channelId: String,
            @Header("Authorization") authorization: String,
            @Part("payload_json") payload: RequestBody,
            @Part file: MultipartBody.Part,
        ): Call<ResponseBody>
    }
}

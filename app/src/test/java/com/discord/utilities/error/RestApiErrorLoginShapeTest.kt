package com.discord.utilities.error

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.HttpException

/** The `auth/login` failure shapes, and which key each one lands under. */
class RestApiErrorLoginShapeTest {

    @Test
    fun `field scoped errors land under their field`() {
        val error = errorFrom(
            """{"code":50035,"message":"Invalid Form Body","errors":{"login":{"_errors":""" +
                """[{"code":"INVALID_LOGIN","message":"Login or password is invalid."}]}}}""",
        )
        assertEquals(mapOf("login" to listOf("Login or password is invalid.")), error.messages)
    }

    @Test
    fun `top level errors land under _misc`() {
        val error = errorFrom(
            """{"code":50035,"message":"Invalid Form Body","errors":{"_errors":""" +
                """[{"code":"INVALID_LOGIN","message":"Login or password is invalid."}]}}""",
        )
        assertEquals(mapOf("_misc" to listOf("Login or password is invalid.")), error.messages)
    }

    @Test
    fun `a bodiless failure carries no field errors at all`() {
        val error = errorFrom("""{"message":"You are being rate limited.","retry_after":4.2,"global":false}""")
        assertEquals(emptyMap<String, List<String>>(), error.messages)
        assertEquals("You are being rate limited.", error.message)
    }

    /**
     * `StoreAuthentication.loginBody` reads the error body to spot a suspended account, and
     * `StoreMediaFavorites` reads it to spot code 50105. Consuming it there leaves this classifier
     * with an empty string, which downgrades every login failure to a bare "bad request".
     */
    @Test
    fun `a body read once still classifies`() {
        val response = errorResponse(
            """{"code":50035,"message":"Invalid Form Body","errors":{"login":{"_errors":""" +
                """[{"code":"INVALID_LOGIN","message":"Login or password is invalid."}]}}}""",
        )
        val peeked = response.errorBody()?.source()?.peek()?.readUtf8()
        assertEquals(true, peeked?.contains("INVALID_LOGIN"))

        val error = RestApiError.from(HttpException(response))
        assertEquals(mapOf("login" to listOf("Login or password is invalid.")), error.messages)
        assertEquals("Invalid Form Body", error.message)
    }

    private fun errorFrom(body: String): RestApiError = RestApiError.from(HttpException(errorResponse(body)))

    private fun errorResponse(body: String): retrofit2.Response<Any> = retrofit2.Response.error(
        body.toResponseBody("application/json".toMediaType()),
        Response.Builder()
            .code(400)
            .message("Bad Request")
            .protocol(Protocol.HTTP_1_1)
            .request(Request.Builder().url("https://discord.com/api/v9/auth/login").build())
            .build(),
    )
}

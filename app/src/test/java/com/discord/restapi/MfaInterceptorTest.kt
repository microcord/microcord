package com.discord.restapi

import com.discord.api.auth.mfa.MfaChallenge
import com.discord.api.auth.mfa.MfaMethodType
import com.discord.utilities.mfa.MfaCancellationException
import com.discord.utilities.mfa.MfaElevation
import com.discord.utilities.mfa.MfaSolver
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MfaInterceptorTest {
    private val server = MockWebServer()

    @After
    fun stopServer() = server.shutdown()

    @Test
    fun `a challenged request is retried carrying the elevation header`() {
        server.enqueue(challenge())
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))

        val response = client(RecordingSolver { MfaElevation("jwt-token") }).newCall(post()).execute()

        assertEquals(200, response.code)
        server.takeRequest()
        val retry = server.takeRequest()
        assertEquals("jwt-token", retry.getHeader("X-Discord-MFA-Authorization"))
        // The retry is the same request, body included.
        assertEquals("""{"content":"hi"}""", retry.body.readUtf8())
    }

    @Test
    fun `the challenge handed to the solver keeps ticket and every offered method`() {
        server.enqueue(challenge())
        server.enqueue(MockResponse().setBody("{}"))
        val solver = RecordingSolver { MfaElevation("jwt-token") }

        client(solver).newCall(post()).execute()

        val challenge = solver.challenges.single()
        assertEquals("mfa-ticket", challenge.ticket)
        assertEquals(
            listOf(MfaMethodType.WEBAUTHN, MfaMethodType.TOTP, MfaMethodType.SMS, MfaMethodType.BACKUP),
            challenge.methods.map { it.type },
        )
        assertEquals("webauthn-challenge", challenge.methods.first().challenge)
        assertTrue(challenge.methods[1].backupCodesAllowed)
    }

    @Test
    fun `an unknown method type is dropped rather than offered`() {
        server.enqueue(
            MockResponse().setResponseCode(401).setBody(
                """{"code":60003,"mfa":{"ticket":"t","methods":[{"type":"retina_scan"},{"type":"totp"}]}}""",
            ),
        )
        server.enqueue(MockResponse().setBody("{}"))
        val solver = RecordingSolver { MfaElevation("jwt-token") }

        client(solver).newCall(post()).execute()

        assertEquals(listOf(MfaMethodType.TOTP), solver.challenges.single().methods.map { it.type })
    }

    @Test
    fun `a challenge offering nothing usable is handed back instead of prompting`() {
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("""{"code":60003,"mfa":{"ticket":"t","methods":[{"type":"retina_scan"}]}}"""),
        )
        val solver = RecordingSolver { MfaElevation("jwt-token") }

        val response = client(solver).newCall(post()).execute()

        assertEquals(401, response.code)
        assertTrue(solver.challenges.isEmpty())
    }

    @Test
    fun `a refused elevation is re-challenged, then given up on`() {
        repeat(MfaInterceptor.MAX_CHALLENGES + 1) { server.enqueue(challenge()) }
        val solver = RecordingSolver { MfaElevation("jwt-token") }

        val response = client(solver).newCall(post()).execute()

        assertEquals(401, response.code)
        assertEquals(MfaInterceptor.MAX_CHALLENGES, solver.challenges.size)
        // The caller still gets the body, so it can classify the failure.
        assertTrue(response.body!!.string().contains("60003"))
    }

    @Test
    fun `declining verification fails the request rather than sending it`() {
        server.enqueue(challenge())
        val solver = RecordingSolver {
            throw MfaCancellationException(MfaCancellationException.REASON_DECLINED)
        }

        val failure = runCatching { client(solver).newCall(post()).execute() }.exceptionOrNull()

        assertTrue(failure is MfaCancellationException)
        assertEquals(MfaCancellationException.REASON_DECLINED, (failure as MfaCancellationException).reason)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `an ordinary 401 is passed through untouched`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"code":0,"message":"401: Unauthorized"}"""))
        val solver = RecordingSolver { MfaElevation("jwt-token") }

        val response = client(solver).newCall(post()).execute()

        assertEquals(401, response.code)
        assertTrue(solver.challenges.isEmpty())
        assertEquals("""{"code":0,"message":"401: Unauthorized"}""", response.body!!.string())
    }

    @Test
    fun `the finish exchange is exempt, so a refused ticket cannot nest another prompt`() {
        server.enqueue(challenge())
        val solver = RecordingSolver { MfaElevation("jwt-token") }

        val response = client(solver)
            .newCall(post().newBuilder().url(server.url("/api/v9/mfa/finish")).build())
            .execute()

        assertEquals(401, response.code)
        assertTrue(solver.challenges.isEmpty())
    }

    @Test
    fun `with no solver installed the challenge is handed back`() {
        server.enqueue(challenge())

        val response = client(solver = null).newCall(post()).execute()

        assertEquals(401, response.code)
        assertEquals(1, server.requestCount)
        assertNull(response.request.header("X-Discord-MFA-Authorization"))
    }

    private fun client(solver: MfaSolver?) = OkHttpClient.Builder()
        .addInterceptor(MfaInterceptor { solver })
        .build()

    private fun post() = Request.Builder()
        .url(server.url("/api/v9/users/@me/mfa/totp/disable"))
        .post("""{"content":"hi"}""".toRequestBody())
        .build()

    private fun challenge() = MockResponse().setResponseCode(401).setBody(
        """
        {
          "message": "Two factor is required for this operation",
          "code": 60003,
          "mfa": {
            "ticket": "mfa-ticket",
            "methods": [
              {"type": "webauthn", "challenge": "webauthn-challenge"},
              {"type": "totp", "backup_codes_allowed": true},
              {"type": "sms"},
              {"type": "backup"}
            ]
          }
        }
        """.trimIndent(),
    )

    private class RecordingSolver(
        private val answer: (MfaChallenge) -> MfaElevation,
    ) : MfaSolver {
        val challenges = mutableListOf<MfaChallenge>()

        override fun solve(challenge: MfaChallenge): MfaElevation {
            challenges += challenge
            return answer(challenge)
        }
    }
}

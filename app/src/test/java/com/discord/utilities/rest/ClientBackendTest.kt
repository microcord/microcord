package com.discord.utilities.rest

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientBackendTest {
    private fun client(vararg routes: Pair<String, String>) = OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain ->
            val url = chain.request().url.toString()
            val body = routes.toMap()[url]
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (body == null) 404 else 200).message("")
                .body((body ?: "{}").toResponseBody()).build()
        })
        .build()

    @Test fun discoversASpacebarInstanceFromItsWellKnownDocument() {
        val backend = ClientBackend.discover(
            "chat.example.org/",
            client(
                "https://chat.example.org/.well-known/spacebar" to """{"api":"https://api.example.org/api/v9"}""",
                "https://api.example.org/api/v9/policies/instance/domains" to
                    """{"cdn":"https://cdn.example.org","gateway":"wss://gw.example.org","apiEndpoint":"https://api.example.org/api"}""",
            ),
        )

        assertEquals(
            ClientBackend.Backend(
                api = "https://api.example.org/api/",
                cdn = "https://cdn.example.org",
                media = "https://cdn.example.org",
                gateway = "wss://gw.example.org",
                web = "https://chat.example.org",
            ),
            backend,
        )
    }

    @Test fun withoutTheWellKnownDocumentTheApiIsAssumedAtSlashApi() {
        val backend = ClientBackend.discover(
            "https://chat.example.org",
            client(
                "https://chat.example.org/api/policies/instance/domains" to
                    """{"cdn":"https://chat.example.org/cdn","gateway":"wss://chat.example.org/gateway"}""",
            ),
        )
        assertEquals("https://chat.example.org/api/", backend.api)
    }

    @Test fun cleartextOrCredentialBearingEndpointsAreRefused() {
        assertTrue(ClientBackend.DISCORD.isValid())
        assertFalse(ClientBackend.DISCORD.copy(api = "http://discord.com/api/").isValid())
        assertFalse(ClientBackend.DISCORD.copy(gateway = "ws://gateway.discord.gg").isValid())
        assertFalse(ClientBackend.DISCORD.copy(cdn = "https://user:pass@cdn.example.org").isValid())
    }
}

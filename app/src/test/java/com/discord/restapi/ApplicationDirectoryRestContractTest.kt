package com.discord.restapi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

class ApplicationDirectoryRestContractTest {
    @Test
    fun directoryAndModernOauthRoutesKeepTheirWireContracts() {
        val search = ApplicationDirectoryAPI::class.java.getMethod(
            "search",
            String::class.java,
            java.lang.Long::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Integer::class.java,
            String::class.java,
            Integer::class.java,
            Int::class.javaPrimitiveType,
        )
        assertEquals("application-directory-static/search", search.getAnnotation(GET::class.java).value)
        assertEquals(
            listOf("query", "guild_id", "page", "page_size", "category_id", "locale", "integration_type", "source"),
            search.parameterAnnotations.map { annotations -> annotations.filterIsInstance<Query>().single().value },
        )

        val authorize = ApplicationDirectoryAPI::class.java.methods.single { it.name == "authorize" }
        assertEquals("oauth2/authorize", authorize.getAnnotation(POST::class.java).value)
        assertNotNull(authorize.parameterTypes.last().takeIf { it == ApplicationAuthorizationBody::class.java })
    }
}

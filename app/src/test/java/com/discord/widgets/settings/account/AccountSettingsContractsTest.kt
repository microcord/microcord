package com.discord.widgets.settings.account

import com.discord.R
import com.discord.restapi.RestAPIBuilder
import com.discord.restapi.RestAPIInterface
import com.discord.restapi.RestAPIParams
import okhttp3.CookieJar
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountSettingsContractsTest {

    @Test fun `disable and delete ordinals match 126 21 bytecode`() {
        assertEquals(0, WidgetDisableDeleteAccountDialog.Mode.DISABLE.ordinal)
        assertEquals(1, WidgetDisableDeleteAccountDialog.Mode.DELETE.ordinal)
        assertEquals(R.string.disable_account, WidgetDisableDeleteAccountDialog.Mode.DISABLE.headerStringId)
        assertEquals(R.string.delete_account, WidgetDisableDeleteAccountDialog.Mode.DELETE.headerStringId)
    }

    @Test fun `disable and delete modes call distinct destructive endpoints`() {
        val server = MockWebServer()
        try {
            val api = RestAPIBuilder(server.url("/api/").toString(), CookieJar.NO_COOKIES)
                .build(RestAPIInterface::class.java)
            val body = RestAPIParams.DisableAccount("fixture", "123456")

            server.enqueue(MockResponse().setResponseCode(204))
            WidgetDisableDeleteAccountDialog.Mode.DISABLE.request(api, body).toBlocking().subscribe()
            val disable = server.takeRequest()
            assertEquals("POST", disable.method)
            assertEquals("/api/v9/users/@me/disable", disable.path)
            assertEquals("{\"password\":\"fixture\",\"code\":\"123456\"}", disable.body.readUtf8())

            server.enqueue(MockResponse().setResponseCode(204))
            WidgetDisableDeleteAccountDialog.Mode.DELETE.request(api, body).toBlocking().subscribe()
            val delete = server.takeRequest()
            assertEquals("POST", delete.method)
            assertEquals("/api/v9/users/@me/delete", delete.path)
            assertEquals("{\"password\":\"fixture\",\"code\":\"123456\"}", delete.body.readUtf8())
        } finally {
            server.shutdown()
        }
    }

    @Test fun `confirmed email flow requires verified user in treatment bucket`() {
        assertTrue(WidgetSettingsAccount.shouldUseConfirmedEmailFlow(true, 1))
        assertFalse(WidgetSettingsAccount.shouldUseConfirmedEmailFlow(false, 1))
        assertFalse(WidgetSettingsAccount.shouldUseConfirmedEmailFlow(true, 0))
        assertFalse(WidgetSettingsAccount.shouldUseConfirmedEmailFlow(true, null))
    }

    @Test fun `contact name trims and clears with null`() {
        assertEquals("Manti Kafasi", WidgetSettingsAccountContactsNameEdit.normalizeContactName("  Manti Kafasi  "))
        assertNull(WidgetSettingsAccountContactsNameEdit.normalizeContactName("   "))
    }
}

package com.discord.widgets.hubs

import com.discord.stores.utilities.Default
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HubModelContractTest {

    @Test
    fun constructorAndComponentOrderMatches12621() {
        val addServer = HubAddServerState("Hub", 10L, 2)
        assertEquals("Hub", addServer.component1())
        assertEquals(10L, addServer.component2())
        assertEquals(2, addServer.component3())

        val description = HubDescriptionArgs(1L, 2L, true, "Hub", true, "About", 3)
        assertEquals(1L, description.component1())
        assertEquals(2L, description.component2())
        assertTrue(description.component3())
        assertEquals("Hub", description.component4())
        assertTrue(description.component5())
        assertEquals("About", description.component6())
        assertEquals(3, description.component7())
    }

    @Test
    fun defaultsAndCopyPreserveReferenceState() {
        assertEquals(Default, AddNameState().changeNicknameAsync)
        assertNull(HubDescriptionState().primaryCategoryId)
        assertEquals(4L, HubEmailState().copy(selectedGuildId = 4L).component2())
        assertFalse(RadioSelectorItem(1, "School", false).copy(selected = false).selected)
    }

    @Test
    fun sensitiveModelsDoNotExposeEmailAddresses() {
        val email = "student@example.edu"
        assertFalse(HubAuthenticationArgs(email).toString().contains(email))
        assertFalse(HubDomainArgs(email, emptyList()).toString().contains(email))
        assertFalse(HubWaitlistArgs(email).toString().contains(email))
        assertFalse(HubEmailState(email = email).toString().contains(email))
    }
}

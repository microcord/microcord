package com.discord.widgets.botuikit

import com.discord.api.botuikit.ComponentType
import com.discord.api.botuikit.TextComponent
import com.discord.api.botuikit.TextStyle
import com.discord.stores.StoreApplicationInteractions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModalComponentTest {

    @Test
    fun validatesRequiredAndBoundedTextLikeTheReference() {
        assertFalse(ModalComponent.accepts(text(required = true), ""))
        assertTrue(ModalComponent.accepts(text(required = false), ""))
        assertFalse(ModalComponent.accepts(text(required = true).copy(required = null), ""))
        assertFalse(ModalComponent.accepts(text(required = true), "a"))
        assertTrue(ModalComponent.accepts(text(required = true), "ab"))
        assertTrue(ModalComponent.accepts(text(required = true), "abcd"))
        assertFalse(ModalComponent.accepts(text(required = true), "abcde"))
        assertTrue(
            ModalComponent.accepts(
                text(required = true).copy(minLength = null, maxLength = null),
                "current modal defaults",
            ),
        )
    }

    @Test
    fun onlyTheSubmittedNonceCompletesTheModal() {
        val sent = StoreApplicationInteractions.SentInteraction(1, null, null)
        val interactions = mapOf("nonce" to sent)

        assertNull(ModalComponent.completion(null, interactions))
        assertNull(ModalComponent.completion("other", interactions))
        assertNull(ModalComponent.completion("nonce", interactions))

        sent.state = StoreApplicationInteractions.SentInteractionState.FAILURE
        assertEquals(ModalComponent.Completion.FAILURE, ModalComponent.completion("nonce", interactions))

        sent.state = StoreApplicationInteractions.SentInteractionState.SUCCESS
        assertEquals(ModalComponent.Completion.SUCCESS, ModalComponent.completion("nonce", interactions))
    }

    private fun text(required: Boolean) = TextComponent(
        type = ComponentType.TEXT,
        style = TextStyle.SMALL,
        customId = "field",
        label = "Field",
        value = null,
        placeholder = null,
        minLength = 2,
        maxLength = 4,
        required = required,
    )
}

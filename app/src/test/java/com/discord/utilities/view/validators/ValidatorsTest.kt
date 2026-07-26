package com.discord.utilities.view.validators

import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validator behaviour is asserted against a plain [View]; the `TextInputLayout` subclasses only
 * differ in how they paint the message, which needs an instrumented context.
 */
private fun view(): View = View(null)

/** Fails with [message] unless the view is the [expected] instance. */
private class RequireView(
    private val expected: View,
    private val message: String,
) : InputValidator<View> {
    override fun getErrorMessage(view: View): CharSequence? =
        if (view === expected) null else message
}

private class AlwaysFails(private val message: String) : InputValidator<View> {
    override fun getErrorMessage(view: View): CharSequence? = message
}

private class AlwaysPasses : InputValidator<View> {
    override fun getErrorMessage(view: View): CharSequence? = null
}

/** Records the messages a field was asked to render. */
private class RecordingInput(
    name: String,
    view: View,
    vararg validators: InputValidator<in View>,
) : Input<View>(name, view, *validators) {
    val rendered = mutableListOf<CharSequence?>()

    override fun setErrorMessage(errorMessage: CharSequence?): Boolean {
        rendered.add(errorMessage)
        return !errorMessage.isNullOrBlank()
    }
}

class InputValidatorTest {

    @Test
    fun `a validator that passes yields no message`() {
        assertNull(AlwaysPasses().getErrorMessage(view()))
    }

    @Test
    fun `a validator receives the field's own view`() {
        val v = view()
        assertNull(RequireView(v, "wrong view").getErrorMessage(v))
        assertEquals("wrong view", RequireView(v, "wrong view").getErrorMessage(view()))
    }
}

class InputTest {

    @Test
    fun `a field with no validators is valid`() {
        assertTrue(RecordingInput("name", view()).validate())
    }

    @Test
    fun `every validator must pass`() {
        val input = RecordingInput("name", view(), AlwaysPasses(), AlwaysFails("nope"))
        assertFalse(input.validate())
        assertEquals(listOf<CharSequence?>(null, "nope"), input.rendered)
    }

    @Test
    fun `validation stops at the first failure`() {
        val input = RecordingInput("name", view(), AlwaysFails("first"), AlwaysFails("second"))
        assertFalse(input.validate())
        assertEquals(listOf<CharSequence?>("first"), input.rendered)
    }

    @Test
    fun `validate(false) reports the failure without rendering it`() {
        val input = RecordingInput("name", view(), AlwaysFails("nope"))
        assertFalse(input.validate(showErrors = false))
        assertTrue(input.rendered.isEmpty())
    }

    @Test
    fun `GenericInput delegates rendering to the caller and ignores blank messages`() {
        val v = view()
        val seen = mutableListOf<CharSequence>()
        val input = Input.GenericInput("name", v, AlwaysFails("bad")) { _, message ->
            seen.add(message)
            true
        }
        assertFalse(input.validate())
        assertEquals(listOf<CharSequence>("bad"), seen)

        val blank = Input.GenericInput("name", v, AlwaysFails("   ")) { _, _ -> true }
        // A blank message is not an error, so the field passes.
        assertTrue(blank.validate())
    }
}

class ValidationManagerTest {

    @Test
    fun `an empty manager validates`() {
        assertTrue(ValidationManager().validate())
    }

    @Test
    fun `validate evaluates every field even after one fails`() {
        val good = RecordingInput("good", view(), AlwaysPasses())
        val bad = RecordingInput("bad", view(), AlwaysFails("nope"))
        val alsoBad = RecordingInput("alsoBad", view(), AlwaysFails("nope too"))

        assertFalse(ValidationManager(good, bad, alsoBad).validate())
        assertEquals(listOf<CharSequence?>("nope"), bad.rendered)
        assertEquals(listOf<CharSequence?>("nope too"), alsoBad.rendered)
    }

    @Test
    fun `addInput and removeInput change what is validated`() {
        val bad = RecordingInput("bad", view(), AlwaysFails("nope"))
        val manager = ValidationManager()
        manager.addInput(bad)
        assertFalse(manager.validate())

        manager.removeInput("bad")
        assertTrue(manager.validate())
    }

    @Test
    fun `a field registered twice under the same name replaces the first`() {
        val first = RecordingInput("email", view(), AlwaysFails("first"))
        val second = RecordingInput("email", view(), AlwaysPasses())
        val manager = ValidationManager(first)
        manager.addInput(second)

        assertTrue(manager.validate())
        assertTrue(first.rendered.isEmpty())
    }

    @Test
    fun `setErrors applies the first message per field and returns unmatched keys`() {
        val email = RecordingInput("email", view())
        val password = RecordingInput("password", view())
        val manager = ValidationManager(email, password)

        val unmatched = manager.setErrors(
            mapOf(
                "email" to listOf("Email is invalid", "ignored"),
                "password" to listOf("Password is too short"),
                "captcha_key" to listOf("You are a robot"),
            ),
        )

        assertEquals(listOf<CharSequence?>("Email is invalid"), email.rendered)
        assertEquals(listOf<CharSequence?>("Password is too short"), password.rendered)
        assertEquals(listOf("captcha_key"), unmatched.toList())
    }

    @Test
    fun `setErrors clears fields the server did not complain about`() {
        val email = RecordingInput("email", view())
        val manager = ValidationManager(email)

        manager.setErrors(emptyMap())
        assertEquals(listOf<CharSequence?>(null), email.rendered)
    }
}

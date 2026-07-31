package com.discord.widgets.contact_sync

import com.discord.api.friendsuggestions.FriendSuggestion
import com.discord.api.friendsuggestions.FriendSuggestionReason
import com.discord.api.friendsuggestions.FriendSuggestionReasonType
import com.discord.api.user.User
import com.discord.models.phone.PhoneCountryCode
import com.discord.restapi.RestAPIInterface
import com.discord.utilities.error.RestApiError
import com.discord.utilities.time.Clock
import com.discord.widgets.contact_sync.WidgetContactSyncViewModel.Item
import com.discord.widgets.contact_sync.WidgetContactSyncViewModel.StoreState
import com.discord.widgets.contact_sync.WidgetContactSyncViewModel.ViewState
import com.discord.widgets.contact_sync.WidgetContactSyncViewModel.Views
import java.lang.reflect.Proxy
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import rx.Observable

class ContactSyncTest {

    // region enum contracts

    /**
     * `getLandingToolbarConfig` switches on the ordinal, and the reference's *source* order is
     * alphabetical while its `$VALUES` order is not. Getting this backwards silently swaps the
     * onboarding and default toolbars.
     */
    @Test fun `contact sync mode ordinals match the reference`() {
        assertEquals(0, ContactSyncMode.ONBOARDING.ordinal)
        assertEquals(1, ContactSyncMode.DEFAULT.ordinal)
    }

    /** The ordinals are used directly as `AppViewFlipper` child indexes. */
    @Test fun `view ordinals match the flipper child order`() {
        assertEquals(
            listOf(
                Views.VIEW_LANDING,
                Views.VIEW_ADD_PHONE,
                Views.VIEW_VERIFY_PHONE,
                Views.VIEW_NAME_INPUT,
                Views.VIEW_SUGGESTIONS,
                Views.VIEW_SUGGESTIONS_EMPTY,
            ),
            Views.entries.toList(),
        )
        assertEquals("Suggestions Results", Views.VIEW_SUGGESTIONS.trackingStep)
        assertEquals("Suggestions Results", Views.VIEW_SUGGESTIONS_EMPTY.trackingStep)
    }

    // endregion

    // region privacy

    @Test fun `view state never prints the phone number or the typed name`() {
        val rendered = viewState(phoneNumber = "+15551234567", name = "Ada Lovelace").toString()
        assertFalse(rendered, rendered.contains("5551234567"))
        assertFalse(rendered, rendered.contains("Ada Lovelace"))
        assertTrue(rendered, rendered.contains("<redacted>"))
    }

    @Test fun `store state never prints the phone number`() {
        val rendered = StoreState(
            userPhone = "+15551234567",
            username = "ada#0001",
            countryCode = PhoneCountryCode.DEFAULT_COUNTRY_CODE,
            contactsConnection = null,
        ).toString()
        assertFalse(rendered, rendered.contains("5551234567"))
    }

    @Test fun `friend suggestion row never prints the address book nickname`() {
        val rendered = Item.FriendSuggestionItem(suggestion(7L, "Mum's mobile"), true).toString()
        assertFalse(rendered, rendered.contains("Mum's mobile"))
        assertTrue(rendered, rendered.contains("7"))
    }

    // endregion

    // region flow

    @Test fun `landing without a phone number goes to the add-phone step`() {
        val viewModel = viewModel(ContactSyncMode.DEFAULT)
        viewModel.onLandingNext()
        val state = viewModel.requireViewState()
        assertEquals(Views.VIEW_ADD_PHONE, state.displayedChild)
        assertTrue(state.toolbarConfig.showBackButton)
        assertFalse(state.toolbarConfig.showSkip)
    }

    @Test fun `skipping the add-phone step returns to the landing toolbar for the mode`() {
        val viewModel = viewModel(ContactSyncMode.ONBOARDING)
        viewModel.onLandingNext()
        viewModel.skip()
        val state = viewModel.requireViewState()
        assertEquals(Views.VIEW_LANDING, state.displayedChild)
        // Onboarding cannot go back, but can skip.
        assertFalse(state.toolbarConfig.showBackButton)
        assertTrue(state.toolbarConfig.showSkip)
    }

    @Test fun `denying the permission surfaces the recovery state instead of re-prompting`() {
        val viewModel = viewModel(ContactSyncMode.DEFAULT)
        assertFalse(viewModel.requireViewState().permissionsDenied)
        viewModel.onPermissionsDenied()
        assertTrue(viewModel.requireViewState().permissionsDenied)
        viewModel.onPermissionsBecameAvailable()
        assertFalse(viewModel.requireViewState().permissionsDenied)
    }

    @Test fun `toggling a suggestion adds and removes exactly that user`() {
        val viewModel = viewModel(ContactSyncMode.DEFAULT)
        viewModel.handleToggleFriendSuggestionSelected(11L, true)
        viewModel.handleToggleFriendSuggestionSelected(22L, true)
        assertEquals(listOf(11L, 22L), viewModel.requireViewState().selectedFriendIds)
        viewModel.handleToggleFriendSuggestionSelected(11L, false)
        assertEquals(listOf(22L), viewModel.requireViewState().selectedFriendIds)
    }

    /** The master toggle is "either method on", so clearing it must clear both. */
    @Test fun `discovery toggle drives both discovery methods`() {
        val viewModel = viewModel(ContactSyncMode.DEFAULT)
        viewModel.onPermissionsToggle(false, false)
        assertFalse(viewModel.requireViewState().allowPhone)
        assertFalse(viewModel.requireViewState().allowEmail)
        viewModel.onPermissionsToggle(true, false)
        assertTrue(viewModel.requireViewState().allowPhone)
        assertFalse(viewModel.requireViewState().allowEmail)
    }

    @Test fun `phone captcha requires the reference captcha key`() {
        val oldGate = ContactSyncDependencies.isPhoneVerifyCaptchaEnabled
        var launched: RestApiError? = null
        val viewModel = viewModel(ContactSyncMode.DEFAULT) { launched = it }
        val events = mutableListOf<WidgetContactSyncViewModel.Event>()
        val subscription = viewModel.observeEvents().subscribe(events::add)
        ContactSyncDependencies.isPhoneVerifyCaptchaEnabled = { true }
        try {
            val captcha = httpError(
                """{"captcha_key":["captcha-required"],"captcha_sitekey":"site-key"}""",
            )
            viewModel.handlePhoneSubmittedErrorForTest(captcha)
            assertSame(captcha, launched)

            launched = null
            viewModel.handlePhoneSubmittedErrorForTest(httpError("""{"captcha_sitekey":"site-key"}"""))
            assertNull(launched)
            assertEquals(listOf(WidgetContactSyncViewModel.Event.PhoneInvalid), events)
        } finally {
            subscription.unsubscribe()
            ContactSyncDependencies.isPhoneVerifyCaptchaEnabled = oldGate
        }
    }

    // endregion

    // region analytics

    @Test fun `flow steps report the previous step and its duration`() {
        val clock = MutableClock(0L)
        val sink = RecordingAnalytics()
        val analytics = ContactSyncFlowAnalytics(isOnboarding = false, clock = clock, analytics = sink)

        clock.now = 3_000
        analytics.trackFlowStep("Add Phone Number", skip = false, back = false)
        clock.now = 10_000
        analytics.trackFlowStep("Landing", skip = false, back = true)

        assertEquals(
            listOf(
                Step("Landing", "Add Phone Number", 3, skip = false, back = false),
                Step("Add Phone Number", "Landing", 7, skip = false, back = true),
            ),
            sink.steps,
        )
        assertTrue(sink.onboardingSteps.isEmpty())
    }

    @Test fun `onboarding flows also report the onboarding funnel`() {
        val sink = RecordingAnalytics()
        ContactSyncFlowAnalytics(isOnboarding = true, clock = MutableClock(0L), analytics = sink)
            .trackEnd(skip = true)
        assertEquals(listOf("Landing" to "Complete"), sink.onboardingSteps)
    }

    // endregion

    // region helpers

    private data class Step(
        val from: String,
        val to: String,
        val durationSeconds: Int,
        val skip: Boolean,
        val back: Boolean,
    )

    private class MutableClock(var now: Long) : Clock {
        override fun currentTimeMillis(): Long = now
    }

    private class RecordingAnalytics : ContactSyncAnalytics by ContactSyncAnalytics.None {
        val steps = mutableListOf<Step>()
        val onboardingSteps = mutableListOf<Pair<String, String>>()

        override fun relationshipSyncFlow(
            flow: String,
            fromStep: String,
            toStep: String,
            durationSeconds: Int,
            skip: Boolean,
            back: Boolean,
            additionalProperties: Map<String, Any>?,
        ) {
            steps += Step(fromStep, toStep, durationSeconds, skip, back)
        }

        override fun newUserOnboarding(
            flow: String,
            fromStep: String,
            toStep: String,
            stepStartedAtMs: Long?,
            skip: Boolean,
        ) {
            onboardingSteps += fromStep to toStep
        }
    }

    private fun viewModel(
        mode: ContactSyncMode,
        captchaLauncher: (RestApiError) -> Unit = {},
    ) = WidgetContactSyncViewModel(
        mode = mode,
        restAPI = stubRestApi(),
        // The store feed is exercised separately; the flow tests drive the view model directly.
        storeObservable = Observable.never(),
        tracker = ContactSyncFlowAnalytics(
            isOnboarding = mode == ContactSyncMode.ONBOARDING,
            clock = MutableClock(0L),
            analytics = ContactSyncAnalytics.None,
        ),
        captchaLauncher = captchaLauncher,
    )

    private fun WidgetContactSyncViewModel.handlePhoneSubmittedErrorForTest(error: RestApiError) {
        javaClass.getDeclaredMethod("handlePhoneSubmittedError", RestApiError::class.java).apply {
            isAccessible = true
        }.invoke(this, error)
    }

    private fun httpError(json: String): RestApiError = RestApiError.from(
        HttpException(Response.error<Any>(400, json.toResponseBody("application/json".toMediaType()))),
    )

    /** Every REST method answers with a stream that never emits, so no call has a side effect. */
    private fun stubRestApi(): RestAPIInterface = Proxy.newProxyInstance(
        RestAPIInterface::class.java.classLoader,
        arrayOf(RestAPIInterface::class.java),
    ) { _, method, _ ->
        if (Observable::class.java.isAssignableFrom(method.returnType)) Observable.never<Any>() else null
    } as RestAPIInterface

    private fun viewState(phoneNumber: String?, name: String?) = ViewState(
        landingNextEnabled = true,
        mode = ContactSyncMode.DEFAULT,
        phoneNumber = phoneNumber,
        existingConnection = null,
        countryCode = PhoneCountryCode.DEFAULT_COUNTRY_CODE,
        username = "ada#0001",
        name = name,
        isSubmitting = false,
        permissionsDenied = false,
        displayedChild = Views.VIEW_LANDING,
        allowPhone = true,
        allowEmail = true,
        bulkAddToken = "token",
        friendSuggestions = emptyList(),
        selectedFriendIds = emptyList(),
        toolbarConfig = WidgetContactSyncViewModel.ToolbarConfig(true, false),
    )

    private fun suggestion(userId: Long, contactName: String) = FriendSuggestion(
        suggestedUser = User(id = userId, username = "friend"),
        reasons = listOf(
            FriendSuggestionReason(FriendSuggestionReasonType.EXTERNAL_FRIEND, "contacts", contactName),
        ),
    )

    // endregion
}

package com.discord.widgets.servers.creator_monetization_eligibility.onboarding

import com.discord.api.creatormonetization.CreatorMonetizationEligibilityRequirements
import com.discord.api.creatormonetization.CreatorMonetizationEnableRequest
import com.discord.models.guild.Guild
import com.discord.restapi.RestAPIInterface
import java.io.IOException
import java.lang.reflect.Proxy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import rx.Observable
import rx.android.plugins.RxAndroidPlugins
import rx.android.plugins.RxAndroidSchedulersHook
import rx.observers.TestSubscriber
import rx.plugins.RxJavaHooks
import rx.schedulers.Schedulers
import rx.subjects.PublishSubject

class ServerSettingsCreatorMonetizationOnboardingViewModelTest {
    private val requirements = PublishSubject.create<CreatorMonetizationEligibilityRequirements>()
    private val createRequest = PublishSubject.create<CreatorMonetizationEnableRequest>()
    private val acceptTerms = PublishSubject.create<CreatorMonetizationEnableRequest>()
    private val events = TestSubscriber<ServerSettingsCreatorMonetizationOnboardingViewModel.Event>()
    private var createdForGuild: Long? = null
    private var acceptedFor: Pair<Long, Long>? = null

    @Before fun setUpSchedulers() {
        RxJavaHooks.setOnIOScheduler { Schedulers.immediate() }
        RxJavaHooks.setOnComputationScheduler { Schedulers.immediate() }
        RxAndroidPlugins.getInstance().reset()
        RxAndroidPlugins.getInstance().registerSchedulersHook(
            object : RxAndroidSchedulersHook() {
                override fun getMainThreadScheduler() = Schedulers.immediate()
            },
        )
    }

    @After fun resetSchedulers() {
        RxJavaHooks.reset()
        RxAndroidPlugins.getInstance().reset()
    }

    @Test fun `requirements and application responses drive every onboarding state`() {
        val viewModel = viewModel()
        viewModel.listenForEvents().subscribe(events)
        assertEquals(ServerSettingsCreatorMonetizationOnboardingViewModel.ViewState.Loading, viewModel.viewState)

        requirements.onNext(requirements())
        val eligible = viewModel.viewState as ServerSettingsCreatorMonetizationOnboardingViewModel.ViewState.CreateRequest
        assertTrue(eligible.isGuildOwner)
        assertFalse(eligible.isApplicationPending)

        viewModel.createRequestToEnableMonetization()
        assertEquals(GUILD_ID, createdForGuild)
        assertTrue(
            (viewModel.viewState as ServerSettingsCreatorMonetizationOnboardingViewModel.ViewState.CreateRequest)
                .isSubmitting,
        )
        createRequest.onNext(CreatorMonetizationEnableRequest(REQUEST_ID, CreatorMonetizationEnableRequest.State.OPEN))
        val pending = viewModel.viewState as ServerSettingsCreatorMonetizationOnboardingViewModel.ViewState.CreateRequest
        assertTrue(pending.isApplicationPending)
        assertFalse(pending.isSubmitting)

        requirements.onNext(
            requirements(
                CreatorMonetizationEnableRequest(REQUEST_ID, CreatorMonetizationEnableRequest.State.APPROVED),
            ),
        )
        val approved = viewModel.viewState as ServerSettingsCreatorMonetizationOnboardingViewModel.ViewState.AcceptTerms
        assertEquals(REQUEST_ID, approved.latestRequest.id)

        viewModel.setHasAcceptedTerms(true)
        viewModel.submitTermsAcceptance()
        assertEquals(GUILD_ID to REQUEST_ID, acceptedFor)
        assertTrue(
            (viewModel.viewState as ServerSettingsCreatorMonetizationOnboardingViewModel.ViewState.AcceptTerms)
                .isSubmitting,
        )
    }

    @Test fun `mutation failure restores controls and emits the raw failure`() {
        val viewModel = viewModel()
        viewModel.listenForEvents().subscribe(events)
        requirements.onNext(
            requirements(
                CreatorMonetizationEnableRequest(REQUEST_ID, CreatorMonetizationEnableRequest.State.APPROVED),
            ),
        )
        viewModel.setHasAcceptedTerms(true)
        viewModel.submitTermsAcceptance()

        val failure = IOException("offline")
        acceptTerms.onError(failure)

        val state = viewModel.viewState as ServerSettingsCreatorMonetizationOnboardingViewModel.ViewState.AcceptTerms
        assertFalse(state.hasAcceptedTerms)
        assertFalse(state.isSubmitting)
        events.assertValue(ServerSettingsCreatorMonetizationOnboardingViewModel.Event.ShowErrorToast(failure))
    }

    private fun viewModel() = ServerSettingsCreatorMonetizationOnboardingViewModel(
        guildId = GUILD_ID,
        restApi = restApi(),
        storeStateObservable = Observable.just(
            ServerSettingsCreatorMonetizationOnboardingViewModel.StoreState(
                Guild(id = GUILD_ID, name = "Guild", ownerId = OWNER_ID),
                isGuildOwner = true,
            ),
        ),
    )

    private fun restApi(): RestAPIInterface = Proxy.newProxyInstance(
        RestAPIInterface::class.java.classLoader,
        arrayOf(RestAPIInterface::class.java),
    ) { _, method, args ->
        when (method.name) {
            "getCreatorMonetizationEligibilityRequirements" -> requirements
            "createCreatorMonetizationEnableRequest" -> {
                createdForGuild = args?.single() as Long
                createRequest
            }
            "acceptCreatorMonetizationTerms" -> {
                acceptedFor = args?.let { it[0] as Long to it[1] as Long }
                acceptTerms
            }
            else -> if (Observable::class.java.isAssignableFrom(method.returnType)) {
                Observable.never<Any>()
            } else {
                null
            }
        }
    } as RestAPIInterface

    private fun requirements(
        request: CreatorMonetizationEnableRequest? = null,
    ) = CreatorMonetizationEligibilityRequirements(
        guildId = GUILD_ID,
        latestRequest = request,
        canApply = true,
        sufficient = true,
        age = true,
        engagementHealthy = true,
        nsfwProperties = emptyMap(),
        retentionHealthy = true,
        safeEnvironment = true,
        size = true,
        healthScore = null,
        healthScorePending = false,
        rejection = null,
        minimumAgeInDays = 30,
        minimumSize = 100,
    )

    private companion object {
        const val GUILD_ID = 42L
        const val OWNER_ID = 7L
        const val REQUEST_ID = 99L
    }
}

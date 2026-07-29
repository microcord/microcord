package com.discord.widgets.auth

import com.discord.models.domain.ModelGuildTemplate
import com.discord.models.domain.ModelInvite
import com.discord.stores.StoreGuildTemplates
import com.discord.stores.StoreInviteSettings
import java.util.Calendar
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import rx.android.plugins.RxAndroidPlugins
import rx.android.plugins.RxAndroidSchedulersHook
import rx.plugins.RxJavaHooks
import rx.schedulers.Schedulers
import rx.subjects.BehaviorSubject
import com.discord.api.user.NsfwAllowance

class AuthLandingAgeViewModelsTest {
    @Before fun setUpSchedulers() {
        RxJavaHooks.setOnIOScheduler { Schedulers.immediate() }
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

    @Test fun `landing fetches changed invite codes once and prefers a resolved invite`() {
        val first = StoreInviteSettings.InviteCode("first", "Deeplink", null)
        val second = StoreInviteSettings.InviteCode("second", "Deeplink", 7L)
        val states = BehaviorSubject.create(
            WidgetAuthLandingViewModel.StoreState(
                first,
                null,
                StoreGuildTemplates.GuildTemplateState.None,
                null,
            ),
        )
        val fetched = mutableListOf<StoreInviteSettings.InviteCode>()
        val model = WidgetAuthLandingViewModel(states, { fetched.add(it) })

        states.onNext(states.value)
        states.onNext(states.value.copy(inviteCode = second))
        val invite = ModelInvite.createForTesting(null)
        states.onNext(states.value.copy(invite = invite, ageGateError = "blocked"))

        assertEquals(listOf(first, second), fetched)
        assertEquals(WidgetAuthLandingViewModel.ViewState.Invite(invite, "blocked"), model.viewState)
    }

    @Test fun `landing renders a resolved template when no invite exists`() {
        val template = ModelGuildTemplate(1L, null, 2L, null, "created", "updated", 3, "code", "Name", "Description")
        val model = WidgetAuthLandingViewModel(
            BehaviorSubject.create(
                WidgetAuthLandingViewModel.StoreState(
                    null,
                    null,
                    StoreGuildTemplates.GuildTemplateState.Resolved(template),
                    null,
                ),
            ),
            {},
        )

        assertEquals(WidgetAuthLandingViewModel.ViewState.GuildTemplate(template, null), model.viewState)
    }

    @Test fun `nsfw underage submission confirms before sending and handles rejection`() {
        val stores = BehaviorSubject.create(
            WidgetAgeVerifyViewModel.StoreState(null, NsfwAllowance.UNKNOWN, true),
        )
        var submittedBirthday: Long? = null
        var callback: ((AuthResult) -> Unit)? = null
        val model = WidgetAgeVerifyViewModel(stores, { birthday, result ->
            submittedBirthday = birthday
            callback = result
        })
        val birthday = yearsAgo(16)

        model.setDateOfBirth(birthday)
        model.submit(isNsfwChannel = true)
        assertEquals(WidgetAgeVerifyViewModel.Views.VIEW_AGE_CONFIRM, model.requireViewState().displayedChild)
        assertEquals(null, submittedBirthday)

        model.submit(isNsfwChannel = true)
        assertEquals(birthday, submittedBirthday)
        callback?.invoke(
            AuthResult.Challenge(
                AuthRoute.AGE_GATED,
                AuthUiState(route = AuthRoute.AGE_GATED, underageMessage = "too young"),
            ),
        )
        assertEquals(WidgetAgeVerifyViewModel.Views.VIEW_AGE_GATED, model.requireViewState().displayedChild)
        assertEquals("too young", model.requireViewState().underageMessage)
    }

    @Test fun `successful public submission emits verified and store state controls closing`() {
        val stores = BehaviorSubject.create(
            WidgetAgeVerifyViewModel.StoreState(null, NsfwAllowance.UNKNOWN, true),
        )
        var callback: ((AuthResult) -> Unit)? = null
        val model = WidgetAgeVerifyViewModel(stores, { _, result -> callback = result })
        var verified = false
        model.observeEvents().subscribe { verified = it === WidgetAgeVerifyViewModel.Event.Verified }

        model.setDateOfBirth(yearsAgo(20))
        model.submit(isNsfwChannel = false)
        callback?.invoke(AuthResult.Success())
        assertTrue(verified)
        assertFalse(model.requireViewState().isSubmitting)
        assertFalse(model.requireViewState().shouldClose)

        stores.onNext(WidgetAgeVerifyViewModel.StoreState(null, NsfwAllowance.ALLOWED, true))
        assertTrue(model.requireViewState().shouldClose)
    }

    private fun yearsAgo(years: Int): Long = Calendar.getInstance().apply {
        add(Calendar.YEAR, -years)
        add(Calendar.DAY_OF_MONTH, -1)
    }.timeInMillis
}

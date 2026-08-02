package com.discord.stores

import com.discord.api.friendsuggestions.FriendSuggestion as ApiFriendSuggestion
import com.discord.api.friendsuggestions.FriendSuggestionDelete
import com.discord.api.friendsuggestions.FriendSuggestionReason
import com.discord.api.friendsuggestions.FriendSuggestionReasonType
import com.discord.api.user.User
import com.discord.api.user.UserProfile
import com.discord.models.domain.ModelPayload
import com.discord.models.domain.ModelUserAffinities
import com.discord.models.domain.ModelUserAffinity
import com.discord.models.domain.ModelUserNote
import com.discord.stores.updates.ObservationDeck
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import rx.schedulers.TestScheduler

class UserProfileNotesSuggestionsAffinitiesTest {
    @Test
    fun profileDeduplicatesRetriesPublishesImmutableSnapshotsAndClears() {
        val harness = Harness()
        val requests = mutableListOf<Pair<(UserProfile) -> Unit, () -> Unit>>()
        val store = StoreUserProfile(harness.dispatcher, harness.deck, { _, _, _, success, failure ->
            requests += success to failure
        })
        harness.register(store)

        store.fetchProfile(1)
        store.fetchProfile(1)
        harness.run()
        assertEquals(1, requests.size)
        assertEquals(setOf(1L), store.getProfilesLoading())

        requests.single().second()
        harness.run()
        store.fetchProfile(1)
        harness.run()
        assertEquals(2, requests.size)
        requests.last().first(UserProfile(user = User(1, "one")))
        harness.run()

        val old = store.getUserProfiles()
        store.updateUser(User(1, "changed"))
        harness.run()
        assertEquals("one", old[1]?.user?.username)
        assertEquals("changed", store.getUserProfile(1).user.username)
        harness.dispatcher.schedule { store.handlePreLogout() }
        harness.run()
        assertEquals(StoreUserProfile.EMPTY_PROFILE, store.getUserProfile(1))
    }

    @Test
    fun profileEffectMetadataIsFetchedOnceCachedAndRebound() {
        val harness = Harness()
        lateinit var profileLoaded: (UserProfile) -> Unit
        val effectRequests = mutableListOf<
            Triple<Long, (UserProfile.ProfileEffectProduct) -> Unit, () -> Unit>,
        >()
        val store = StoreUserProfile(
            harness.dispatcher,
            harness.deck,
            fetchProfile = { _, _, _, success, _ -> profileLoaded = success },
            fetchProfileEffect = { skuId, success, failure ->
                effectRequests += Triple(skuId, success, failure)
            },
        )
        harness.register(store)
        val observed = mutableListOf<UserProfile>()
        store.observeUserProfile(1).subscribe(observed::add)

        store.fetchProfile(1)
        harness.run()
        profileLoaded(
            UserProfile(
                user = User(1, "one"),
                userProfile = UserProfile.ProfileMetadata(profileEffect = UserProfile.ProfileEffect(7)),
                guildMemberProfile = UserProfile.ProfileMetadata(profileEffect = UserProfile.ProfileEffect(7)),
            ),
        )
        harness.run()

        assertEquals(1, effectRequests.size)
        assertEquals(7L, effectRequests.single().first)
        val unresolved = store.getUserProfile(1)
        assertEquals(null, unresolved.userProfile?.profileEffect?.product)

        val product = GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .create()
            .fromJson(
                """{
                    "sku_id":"7","name":"fixture","type":121,
                    "items":[{
                        "sku_id":"7","type":1,"animationType":2,
                        "staticFrameSrc":"https://cdn.example/static.png",
                        "reducedMotionSrc":"https://cdn.example/reduced.png",
                        "effects":[{
                            "src":"https://cdn.example/layer.png","loop":true,
                            "duration":5000,"start":13000,"loopDelay":5000,"zIndex":2,
                            "randomizedSources":[{"src":"https://cdn.example/random.png"}]
                        }]
                    }]
                }""".trimIndent(),
                UserProfile.ProfileEffectProduct::class.java,
            )
        effectRequests.single().second(product)
        harness.run()

        assertEquals(product, store.getUserProfile(1).userProfile?.profileEffect?.product)
        assertEquals(product, store.getUserProfile(1).guildMemberProfile?.profileEffect?.product)
        assertEquals(
            "https://cdn.example/layer.png",
            observed.last().userProfile?.profileEffect?.product?.items?.single()?.effects?.single()?.src,
        )
        assertEquals(null, unresolved.userProfile?.profileEffect?.product)

        store.fetchProfile(2)
        harness.run()
        profileLoaded(
            UserProfile(
                user = User(2, "two"),
                userProfile = UserProfile.ProfileMetadata(profileEffect = UserProfile.ProfileEffect(7)),
            ),
        )
        harness.run()
        assertEquals(1, effectRequests.size)
        assertEquals(product, store.getUserProfile(2).userProfile?.profileEffect?.product)

        store.fetchProfile(3)
        harness.run()
        profileLoaded(
            UserProfile(
                user = User(3, "three"),
                userProfile = UserProfile.ProfileMetadata(profileEffect = UserProfile.ProfileEffect(8)),
            ),
        )
        harness.run()
        effectRequests.last().third()
        harness.run()
        store.fetchProfile(3)
        harness.run()
        profileLoaded(
            UserProfile(
                user = User(3, "three"),
                userProfile = UserProfile.ProfileMetadata(profileEffect = UserProfile.ProfileEffect(8)),
            ),
        )
        harness.run()
        assertEquals(3, effectRequests.size)
        assertEquals(null, store.getUserProfile(3).userProfile?.profileEffect?.product)
    }

    @Test
    fun notesLoadOnceTrackGatewayAndSaveThenClearOnReadyAndLogout() {
        val harness = Harness()
        var loads = 0
        lateinit var loaded: (ModelUserNote) -> Unit
        val store = StoreUserNotes(
            harness.dispatcher,
            harness.deck,
            { _, success, _ -> loads++; loaded = success },
            { _, _, success, _ -> success() },
        )
        harness.register(store)

        store.loadNote(2)
        store.loadNote(2)
        harness.run()
        assertEquals(1, loads)
        assertEquals(StoreUserNotes.UserNoteState.Loading, store.getNotesByUserId()[2])
        loaded(ModelUserNote(2, "note"))
        harness.run()
        assertTrue(store.getNotesByUserId()[2] is StoreUserNotes.UserNoteState.Loaded)
        harness.dispatcher.schedule { store.handleNoteUpdate(ModelUserNote.Update(2, "")) }
        harness.run()
        assertEquals(StoreUserNotes.UserNoteState.Empty, store.getNotesByUserId()[2])
        store.saveNote(2, "saved")
        harness.run()
        assertEquals("saved", (store.getNotesByUserId()[2] as StoreUserNotes.UserNoteState.Loaded).note.note)
        harness.dispatcher.schedule { store.handleConnectionOpen() }
        harness.run()
        assertTrue(store.getNotesByUserId().isEmpty())
        harness.dispatcher.schedule { store.handlePreLogout() }
        harness.run()
        assertTrue(store.getNotesByUserId().isEmpty())
    }

    @Test
    fun suggestionsFetchReplaceHydrateMutateFailAndClear() {
        val harness = Harness()
        var fetches = 0
        lateinit var success: (List<ApiFriendSuggestion>) -> Unit
        lateinit var failure: () -> Unit
        val hydrated = mutableListOf<List<User>>()
        val store = StoreFriendSuggestions(harness.dispatcher, harness.deck, { loaded, failed ->
            fetches++; success = loaded; failure = failed
        }, hydrated::add)
        harness.register(store)

        val ready = ModelPayload().apply { friendSuggestionCount = 1 }
        harness.dispatcher.schedule { store.handleConnectionOpen(ready) }
        harness.run()
        assertEquals(1, fetches)
        assertTrue(store.isLoading())
        success(listOf(suggestion(3, "three")))
        harness.run()
        val old = store.getFriendSuggestions()
        harness.dispatcher.schedule {
            store.handleFriendSuggestionCreate(suggestion(4, "four"))
            store.handleFriendSuggestionDelete(FriendSuggestionDelete(3))
        }
        harness.run()
        assertEquals(setOf(3L), old.keys)
        assertEquals(setOf(4L), store.getFriendSuggestions().keys)
        assertEquals(listOf(3L, 4L), hydrated.flatten().map(User::id))

        harness.dispatcher.schedule { store.handleConnectionOpen(ready) }
        harness.run()
        failure()
        harness.run()
        assertFalse(store.isLoading())
        assertTrue(store.getFriendSuggestions().isEmpty())
        harness.dispatcher.schedule { store.handlePreLogout() }
        harness.run()
        assertTrue(store.getFriendSuggestions().isEmpty())
    }

    @Test
    fun affinitiesPublishBothListsAndClearWithoutMutatingOldSnapshot() {
        val harness = Harness()
        lateinit var success: (ModelUserAffinities) -> Unit
        val store = StoreUserAffinities(harness.dispatcher, harness.deck, { loaded, _ -> success = loaded })
        harness.register(store)
        val observed = mutableListOf<ModelUserAffinities>()
        store.observeUserAffinities().subscribe(observed::add)

        harness.dispatcher.schedule { store.handleConnectionOpen() }
        harness.run()
        success(ModelUserAffinities(listOf(ModelUserAffinity(5, .8f)), listOf(ModelUserAffinity(6, .4f))))
        harness.run()
        val old = store.getUserAffinities()
        assertEquals(listOf(5L), old.userAffinities.map { it.userId })
        assertEquals(listOf(6L), old.inverseUserAffinities.map { it.userId })
        harness.dispatcher.schedule { store.handlePreLogout() }
        harness.run()
        assertEquals(listOf(5L), old.userAffinities.map { it.userId })
        assertEquals(ModelUserAffinities(emptyList(), emptyList()), store.getUserAffinities())
        assertEquals(3, observed.size)
    }

    private fun suggestion(id: Long, name: String) = ApiFriendSuggestion(
        User(id, name),
        listOf(FriendSuggestionReason(FriendSuggestionReasonType.EXTERNAL_FRIEND, "contacts", name)),
    )

    private class Harness {
        val scheduler = TestScheduler()
        val dispatcher = Dispatcher(scheduler)
        val deck = ObservationDeck()

        fun register(vararg stores: StoreV2) {
            dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(stores.toList(), deck))
        }

        fun run() = scheduler.triggerActions()
    }
}

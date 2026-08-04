package com.discord.widgets.friends

import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.app.AppFragment
import com.discord.i18n.FormatUtils
import com.discord.models.presence.Presence
import com.discord.models.user.User
import com.discord.restapi.RestAPIParams
import com.discord.rlottie.RLottieImageView
import com.discord.stores.StoreStream
import com.discord.utilities.analytics.AnalyticsTracker
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.dimen.dpToPixels
import com.discord.utilities.error.RestApiError
import com.discord.utilities.mg_recycler.MGRecyclerAdapter
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rest.RestAPIAbortMessages
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.uri.UriHandler
import com.discord.utilities.user.UserUtils
import com.discord.widgets.user.usersheet.WidgetUserSheet
import com.discord.models.domain.ModelUserRelationship
import com.google.android.material.button.MaterialButton
import rx.Observable

/**
 * "Nearby scan" — the second tab of [WidgetFriendsAdd].
 *
 * Four states, driven by [NearbyManager.state] crossed with the user stores:
 * uninitialised (scan not started, "Enable" button), empty (scanning, animation running),
 * nearby users (results list), error (scan refused or failed, "Enable" acts as retry).
 *
 * ### Privacy
 * The scan starts in `onResume` and is torn down in `onPause`, so it never runs behind another
 * screen. Discovered users are rendered but never logged, and a friend request is only ever sent
 * from an explicit tap on a row — there is no bulk action here.
 */
class WidgetFriendsFindNearby : AppFragment(R.layout.widget_friends_nearby) {

    private val nearbyManager = NearbyManager()

    private lateinit var resultsAdapter: WidgetFriendsAddUserAdapter
    private lateinit var searchingTitle: TextView
    private lateinit var searchingBody: TextView
    private lateinit var searchingLottie: RLottieImageView
    private lateinit var resultsRecycler: RecyclerView
    private lateinit var enableButton: MaterialButton

    private var meUserId: Long? = null

    /** What the screen renders. */
    sealed class Model {
        /** The scan has not been started (or was stopped). */
        object Uninitialized : Model()

        /** Scanning, nobody found yet. */
        object Empty : Model()

        /** @param errorCode one of [NearbyManager.CONNECTION_ERROR] / [NearbyManager.PERMISSION_DENIED]. */
        data class Error(val errorCode: Int?) : Model()

        data class NearbyUsers(val items: List<WidgetFriendsAddUserAdapter.ItemUser>) : Model()
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        searchingTitle = view.findViewById(R.id.nearby_friends_searching_title)
        searchingBody = view.findViewById(R.id.nearby_friends_searching_body)
        searchingLottie = view.findViewById(R.id.nearby_friends_searching_lottie)
        resultsRecycler = view.findViewById(R.id.nearby_friends_recycler)
        enableButton = view.findViewById(R.id.nearby_friends_enable)

        updateMeUserIdAndInitNearbyManager()

        resultsAdapter = MGRecyclerAdapter.configure(WidgetFriendsAddUserAdapter(resultsRecycler))
        resultsAdapter.setSendHandler { username, discriminator ->
            sendFriendRequest(username, discriminator)
        }
        resultsAdapter.setAcceptHandler { userId -> acceptFriendRequest(userId) }
        resultsAdapter.setDeclineHandler { userId, incomingFriendRequest ->
            declineFriendRequest(userId, incomingFriendRequest)
        }
        resultsAdapter.setOnItemClick { userId ->
            WidgetUserSheet.show(parentFragmentManager, userId)
        }

        enableButton.setOnClickListener { enableScanning() }
        view.findViewById<View>(R.id.nearby_friends_learn_more).setOnClickListener {
            UriHandler.handle(it.context, NEARBY_HELP_URL)
        }
        searchingLottie.setAnimation(
            R.raw.anim_friends_add_nearby_looking,
            dpToPixels(LOTTIE_SIZE_DP),
            dpToPixels(LOTTIE_SIZE_DP),
        )
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()

        // Pending requests come from a different store than the rows, so they are pushed into the
        // adapter separately; without them a row would offer "send" to somebody already asked.
        Observable.combineLatest(
            StoreStream.getUserRelationships()
                .observeForType(ModelUserRelationship.TYPE_INVITE_OUTGOING).map { it.keys },
            StoreStream.getUserRelationships()
                .observeForType(ModelUserRelationship.TYPE_INVITE_INCOMING).map { it.keys },
        ) { outgoing, incoming -> outgoing to incoming }
            .computationLatest()
            .distinctUntilChanged()
            .ui(this)
            .appSubscribe(WidgetFriendsFindNearby::class.java) { (outgoing, incoming) ->
                resultsAdapter.addFriendRequestUserIds(outgoing, incoming)
            }

        ModelProvider.get(nearbyManager.state)
            .ui(this)
            .appSubscribe(WidgetFriendsFindNearby::class.java) { configureUI(it) }
    }

    override fun onResume() {
        super.onResume()
        enableScanning()
        AnalyticsTracker.friendAddViewed("Nearby")
    }

    override fun onPause() {
        nearbyManager.disableNearby()
        super.onPause()
    }

    // region rendering

    private fun configureUI(model: Model) {
        when (model) {
            is Model.Uninitialized -> {
                setCopy(R.string.add_friend_nearby_title, getString(R.string.add_friend_nearby_body))
                searchingBody.setTextColor(
                    ColorCompat.getThemedColor(searchingBody, R.attr.colorHeaderSecondary),
                )
                enableButton.visibility = View.VISIBLE
                resultsRecycler.visibility = View.GONE
                searchingLottie.stopAnimation()
            }
            is Model.Error -> {
                setCopy(R.string.add_friend_nearby_title, getErrorMessage(model.errorCode))
                searchingBody.setTextColor(
                    ColorCompat.getColor(searchingBody.context, R.color.status_red),
                )
                enableButton.visibility = View.VISIBLE
                resultsRecycler.visibility = View.GONE
                searchingLottie.stopAnimation()
            }
            is Model.Empty -> {
                setCopy(R.string.add_friend_nearby_title, getString(R.string.add_friend_nearby_body))
                searchingBody.setTextColor(
                    ColorCompat.getThemedColor(searchingBody, R.attr.colorHeaderSecondary),
                )
                enableButton.visibility = View.GONE
                resultsRecycler.visibility = View.GONE
                if (!StoreStream.getAccessibility().isReducedMotionEnabled()) {
                    searchingLottie.playAnimation()
                }
            }
            is Model.NearbyUsers -> {
                setCopy(
                    R.string.add_friend_nearby_found_title,
                    getString(R.string.add_friend_nearby_found_body),
                )
                searchingBody.setTextColor(
                    ColorCompat.getThemedColor(searchingBody, R.attr.colorHeaderSecondary),
                )
                enableButton.visibility = View.GONE
                resultsAdapter.setData(model.items)
                resultsRecycler.visibility = View.VISIBLE
            }
        }
        enableButton.setText(
            if (model is Model.Error) R.string.retry else R.string.add_friend_nearby_enable,
        )
    }

    private fun setCopy(titleRes: Int, body: CharSequence?) {
        searchingTitle.text = getString(titleRes)
        searchingBody.text = body
    }

    private fun getErrorMessage(resultCode: Int?): String? = context?.getString(
        when (resultCode) {
            NearbyManager.CONNECTION_ERROR -> R.string.add_friend_nearby_connection_error
            NearbyManager.PERMISSION_DENIED -> R.string.add_friend_nearby_stopped
            else -> R.string.add_friend_nearby_generic_error
        },
    )

    // endregion

    // region scanning

    private fun updateMeUserIdAndInitNearbyManager() {
        val userId = StoreStream.getUsers().getMe().id
        meUserId = userId
        nearbyManager.initialize(userId)
    }

    private fun enableScanning() {
        if (meUserId == null) updateMeUserIdAndInitNearbyManager()
        if (meUserId == null) return
        nearbyManager.buildClientAndPublish(requireActivity())
    }

    // endregion

    // region relationship actions

    private fun sendFriendRequest(username: String, discriminator: Int) {
        val api = RestAPI.apiOrNull ?: return
        api.sendRelationshipRequest(
            RestAPIParams.UserRelationship.Add(
                username = username,
                discriminator = discriminator,
            ),
            relationshipContext(LOCATION_ADD_SUGGESTION),
        )
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(
                errorClass = WidgetFriendsFindNearby::class.java,
                errorHandler = { error ->
                    RestAPIAbortMessages.handleAbortCodeOrDefault(error, onHandle = {
                        showToast(
                            RestAPIAbortMessages.ResponseResolver.getRelationshipResponse(
                                context,
                                error.code,
                                username + UserUtils.discriminatorSuffix(discriminator),
                            ),
                        )
                    })
                },
            ) {
                context?.let {
                    showToast(
                        FormatUtils.format(
                            it.getString(R.string.add_friend_confirmation),
                            "name" to username,
                        ),
                    )
                }
            }
    }

    private fun acceptFriendRequest(userId: Long) {
        val api = RestAPI.apiOrNull ?: return
        api.addRelationship(
            userId,
            RestAPIParams.UserRelationship(),
            relationshipContext(LOCATION_ACCEPT),
        )
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(WidgetFriendsFindNearby::class.java) {
                showToast(getString(R.string.accept_request_button_after))
            }
    }

    private fun declineFriendRequest(userId: Long, incomingFriendRequest: Boolean) {
        val api = RestAPI.apiOrNull ?: return
        val successMessageStringRes = if (incomingFriendRequest) {
            R.string.friend_request_ignored
        } else {
            R.string.friend_request_cancelled
        }
        api.removeRelationship(userId, relationshipContext(LOCATION_REMOVE))
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(WidgetFriendsFindNearby::class.java) {
                showToast(getString(successMessageStringRes))
            }
    }

    private fun showToast(text: CharSequence?) {
        val context = context ?: return
        if (text.isNullOrBlank()) return
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }

    // endregion

    /**
     * Turns the raw nearby-id set into rows by joining it with the user, presence and relationship
     * stores. Kept separate from the fragment so the join is testable and so the fragment never
     * touches a store directly.
     */
    internal object ModelProvider {

        fun get(nearbyStateObservable: Observable<NearbyManager.NearbyState>): Observable<Model> =
            nearbyStateObservable.switchMap { nearbyState ->
                when (nearbyState) {
                    is NearbyManager.NearbyState.Uninitialized ->
                        Observable.just<Model>(Model.Uninitialized)
                    is NearbyManager.NearbyState.Disconnected ->
                        Observable.just<Model>(Model.Error(nearbyState.code))
                    is NearbyManager.NearbyState.Connected ->
                        if (nearbyState.nearbyUserIds.isEmpty()) {
                            Observable.just<Model>(Model.Empty)
                        } else {
                            getUserModels(nearbyState.nearbyUserIds)
                        }
                }
            }

        private fun getUserModels(userIds: Collection<Long>): Observable<Model> =
            Observable.combineLatest<Map<Long, User>, Map<Long, Presence>, Map<Long, Int>, Model>(
                StoreStream.getUsers().observeUsers(userIds, true),
                StoreStream.getPresences().observePresencesForUsers(userIds),
                StoreStream.getUserRelationships().observe(userIds),
            ) { users, presences, relationships ->
                Model.NearbyUsers(
                    userIds.mapNotNull { userId ->
                        val user = users[userId] ?: return@mapNotNull null
                        WidgetFriendsAddUserAdapter.ItemUser(
                            user = user,
                            aliases = emptyList(),
                            presence = presences[userId],
                            isFriend = ModelUserRelationship.getType(relationships[userId]) ==
                                ModelUserRelationship.TYPE_FRIEND,
                        )
                    },
                )
            }
    }

    companion object {
        private const val LOTTIE_SIZE_DP = 200
        private const val LOCATION_ADD_SUGGESTION = "Nearby - Add Friend Suggestion"
        private const val LOCATION_ACCEPT = "Nearby - Accept Friend Request"
        private const val LOCATION_REMOVE = "Nearby - Remove Friend Request"

        /** Zendesk article 360014894392, the reference's "Nearby Friends" help page. */
        private const val NEARBY_HELP_URL =
            "https://support.discord.com/hc/en-us/articles/360014894392"

        /** `X-Context-Properties` is a base64 JSON blob naming where the action was taken. */
        private fun relationshipContext(location: String): String =
            android.util.Base64.encodeToString(
                "{\"location\":\"$location\"}".toByteArray(),
                android.util.Base64.NO_WRAP,
            )
    }
}

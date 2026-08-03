package com.discord.widgets.friends

import android.content.Intent
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.TextView
import android.widget.Toast
import com.discord.R
import com.discord.app.AppFragment
import com.discord.i18n.FormatUtils
import com.discord.models.user.User
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreStream
import com.discord.utilities.analytics.AnalyticsTracker
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.error.RestApiError
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rest.RestAPIAbortMessages
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.user.UserUtils
import com.discord.utilities.view.extensions.addBindedTextWatcher
import com.discord.utilities.view.extensions.clear
import com.discord.utilities.view.extensions.getTextOrEmpty
import com.discord.utilities.view.extensions.setOnImeActionDone
import com.discord.utilities.view.extensions.setSelectionEnd
import com.discord.utilities.view.extensions.setText
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputLayout

/**
 * "Add by username#tag" — the first tab of [WidgetFriendsAdd].
 *
 * The screen owns its REST call rather than delegating it, because the two failure paths it has to
 * distinguish are both specific to this form:
 *
 * - the typed text has no four-digit tag, which is a *local* validation failure and never reaches
 *   the API;
 * - the API rejects the request, which becomes a specific inline error under the text field.
 */
class WidgetFriendsAddById : AppFragment(R.layout.widget_friends_add_by_id) {

    private lateinit var inputWrap: TextInputLayout
    private lateinit var sendButton: MaterialButton

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        inputWrap = view.findViewById(R.id.friends_add_text_edit_wrap)
        sendButton = view.findViewById(R.id.friends_add_send)

        sendButton.setOnClickListener { extractTargetAndSendFriendRequest() }
        inputWrap.addBindedTextWatcher(this) { editable ->
            if (editable.isEmpty()) setInputEditError(null)
            sendButton.isEnabled = editable.isNotEmpty()
        }
        inputWrap.setOnImeActionDone {
            extractTargetAndSendFriendRequest()
            dismissKeyboard()
        }
        view.findViewById<View>(R.id.friends_add_container).setOnClickListener { dismissKeyboard() }

        setInputText(mostRecentIntent.getStringExtra(Intent.EXTRA_TEXT))

        view.findViewById<TextView>(R.id.friend_add_username_indicator).apply {
            visibility = View.VISIBLE
            text = getUsernameIndicatorText(StoreStream.getUsers().getMe())
        }
    }

    override fun onResume() {
        super.onResume()
        AnalyticsTracker.friendAddViewed("Id")
    }

    override fun onPause() {
        super.onPause()
        dismissKeyboard()
    }

    /**
     * The reference overrides `hideKeyboard` to also drop focus; that member is not open here, so
     * the two steps are paired in one private helper and every call site uses it.
     */
    private fun dismissKeyboard() {
        hideKeyboard()
        if (::inputWrap.isInitialized) inputWrap.clearFocus()
    }

    /**
     * Splits the typed text into name and tag. Without a tag the request is not attempted at all —
     * the API cannot resolve a bare username, so sending it would only produce a confusing 400.
     */
    private fun extractTargetAndSendFriendRequest() {
        val source = inputWrap.getTextOrEmpty()
        val target = extractUsernameAndDiscriminator(source)
        val discriminator = target.discriminator
        if (discriminator != null) {
            sendFriendRequest(target.username, discriminator)
        } else {
            setInputEditError(
                context?.let {
                    FormatUtils.format(
                        it.getString(R.string.add_friend_error_username_only),
                        "username" to target.username,
                    )
                },
            )
            AnalyticsTracker.friendRequestFailed(source, "Invalid Username")
        }
    }

    private fun sendFriendRequest(username: String, discriminator: Int) {
        val api = RestAPI.apiOrNull ?: return
        api.sendRelationshipRequest(
            RestAPIParams.UserRelationship.Add(
                username = username,
                discriminator = discriminator,
            ),
            relationshipContext(LOCATION),
        )
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(
                errorClass = WidgetFriendsAddById::class.java,
                errorHandler = { error -> handleSendFailure(error, username, discriminator) },
            ) {
                context?.let {
                    Toast.makeText(
                        it,
                        FormatUtils.format(
                            it.getString(R.string.add_friend_confirmation),
                            "name" to username,
                        ),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                inputWrap.clear()
                dismissKeyboard()
            }
    }

    private fun handleSendFailure(error: RestApiError, username: String, discriminator: Int) {
        RestAPIAbortMessages.handleAbortCodeOrDefault(error, onHandle = {
            inputWrap.setErrorTextAppearance(R.style.TextAppearance_Error)
            setInputEditError(
                RestAPIAbortMessages.ResponseResolver.getRelationshipResponse(
                    context,
                    error.code,
                    username + UserUtils.padDiscriminator(discriminator),
                ),
            )
        })
    }

    private fun setInputEditError(error: CharSequence?) {
        inputWrap.error = error
        inputWrap.isErrorEnabled = !error.isNullOrBlank()
    }

    private fun setInputText(text: String?) {
        if (text.isNullOrBlank()) return
        inputWrap.setText(text)
        inputWrap.setSelectionEnd()
    }

    /**
     * "Your username is Name#0001", with the tag itself drawn in the primary header colour so the
     * user can see at a glance what a well-formed tag looks like.
     */
    private fun getUsernameIndicatorText(user: User): SpannableStringBuilder {
        val nameWithDiscriminator = with(UserUtils) { user.getUserNameWithDiscriminator() }.toString()
        val text = FormatUtils.format(
            getString(R.string.self_username_indicator),
            "username" to nameWithDiscriminator,
        )
        return SpannableStringBuilder(text).apply {
            val start = (length - nameWithDiscriminator.length).coerceAtLeast(0)
            setSpan(
                ForegroundColorSpan(
                    ColorCompat.getThemedColor(requireContext(), R.attr.colorHeaderPrimary),
                ),
                start,
                length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }

    companion object {
        private const val LOCATION = "Search - Add Friend Search"

        /** Name plus a literal four-digit tag; anything else is a name with no tag. */
        private val PATTERN_USERNAME = Regex("""^(.*)#(\d{4})$""")

        internal fun extractUsernameAndDiscriminator(source: CharSequence): UserNameDiscriminator {
            val match = PATTERN_USERNAME.matchEntire(source)
            return if (match != null && match.groupValues.isNotEmpty()) {
                UserNameDiscriminator(match.groupValues[1], match.groupValues[2].toIntOrNull())
            } else {
                UserNameDiscriminator(source.toString(), null)
            }
        }

        /** `X-Context-Properties` is a base64 JSON blob naming where the action was taken. */
        private fun relationshipContext(location: String): String =
            android.util.Base64.encodeToString(
                "{\"location\":\"$location\"}".toByteArray(),
                android.util.Base64.NO_WRAP,
            )

        data class UserNameDiscriminator(val username: String, val discriminator: Int?)
    }
}

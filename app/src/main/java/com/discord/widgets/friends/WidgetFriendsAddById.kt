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
import com.discord.utilities.rest.contextPropertiesOf
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
 * "Add by username" — the first tab of [WidgetFriendsAdd].
 *
 * Accepts a unique username on its own and a legacy `name#1234` tag, and owns its REST call so the
 * API's abort code can become an inline error under the field rather than a toast.
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

    private fun extractTargetAndSendFriendRequest() {
        val source = inputWrap.getTextOrEmpty().trim()
        if (source.isEmpty()) return
        // A unique username is shown as @name throughout the app, but only that form is a handle.
        val discordTag = if (!source.contains('#') && source.startsWith("@")) source.substring(1) else source

        if (!isValidDiscordTag(discordTag)) {
            setInputEditError(context?.getString(R.string.pomelo_add_friend_error))
            AnalyticsTracker.friendRequestFailed(source, "Invalid Username")
            return
        }
        val target = extractUsernameAndDiscriminator(discordTag)
        sendFriendRequest(target.username, target.discriminator)
    }

    private fun sendFriendRequest(username: String, discriminator: Int?) {
        // `discriminator` must reach the server as an explicit null for a unique username.
        val api = RestAPI.apiSerializeNulls
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
                    Toast.makeText(it, R.string.toast_add_friend, Toast.LENGTH_SHORT).show()
                }
                inputWrap.clear()
                dismissKeyboard()
            }
    }

    private fun handleSendFailure(error: RestApiError, username: String, discriminator: Int?) {
        RestAPIAbortMessages.handleAbortCodeOrDefault(error, onHandle = {
            inputWrap.setErrorTextAppearance(R.style.TextAppearance_Error)
            setInputEditError(
                RestAPIAbortMessages.ResponseResolver.getRelationshipResponse(
                    context,
                    error.code,
                    username + UserUtils.discriminatorSuffix(discriminator ?: 0),
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

        /**
         * Reference `validateDiscordTag`. The `@`/`.`-shaped first alternative is only reachable
         * once the value contains a `#`, so a bare email is still rejected.
         */
        private val UNIQUE_USERNAME = Regex("""^[a-zA-Z0-9_.]+$""")
        private val LEGACY_TAG = Regex("""^(.+?@.+?\..+?|.+?#\d{4})$""")

        internal fun isValidDiscordTag(value: String): Boolean =
            UNIQUE_USERNAME.matches(value) || (value.contains('#') && LEGACY_TAG.matches(value))

        internal fun extractUsernameAndDiscriminator(source: CharSequence): UserNameDiscriminator {
            val match = PATTERN_USERNAME.matchEntire(source)
            return if (match != null && match.groupValues.isNotEmpty()) {
                UserNameDiscriminator(match.groupValues[1], match.groupValues[2].toIntOrNull())
            } else {
                UserNameDiscriminator(source.toString(), null)
            }
        }

        private fun relationshipContext(location: String): String =
            contextPropertiesOf("location" to location)

        data class UserNameDiscriminator(val username: String, val discriminator: Int?)
    }
}

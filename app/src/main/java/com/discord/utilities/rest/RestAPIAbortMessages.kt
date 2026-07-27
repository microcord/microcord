package com.discord.utilities.rest

import android.content.Context
import com.discord.R
import com.discord.i18n.FormatUtils
import com.discord.restapi.RestAPIAbortCodes
import com.discord.utilities.error.RestApiError

/**
 * Turns a Discord `400` abort code into the copy the screen should show, and decides whether the
 * generic error toast fires at all.
 *
 * Reconstruction of the reference `com.discord.utilities.rest.RestAPIAbortMessages`. The reference's
 * error type is `com.discord.utilities.error.Error`; this project's equivalent is
 * [RestApiError] (renamed only because `kotlin.Error` is a default import).
 *
 * The contract that matters is [handleAbortCodeOrDefault]: a bad-request response is *claimed* by
 * the caller — the generic toast is suppressed and the caller renders something specific — while
 * anything else falls through to `onDefault`, whose return value decides whether the shared toast
 * still fires.
 */
object RestAPIAbortMessages {

    /**
     * Abort code → user-facing string. Transcribed from the reference's static initialiser.
     *
     * `RELATIONSHIP_INCOMING_DISABLED` (80000) is listed twice in the reference map literal, first
     * against `bot_requires_email_verification` and then against `add_friend_error_invalid_discord_tag`.
     * `mapOf` keeps the last entry, so 80000 resolves to the discord-tag copy; the earlier pair is
     * dead in the reference and is therefore not reproduced.
     */
    private val MESSAGES: Map<Int, Int> = mapOf(
        RestAPIAbortCodes.INVALID_MESSAGE_SEND_USER to R.string.bot_dm_send_failed,
        RestAPIAbortCodes.RATE_LIMIT_DM_OPEN to R.string.bot_dm_rate_limited,
        RestAPIAbortCodes.SEND_MESSAGE_TEMPORARILY_DISABLED to R.string.bot_dm_send_message_temporarily_disabled,
        RestAPIAbortCodes.RELATIONSHIP_INCOMING_DISABLED to R.string.add_friend_error_invalid_discord_tag,
        RestAPIAbortCodes.TOO_MANY_FRIENDS to R.string.add_friend_error_too_many_friends,
        RestAPIAbortCodes.RELATIONSHIP_INCOMING_BLOCKED to R.string.add_friend_error_other,
        RestAPIAbortCodes.RELATIONSHIP_INVALID_SELF to R.string.add_friend_error_other,
        RestAPIAbortCodes.RELATIONSHIP_INVALID_USER_BOT to R.string.add_friend_error_other,
        RestAPIAbortCodes.RELATIONSHIP_INVALID_DISCORD_TAG to R.string.add_friend_error_other,
        RestAPIAbortCodes.RELATIONSHIP_ALREADY_FRIENDS to R.string.add_friend_error_already_friends,
        RestAPIAbortCodes.TOO_MANY_THREADS to R.string.too_many_threads_message,
        RestAPIAbortCodes.TOO_MANY_ANNOUNCEMENT_THREADS to R.string.too_many_announcement_threads_message,
        RestAPIAbortCodes.SLOWMODE_RATE_LIMITED to R.string.channel_slowmode_desc_short,
    )

    @JvmStatic
    fun getAbortCodeMessageResId(abortCode: Int): Int? = MESSAGES[abortCode]

    /**
     * @param onHandle runs when the failure is a Discord bad request the caller wants to own; the
     *   shared error toast is suppressed first.
     * @param onDefault runs otherwise and returns whether the shared toast should still be shown.
     *   Defaults to `true`, matching the reference default lambda.
     */
    @JvmOverloads
    fun handleAbortCodeOrDefault(
        errorResponse: RestApiError,
        onHandle: () -> Unit,
        onDefault: () -> Boolean = { true },
    ) {
        if (errorResponse.type == RestApiError.Type.DISCORD_BAD_REQUEST) {
            errorResponse.showErrorToasts = false
            onHandle()
        } else {
            errorResponse.showErrorToasts = onDefault()
        }
    }

    object ResponseResolver {
        /**
         * Copy for a failed relationship action. Only `RELATIONSHIP_INCOMING_DISABLED` names the
         * user in its message, which is why it is the single code that receives an argument.
         */
        fun getRelationshipResponse(
            context: Context?,
            abortCode: Int,
            username: String,
        ): CharSequence? {
            if (context == null) return null
            val resId = getAbortCodeMessageResId(abortCode) ?: R.string.add_friend_error_other
            return if (abortCode == RestAPIAbortCodes.RELATIONSHIP_INCOMING_DISABLED) {
                formatPositional(context.getString(resId), username)
            } else {
                FormatUtils.format(context.getString(resId), emptyMap())
            }
        }
    }

    /**
     * The reference renders these strings through its positional helper: the supplied arguments
     * replace the `{token}` placeholders in order of appearance, whatever the tokens are named.
     * [FormatUtils] only exposes name-keyed substitution, so the positional form lives here until
     * `com.discord.i18n` grows one. (Recorded as a convergence item — the app has three
     * hook/placeholder helpers already.)
     */
    private fun formatPositional(template: String, vararg args: Any?): CharSequence {
        var index = 0
        val names = PLACEHOLDER.findAll(template).map { it.groupValues[1] }.toList()
        val substitutions = LinkedHashMap<String, Any?>(names.size)
        for (name in names) {
            if (index >= args.size) break
            if (!substitutions.containsKey(name)) substitutions[name] = args[index]
            index++
        }
        return FormatUtils.format(template, substitutions)
    }

    private val PLACEHOLDER = Regex("""\{(\S+?)\}""")
}

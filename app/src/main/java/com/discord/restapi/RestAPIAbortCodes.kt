package com.discord.restapi

/**
 * Discord's `code` field on a 4xx REST error body.
 *
 * The HTTP status alone is not enough to tell "you already sent this friend request" from "that
 * user does not accept requests" — both are `400`. The API disambiguates with this numeric code,
 * and [com.discord.utilities.rest.RestAPIAbortMessages] maps the ones the client has copy for onto
 * a user-facing string.
 *
 * Transcribed verbatim from the reference `com.discord.restapi.RestAPIAbortCodes`. Note that
 * `TOO_MANY_BOT_GUILDS` and `TOO_MANY_USER_GUILDS` genuinely share the value `30001` there.
 */
object RestAPIAbortCodes {
    const val UNKNOWN_ACCOUNT = 10001
    const val UNKNOWN_APPLICATION = 10002
    const val UNKNOWN_CHANNEL = 10003
    const val UNKNOWN_GUILD = 10004
    const val UNKNOWN_INTEGRATION = 10005
    const val UNKNOWN_INVITE = 10006
    const val UNKNOWN_MEMBER = 10007
    const val UNKNOWN_MESSAGE = 10008
    const val UNKNOWN_OVERWRITE = 10009
    const val UNKNOWN_PLATFORM = 10010
    const val UNKNOWN_ROLE = 10011
    const val UNKNOWN_TOKEN = 10012
    const val UNKNOWN_USER = 10013
    const val UNKNOWN_EMOJI = 10014
    const val UNKNOWN_WEBHOOK = 10015
    const val UNKNOWN_REMOTE_AUTH_SESSION = 10056

    const val BOT_DISALLOWED = 20001
    const val BOT_REQUIRED = 20002
    const val RPC_PROXY_DISALLOWED = 20003
    const val EXPLICIT_CONTENT = 20009
    const val ACCOUNT_SCHEDULED_FOR_DELETION = 20011
    const val ACCOUNT_DISABLED = 20013
    const val SLOWMODE_RATE_LIMITED = 20016

    const val TOO_MANY_BOT_GUILDS = 30001
    const val TOO_MANY_USER_GUILDS = 30001
    const val TOO_MANY_FRIENDS = 30002
    const val TOO_MANY_PINS_IN_CHANNEL = 30003
    const val TOO_MANY_RECIPIENTS = 30004
    const val TOO_MANY_GUILD_ROLES = 30005
    const val TOO_MANY_USING_USERNAME = 30006
    const val TOO_MANY_WEBHOOKS = 30007
    const val TOO_MANY_THREAD_MEMBERS = 30033

    const val UNAUTHORIZED = 40001
    const val EMAIL_VERIFICATION_REQUIRED = 40002
    const val RATE_LIMIT_DM_OPEN = 40003
    const val SEND_MESSAGE_TEMPORARILY_DISABLED = 40004
    const val USER_BANNED = 40007

    const val INVALID_ACCESS = 50001
    const val INVALID_ACCOUNT_TYPE = 50002
    const val INVALID_ACTION_DM = 50003
    const val INVALID_EMBED_DISABLED = 50004
    const val INVALID_MESSAGE_AUTHOR = 50005
    const val INVALID_MESSAGE_EMPTY = 50006
    const val INVALID_MESSAGE_SEND_USER = 50007
    const val INVALID_MESSAGE_SEND_NON_TEXT = 50008
    const val INVALID_MESSAGE_VERIFICATION_LEVEL = 50009
    const val INVALID_OAUTH_APP_BOT = 50010
    const val INVALID_OAUTH_APP_LIMIT = 50011
    const val INVALID_OAUTH_STATE = 50012
    const val INVALID_PERMISSIONS = 50013
    const val INVALID_TOKEN = 50014
    const val INVALID_NOTE = 50015
    const val INVALID_BULK_DELETE_COUNT = 50016
    const val INVALID_MFA_LEVEL = 50017
    const val INVALID_PASSWORD = 50018
    const val INVALID_PIN_MESSAGE_CHANNEL = 50019
    const val INVALID_INVITE_CODE = 50020
    const val INVALID_PHONE_NUMBER = 50022
    const val INVALID_CLIENT_ID = 50023
    const val INVALID_CHANNEL_TYPE = 50024
    const val INVALID_OAUTH2_ACCESS_TOKEN = 50025
    const val INVALID_OAUTH2_MISSING_SCOPE = 50026
    const val INVALID_WEBHOOK_TOKEN = 50027
    const val INVALID_FORM_BODY = 50035
    const val GIFTING_ALREADY_USED_ERROR_CODE = 50050
    const val GIFTING_ALREADY_OWNED_ERROR_CODE = 50051
    const val GIFTING_CANT_REDEEM_OWN_GIFT = 50054

    const val MFA_ENABLED = 60001
    const val MFA_DISABLED = 60002
    const val MFA_REQUIRED = 60003
    const val MFA_UNVERIFIED = 60004
    const val MFA_INVALID_SECRET = 60005
    const val MFA_INVALID_TICKET = 60006
    const val MFA_INVALID_CODE = 60008
    const val MFA_INVALID_SESSION = 60009

    const val PHONE_NUMBER_UNABLE_TO_SEND = 70003
    const val PHONE_VERIFICATION_REQUIRED = 70007

    const val RELATIONSHIP_INCOMING_DISABLED = 80000
    const val RELATIONSHIP_INCOMING_BLOCKED = 80001
    const val RELATIONSHIP_INVALID_USER_BOT = 80002
    const val RELATIONSHIP_INVALID_SELF = 80003
    const val RELATIONSHIP_INVALID_DISCORD_TAG = 80004
    const val RELATIONSHIP_ALREADY_FRIENDS = 80007

    const val GIFTING_CANT_REDEEM_SUBSCRIPTION_MANAGED = 100022
    const val GIFTING_CANT_REDEEM_SUBSCRIPTION_PAST_DUE = 100023
    const val GIFTING_CANT_REDEEM_SKU_MISMATCH = 100024
    const val GIFTING_CANT_REDEEM_INVOICE_OPEN = 100025

    const val SEARCH_INDEXING = 111000

    const val TOO_MANY_THREADS = 160006
    const val TOO_MANY_ANNOUNCEMENT_THREADS = 160007
}

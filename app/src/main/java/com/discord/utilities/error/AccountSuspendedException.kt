package com.discord.utilities.error

import java.io.IOException

/**
 * Login or MFA answered 403 with a suspension body instead of a token.
 *
 * [suspendedUserToken] authenticates the `safety-hub/suspended` endpoints in place of a session.
 */
class AccountSuspendedException(
    val suspendedUserToken: String,
) : IOException("Account is suspended")

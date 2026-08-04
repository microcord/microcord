package com.discord.utilities.error

import java.io.IOException

/**
 * Login or MFA answered 403 with a suspension body instead of a token.
 *
 * The token that comes with it only reaches the safety hub, which this build does not implement,
 * so it is deliberately not retained — the user is told what happened and sent to the web appeal.
 */
class AccountSuspendedException : IOException("Account is suspended")

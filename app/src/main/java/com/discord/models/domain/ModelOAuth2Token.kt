package com.discord.models.domain

import com.discord.api.application.Application
import com.discord.api.auth.OAuthScope

/** An OAuth2 authorization the user has granted to a third-party application. */
data class ModelOAuth2Token(
    val id: Long,
    val scopes: List<OAuthScope>,
    val application: Application,
)

package com.discord.api.activity

data class ActivitySecrets(val match: String?, val join: String?) {
    override fun toString() = "ActivitySecrets(match=<redacted>, join=<redacted>)"
}

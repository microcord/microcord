package com.discord.models.deserialization.gson

import com.discord.api.stageinstance.StageInstancePrivacyLevel
import com.discord.api.stageinstance.StageInstancePrivacyLevelTypeAdapter
import com.google.gson.GsonBuilder

fun GsonBuilder.registerDiscordApiTypeAdapters(): GsonBuilder =
    registerTypeAdapter(StageInstancePrivacyLevel::class.java, StageInstancePrivacyLevelTypeAdapter())

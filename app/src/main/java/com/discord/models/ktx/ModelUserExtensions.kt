package com.discord.models.ktx

import androidx.core.app.Person
import com.discord.models.user.User

/**
 * Adapts a user to the [Person] the notification framework needs for messaging-style
 * notifications; the key is the snowflake so shortcuts and bubbles stay stable across renames.
 */
fun User.toPerson(): Person = Person.Builder()
    .setName(username)
    .setKey(id.toString())
    .setBot(isBot)
    .build()

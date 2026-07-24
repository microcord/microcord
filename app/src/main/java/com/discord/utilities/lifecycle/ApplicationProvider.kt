package com.discord.utilities.lifecycle

import android.app.Application

object ApplicationProvider {
    private lateinit var application: Application

    fun init(application: Application) {
        this.application = application
    }

    fun get(): Application = application
}

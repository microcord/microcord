package com.discord.utilities.lifecycle

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.annotation.MainThread

class ActivityProvider : Application.ActivityLifecycleCallbacks {
    var currentActivity: Activity? = null
        private set

    override fun onActivityStarted(activity: Activity) {
        currentActivity = activity
    }

    override fun onActivityStopped(activity: Activity) {
        if (currentActivity === activity) currentActivity = null
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    companion object {
        private lateinit var instance: ActivityProvider

        fun init(application: Application) {
            instance = ActivityProvider()
            application.registerActivityLifecycleCallbacks(instance)
        }

        @JvmStatic
        @MainThread
        fun getActivity(): Activity? = instance.currentActivity
    }
}

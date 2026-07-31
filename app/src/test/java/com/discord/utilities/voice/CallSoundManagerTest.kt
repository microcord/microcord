package com.discord.utilities.voice

import android.app.Application
import com.discord.app.AppComponent
import com.discord.utilities.media.AppSoundManager
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import rx.android.plugins.RxAndroidPlugins
import rx.android.plugins.RxAndroidSchedulersHook
import rx.plugins.RxJavaHooks
import rx.schedulers.Schedulers
import rx.subjects.PublishSubject
import rx.subjects.Subject
import sun.misc.Unsafe

class CallSoundManagerTest {
    @Before
    fun setUpSchedulers() {
        RxJavaHooks.setOnComputationScheduler { Schedulers.immediate() }
        RxAndroidPlugins.getInstance().reset()
        RxAndroidPlugins.getInstance().registerSchedulersHook(
            object : RxAndroidSchedulersHook() {
                override fun getMainThreadScheduler() = Schedulers.immediate()
            },
        )
    }

    @After
    fun resetSchedulers() {
        RxJavaHooks.reset()
        RxAndroidPlugins.getInstance().reset()
    }

    @Test
    fun `subscribing to another channel releases the previous state stream`() {
        val first = PublishSubject.create<CallSoundManager.StoreState>()
        val second = PublishSubject.create<CallSoundManager.StoreState>()
        val manager = CallSoundManager(
            appComponent = object : AppComponent {
                override val unsubscribeSignal: Subject<Void, Void> = PublishSubject.create()
            },
            appSoundManager = AppSoundManager(unsafeApplication()),
            storeStateGenerator = object : CallSoundManager.IStoreStateGenerator {
                override fun observeStoreState(channelId: Long) = when (channelId) {
                    1L -> first
                    else -> second
                }
            },
        )

        manager.subscribeToStoreState(1L)
        assertTrue(first.hasObservers())

        manager.subscribeToStoreState(2L)
        assertFalse(first.hasObservers())
        assertTrue(second.hasObservers())
    }

    private fun unsafeApplication(): Application = unsafe.allocateInstance(Application::class.java) as Application

    private companion object {
        val unsafe: Unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").run {
            isAccessible = true
            get(null) as Unsafe
        }
    }
}

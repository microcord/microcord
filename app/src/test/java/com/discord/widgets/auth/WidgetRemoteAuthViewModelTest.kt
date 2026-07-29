package com.discord.widgets.auth

import com.discord.models.domain.ModelRemoteAuthHandshake
import com.discord.restapi.RestAPIInterface
import com.discord.restapi.RestAPIParams
import java.lang.reflect.Proxy
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import rx.Observable
import rx.android.plugins.RxAndroidPlugins
import rx.android.plugins.RxAndroidSchedulersHook
import rx.plugins.RxJavaHooks
import rx.schedulers.Schedulers

class WidgetRemoteAuthViewModelTest {
    private var finish: RestAPIParams.RemoteAuthFinish? = null

    @Before fun setUpSchedulers() {
        RxJavaHooks.setOnIOScheduler { Schedulers.immediate() }
        RxAndroidPlugins.getInstance().reset()
        RxAndroidPlugins.getInstance().registerSchedulersHook(
            object : RxAndroidSchedulersHook() {
                override fun getMainThreadScheduler() = Schedulers.immediate()
            },
        )
    }

    @After fun resetSchedulers() {
        RxJavaHooks.reset()
        RxAndroidPlugins.getInstance().reset()
    }

    @Test fun `remote login uses the permanent session flag from 126 21`() {
        val viewModel = WidgetRemoteAuthViewModel("fingerprint", restApi())
        viewModel.toggleTemporary(true)
        viewModel.remoteLogin()

        assertFalse(requireNotNull(finish).temporary)
    }

    private fun restApi(): RestAPIInterface = Proxy.newProxyInstance(
        RestAPIInterface::class.java.classLoader,
        arrayOf(RestAPIInterface::class.java),
    ) { _, method, args ->
        when (method.name) {
            "postRemoteAuthInitialize" -> Observable.just(ModelRemoteAuthHandshake("handshake"))
            "postRemoteAuthFinish" -> {
                finish = args?.single() as RestAPIParams.RemoteAuthFinish
                Observable.never<Void>()
            }
            else -> if (Observable::class.java.isAssignableFrom(method.returnType)) Observable.never<Any>() else null
        }
    } as RestAPIInterface
}

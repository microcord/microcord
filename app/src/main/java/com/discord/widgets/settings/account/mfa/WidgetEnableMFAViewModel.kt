package com.discord.widgets.settings.account.mfa

import android.content.Context
import androidx.annotation.MainThread
import com.discord.app.AppViewModel
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreMFA
import com.discord.stores.StoreStream
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class WidgetEnableMFAViewModel : AppViewModel<WidgetEnableMFAViewModel.ViewState>(ViewState(false, 0)) {
    private var encodedTotpSecret: String? = null
    private var password: String? = null
    private var totpSecret: String? = null

    @MainThread
    fun enableMFA(context: Context, mfaCode: String) {
        updateViewState(ViewState(true, null))
        val secret = encodedTotpSecret ?: return
        val password = password ?: return
        RestAPI.api.enableMFA(RestAPIParams.EnableMFA(mfaCode, secret, password))
            .timeout(2_000, TimeUnit.MILLISECONDS)
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(
                errorClass = WidgetEnableMFAViewModel::class.java,
                context = context,
                errorHandler = { updateViewState(ViewState(false, INPUT_SCREEN)) },
            ) { response ->
                StoreStream.getAuthentication().setAuthed(response.token)
                updateViewState(ViewState(false, SUCCESS_SCREEN))
                StoreStream.getMFA().updatePendingMFAState(StoreMFA.MFAActivationState.PENDING_ENABLED)
            }
    }

    @MainThread
    fun setPassword(password: String) {
        this.password = password
    }

    @MainThread
    fun setTotpSecret(secret: String) {
        totpSecret = secret
        encodedTotpSecret = normalizeTotpSecret(secret)
    }

    fun getPassword(): String? = password

    fun getTotpSecret(): String? = totpSecret

    @MainThread
    fun updateScreenIndex(currentPage: Int) {
        viewState?.let { updateViewState(it.copy(screenIndex = currentPage)) }
    }

    data class ViewState(
        val isLoading: Boolean = false,
        val screenIndex: Int? = null,
    )

    companion object {
        const val INPUT_SCREEN = 2
        const val SUCCESS_SCREEN = 3

        internal fun normalizeTotpSecret(secret: String): String =
            secret.replace(" ", "").uppercase(Locale.getDefault()).trim()

        internal fun generateNewTotpKey(seed: Long = System.currentTimeMillis()): String =
            encodeTotpBase32(Random(seed).nextBytes(10))
                .lowercase(Locale.getDefault())
                .chunked(4)
                .joinToString(" ")

        internal fun encodeTotpBase32(bytes: ByteArray): String {
            val output = StringBuilder((bytes.size + 7) * 8 / 5)
            var buffer = 0
            var bits = 0
            bytes.forEach { byte ->
                buffer = (buffer shl 8) or (byte.toInt() and 0xff)
                bits += 8
                while (bits >= 5) {
                    output.append(BASE32_ALPHABET[(buffer shr (bits - 5)) and 31])
                    bits -= 5
                }
            }
            if (bits > 0) output.append(BASE32_ALPHABET[(buffer shl (5 - bits)) and 31])
            return output.toString()
        }

        private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    }
}

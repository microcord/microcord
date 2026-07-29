package com.discord.widgets.auth

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.discord.app.AppLog
import com.google.android.gms.common.api.Status

/** Extracts the verification code delivered by Google Play Services' SMS Retriever API. */
class SmsAuthCodeBroadcastReceiver(
    val onReceiveCallback: (String) -> Unit,
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SMS_RETRIEVED_ACTION) return
        val extras = intent.extras ?: return
        val status = extras.get(EXTRA_STATUS) as? Status ?: return
        when (status.statusCode) {
            STATUS_SUCCESS -> {
                val message = extras.get(EXTRA_SMS_MESSAGE) as? String
                val code = message?.let(::extractCode).orEmpty()
                if (code.isEmpty()) {
                    AppLog.d(TAG, "Failed to extract code from SMS.")
                } else {
                    // Never log the code: it is an authentication credential.
                    AppLog.d(TAG, "Successfully extracted code from SMS.")
                    onReceiveCallback(code)
                }
            }
            STATUS_TIMEOUT -> AppLog.d(TAG, "Timeout waiting for SMS code.")
            else -> AppLog.d(TAG, "Unexpected status code while waiting for SMS code: ${status.statusCode}")
        }
    }

    private fun extractCode(message: String): String =
        message.substringBefore("\n\n").filter(Char::isDigit)

    private companion object {
        const val TAG = "SmsAuthCodeBroadcastReceiver"
        const val SMS_RETRIEVED_ACTION = "com.google.android.gms.auth.api.phone.SMS_RETRIEVED"
        const val EXTRA_SMS_MESSAGE = "com.google.android.gms.auth.api.phone.EXTRA_SMS_MESSAGE"
        const val EXTRA_STATUS = "com.google.android.gms.auth.api.phone.EXTRA_STATUS"
        const val STATUS_SUCCESS = 0
        const val STATUS_TIMEOUT = 15
    }
}

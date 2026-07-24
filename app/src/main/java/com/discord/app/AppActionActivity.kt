package com.discord.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.discord.utilities.intent.AppIntentContract
import com.discord.utilities.intent.AppIntentParser

class AppActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (AppIntentParser.parse(intent)) {
            is AppIntentContract.Share, is AppIntentContract.SdkAction -> startActivity(
                Intent(intent)
                    .setClass(this, AppActivity.Main::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
            else -> Unit
        }
        finish()
    }
}

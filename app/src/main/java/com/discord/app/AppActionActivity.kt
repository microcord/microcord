package com.discord.app

import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.commit
import com.discord.utilities.ShareUtils
import com.discord.utilities.intent.AppIntentContract
import com.discord.utilities.intent.AppIntentParser
import com.discord.widgets.share.WidgetIncomingShare

class AppActionActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (AppIntentParser.parse(intent)) {
            is AppIntentContract.Share -> ShareUtils.getSharedContent(intent)?.let { content ->
                if (savedInstanceState == null) supportFragmentManager.commit {
                    replace(android.R.id.content, WidgetIncomingShare.newInstance(content))
                }
            } ?: finish()
            is AppIntentContract.SdkAction -> startActivity(
                Intent(intent)
                    .setClass(this, AppActivity.Main::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            ).also { finish() }
            else -> finish()
        }
    }
}

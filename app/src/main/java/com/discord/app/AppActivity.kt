package com.discord.app

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.discord.R
import com.discord.databinding.ActivityAppBinding
import com.discord.models.domain.ModelUserSettings
import com.discord.stores.StoreStream
import java.util.Locale

open class AppActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: Context) {
        val locale = ModelUserSettings.getLocaleObject(StoreStream.getUserSettingsSystem().getLocale())
        Locale.setDefault(locale)
        val configuration = Configuration(newBase.resources.configuration).apply { setLocale(locale) }
        super.attachBaseContext(newBase.createConfigurationContext(configuration))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(
            when (StoreStream.getUserSettingsSystem().getTheme()) {
                ModelUserSettings.THEME_LIGHT -> R.style.AppTheme_Light
                ModelUserSettings.THEME_PURE_EVIL -> R.style.AppTheme_Dark_Evil
                else -> R.style.AppTheme_Dark
            }
        )
        super.onCreate(savedInstanceState)
        setContentView(ActivityAppBinding.inflate(layoutInflater).root)
    }

    class Main : AppActivity()
}

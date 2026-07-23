package com.discord.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.discord.databinding.ActivityAppBinding

open class AppActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ActivityAppBinding.inflate(layoutInflater).root)
    }

    class Main : AppActivity()
}

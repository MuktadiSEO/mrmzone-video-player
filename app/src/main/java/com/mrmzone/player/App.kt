package com.mrmzone.player

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.mrmzone.player.data.Prefs

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        applyTheme(Prefs(this).theme)
    }

    companion object {
        fun applyTheme(mode: Int) = AppCompatDelegate.setDefaultNightMode(
            when (mode) { 0 -> AppCompatDelegate.MODE_NIGHT_NO; 1 -> AppCompatDelegate.MODE_NIGHT_YES; else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM })
    }
}

package com.intentrouter.stubio

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

/**
 * Application entry point.
 *
 * Forces the app into dark mode unconditionally so the UI is always rendered with
 * the dark colour scheme regardless of the device/OS night-mode setting.
 */
class StubioApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
    }
}

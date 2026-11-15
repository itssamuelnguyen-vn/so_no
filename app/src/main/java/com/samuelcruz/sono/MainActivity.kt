package com.samuelcruz.sono

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.samuelcruz.sono.core.AppLock
import com.samuelcruz.sono.ui.AppNav
import com.samuelcruz.sono.ui.LockScreen
import com.samuelcruz.sono.ui.SoNoTheme

class MainActivity : ComponentActivity() {
    private val app by lazy { application as SoNoApp }
    private var locked by mutableStateOf(false)
    /** When the app last went to the background (0 = not since start). */
    private var stoppedAt = 0L

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        locked = app.prefs.lockEnabled
        // Reminders need the notification permission (Android 13+); asked once, can be changed in system settings.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !app.prefs.askedNotifications) {
            app.prefs.askedNotifications = true
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            SoNoTheme(app.themeMode) {
                Box(Modifier.fillMaxSize()) {
                    AppNav()
                    if (locked) LockScreen(onUnlock = ::unlock)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val away = SystemClock.elapsedRealtime() - stoppedAt
        if (app.prefs.lockEnabled && stoppedAt != 0L && away > AppLock.GRACE_MILLIS) locked = true
        app.syncSoon(1_000) // pick up changes made on another phone
    }

    override fun onResume() {
        super.onResume()
        // With the lock on, the recent-apps screen shows a blank card instead of the amounts (Android 13+).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(!app.prefs.lockEnabled)
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            stoppedAt = SystemClock.elapsedRealtime()
            if (app.prefs.changedAt > app.prefs.lastSyncAt) app.syncSoon() // upload before the phone goes idle
        }
    }

    private fun unlock() = AppLock.authenticate(this, onSuccess = { locked = false })
}

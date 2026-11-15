package com.samuelcruz.sono

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.samuelcruz.sono.core.Prefs
import com.samuelcruz.sono.data.AppDatabase
import com.samuelcruz.sono.data.AutoBackup
import com.samuelcruz.sono.data.Photos
import com.samuelcruz.sono.data.Repo
import com.samuelcruz.sono.sync.SnapshotSync
import com.samuelcruz.sono.widget.SummaryWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

/** A snackbar line; [action] is the button label, [onAction] what it does. */
class UiMessage(val text: String, val action: String? = null, val onAction: (() -> Unit)? = null)

/** Holds the long-lived objects (like a Rails initializer). No DI library: there are only a few. */
class SoNoApp : Application() {
    val db: AppDatabase by lazy { AppDatabase.build(this) }
    val prefs: Prefs by lazy { Prefs(this) }
    val repo: Repo by lazy { Repo(this, db, ::changed) }
    /** For work that must finish even if the screen that started it closes. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Snackbar messages from any screen (shown by AppNav), optionally with one action button. */
    val messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 8)

    /** Observable copy of [Prefs.theme], so switching theme redraws at once. */
    var themeMode by mutableIntStateOf(Prefs.THEME_SYSTEM)
        private set

    private var widgetJob: Job? = null
    private var syncJob: Job? = null

    /** Result of the last sync, for Settings (conflicts are answered there). */
    var syncState by mutableStateOf<SnapshotSync.Result?>(null)
        private set

    override fun onCreate() {
        super.onCreate()
        themeMode = prefs.theme
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(Reminders.CHANNEL, "Nhắc nợ và hụi", Reminders.channelImportance()),
        )
        Reminders.schedule(this)
        if (prefs.autoBackupDir.isNotEmpty()) AutoBackup.schedule(this)
        if (prefs.syncEmail.isNotEmpty()) SnapshotSync.schedule(this)
        scope.launch { runCatching { Photos.cleanUp(this@SoNoApp, db) } }
        refreshWidget() // redraw widgets with this version's code after an update
    }

    fun changeTheme(value: Int) {
        prefs.theme = value
        themeMode = value
    }

    fun message(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
        messages.tryEmit(UiMessage(text, action, onAction))
    }

    /** Something changed here: refresh the widget and upload to Drive a little later (after a burst of edits). */
    fun changed() {
        prefs.changedAt = System.currentTimeMillis()
        refreshWidget()
        syncSoon(20_000)
    }

    /** Data was replaced from a backup or from Drive (not a local edit, nothing to upload). */
    fun dataReplaced() = refreshWidget()

    private fun refreshWidget() {
        widgetJob?.cancel()
        widgetJob = scope.launch {
            delay(400)
            runCatching { SummaryWidget.refresh(this@SoNoApp) }
        }
    }

    /** Syncs with Drive after [delayMs] (each call restarts the wait). Does nothing when not connected. */
    fun syncSoon(delayMs: Long = 0, keep: SnapshotSync.Keep? = null) {
        if (prefs.syncEmail.isEmpty()) return
        syncJob?.cancel()
        syncJob = scope.launch {
            delay(delayMs)
            val r = SnapshotSync.sync(this@SoNoApp, keep)
            syncState = r
            if (r == SnapshotSync.Result.Downloaded) message("Đã cập nhật dữ liệu từ Google Drive")
        }
    }
}

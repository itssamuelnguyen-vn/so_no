package com.samuelcruz.sono.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.samuelcruz.sono.SoNoApp
import com.samuelcruz.sono.data.Backup
import com.samuelcruz.sono.data.SnapshotFiles
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Google Drive sync, the simple way: the whole book (the same `.zip` as a backup) is one file, `sono-data.zip`, in
 * the app's hidden Drive folder. Drive numbers every change of a file ([RemoteFile.version]), so:
 * - Drive unchanged since our last sync, this phone changed → upload.
 * - Drive changed (another phone), this phone unchanged → download and replace.
 * - Both changed → [Result.Conflict]: the user picks which copy to keep; the other is saved as a backup file.
 * Fine for one person with a phone or two (the owner's case); it is not a row-by-row merge.
 */
object SnapshotSync {
    const val FILE = "sono-data.zip"
    private const val WORK = "drive-sync"
    private val lock = Mutex()

    sealed interface Result {
        data object Nothing : Result
        data object Uploaded : Result
        data object Downloaded : Result
        /** Both sides changed since the last sync (or first connection on a phone that already has data). */
        data object Conflict : Result
        data object NeedsSignIn : Result
        data class Failed(val message: String) : Result
    }

    enum class Keep { THIS_PHONE, DRIVE }

    fun connected(context: Context) = (context.applicationContext as SoNoApp).prefs.syncEmail.isNotEmpty()

    /** Every 6 hours with a network, in the background. */
    fun schedule(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build(),
        )
    }

    fun cancel(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(WORK)

    /** One sync pass. [keep] answers an earlier [Result.Conflict]. */
    suspend fun sync(context: Context, keep: Keep? = null): Result = lock.withLock {
        val app = context.applicationContext as SoNoApp
        val prefs = app.prefs
        if (prefs.syncEmail.isEmpty()) return Result.Nothing
        val result = try {
            var token = GoogleAuth.silentToken(context, prefs.syncEmail) ?: return Result.NeedsSignIn
            try {
                pass(context, Drive(token), keep)
            } catch (e: DriveException) {
                if (e.code != 401) throw e
                GoogleAuth.clearToken(context, token)
                token = GoogleAuth.silentToken(context, prefs.syncEmail) ?: return Result.NeedsSignIn
                pass(context, Drive(token), keep)
            }
        } catch (e: Exception) {
            Result.Failed(e.message ?: e.javaClass.simpleName)
        }
        prefs.syncError = when (result) {
            is Result.Failed -> result.message
            Result.NeedsSignIn -> "Cần đăng nhập Google lại"
            Result.Conflict -> "Hai máy cùng sửa: chọn giữ bản nào trong Cài đặt"
            else -> ""
        }
        result
    }

    private suspend fun pass(context: Context, drive: Drive, keep: Keep?): Result {
        val app = context.applicationContext as SoNoApp
        val prefs = app.prefs
        val remote = drive.list().filter { it.name == FILE }.maxByOrNull { it.modifiedAt }
        val dirty = prefs.changedAt > prefs.lastSyncAt
        val known = remote != null && remote.id == prefs.syncFileId && remote.version == prefs.syncVersion

        fun done(f: RemoteFile) {
            prefs.syncFileId = f.id
            prefs.syncVersion = f.version
            prefs.lastSyncAt = maxOf(System.currentTimeMillis(), prefs.changedAt)
        }

        suspend fun upload(): Result {
            val bytes = ByteArrayOutputStream().also { Backup.write(context, app.db, it) }.toByteArray()
            val f = if (remote == null) drive.create(FILE, "application/zip", bytes) else drive.update(remote.id, "application/zip", bytes)
            done(f)
            return Result.Uploaded
        }

        suspend fun download(): Result {
            val bytes = drive.download(remote!!.id)
            Backup.restore(context, app.db, ByteArrayInputStream(bytes))
            app.dataReplaced()
            done(remote)
            return Result.Downloaded
        }

        return when {
            keep == Keep.THIS_PHONE -> {
                // Keep Drive's copy on this phone before overwriting it, in case it was the wrong choice.
                if (remote != null) SnapshotFiles.saveBytes(context, drive.download(remote.id), "ban-drive-truoc-khi-ghi-de")
                upload()
            }
            keep == Keep.DRIVE && remote != null -> {
                SnapshotFiles.saveLocalCopy(context, app.db, "truoc-khi-lay-tu-drive")
                download()
            }
            remote == null -> upload()
            known -> if (dirty) upload() else Result.Nothing
            // Drive changed elsewhere. A phone that changed too, or never synced but has data, must choose.
            dirty || (prefs.syncVersion == 0L && hasData(app)) -> Result.Conflict
            else -> download()
        }
    }

    private suspend fun hasData(app: SoNoApp) = app.db.debts().all().isNotEmpty() || app.db.hui().groups().isNotEmpty()
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val r = SnapshotSync.sync(applicationContext)
        return if (r is SnapshotSync.Result.Failed) Result.retry() else Result.success()
    }
}

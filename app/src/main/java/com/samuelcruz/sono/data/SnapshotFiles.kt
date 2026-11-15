package com.samuelcruz.sono.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Safety copies made before sync replaces data (filesDir/snapshots, newest [KEEP] kept). Settings lists them and
 * can restore one, so picking the wrong side of a sync conflict is never final.
 */
object SnapshotFiles {
    private const val KEEP = 5
    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss")

    fun dir(context: Context) = File(context.filesDir, "snapshots").apply { mkdirs() }

    fun list(context: Context): List<File> = dir(context).listFiles()?.filter { it.name.endsWith(".zip") }?.sortedByDescending { it.name }.orEmpty()

    suspend fun saveLocalCopy(context: Context, db: AppDatabase, label: String) = withContext(Dispatchers.IO) {
        file(context, label).outputStream().use { Backup.write(context, db, it) }
        trim(context)
    }

    suspend fun saveBytes(context: Context, bytes: ByteArray, label: String) = withContext(Dispatchers.IO) {
        file(context, label).writeBytes(bytes)
        trim(context)
    }

    private fun file(context: Context, label: String) = File(dir(context), LocalDateTime.now().format(stamp) + "-" + label + ".zip")

    private fun trim(context: Context) = list(context).drop(KEEP).forEach { it.delete() }
}

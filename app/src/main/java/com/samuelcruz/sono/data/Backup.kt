package com.samuelcruz.sono.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.samuelcruz.sono.SoNoApp
import com.samuelcruz.sono.core.Dates
import com.samuelcruz.sono.core.Direction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** The whole database. [version] is the file format, bumped only if old files need converting. */
@Serializable
data class BackupFile(
    val app: String = APP,
    val version: Int = VERSION,
    val exportedAt: Long,
    val people: List<Person>,
    val debts: List<Debt>,
    val payments: List<Payment>,
    val hui: List<HuiGroup>,
    val slots: List<HuiSlot>,
    val rounds: List<HuiRound>,
    val paid: List<HuiPaid>,
) {
    companion object {
        const val APP = "sono"
        const val VERSION = 1
    }
}

/** A `.zip` with `data.json` and the photos, so restoring on a new phone brings everything back. */
object Backup {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun write(context: Context, db: AppDatabase, out: OutputStream): Int = withContext(Dispatchers.IO) {
        val file = BackupFile(
            exportedAt = System.currentTimeMillis(),
            people = db.people().all(), debts = db.debts().all(), payments = db.payments().all(),
            hui = db.hui().groups(), slots = db.hui().allSlots(), rounds = db.hui().allRounds(), paid = db.hui().allPaid(),
        )
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("data.json"))
            zip.write(json.encodeToString(BackupFile.serializer(), file).toByteArray())
            zip.closeEntry()
            for (name in file.debts.mapNotNull { it.photo }.distinct()) {
                val f = Photos.file(context, name)
                if (!f.exists()) continue
                zip.putNextEntry(ZipEntry("photos/$name"))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        file.debts.size + file.hui.size
    }

    /** Replaces everything with the backup. Throws [IllegalArgumentException] with a message for the user. */
    suspend fun restore(context: Context, db: AppDatabase, input: InputStream) = withContext(Dispatchers.IO) {
        var file: BackupFile? = null
        val photos = mutableMapOf<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                when {
                    e.name == "data.json" -> file = runCatching {
                        json.decodeFromString(BackupFile.serializer(), zip.readBytes().decodeToString())
                    }.getOrNull()
                    e.name.startsWith("photos/") && !e.name.contains("..") -> photos[e.name.removePrefix("photos/")] = zip.readBytes()
                }
            }
        }
        val f = file ?: throw IllegalArgumentException("File này không phải bản sao lưu của Sổ Nợ & Hụi")
        require(f.app == BackupFile.APP) { "File này không phải bản sao lưu của Sổ Nợ & Hụi" }
        require(f.version <= BackupFile.VERSION) { "Bản sao lưu này từ phiên bản mới hơn, hãy cập nhật ứng dụng" }
        photos.forEach { (name, bytes) -> if ('/' !in name) Photos.file(context, name).writeBytes(bytes) }
        db.withTransaction {
            db.wipe().people() // cascades to debts and payments
            db.wipe().hui() // cascades to slots, rounds and paid marks
            db.wipe().people(f.people)
            db.wipe().debts(f.debts)
            db.wipe().payments(f.payments)
            db.wipe().groups(f.hui)
            db.wipe().slots(f.slots)
            db.wipe().rounds(f.rounds)
            db.wipe().paid(f.paid)
        }
    }

    /** Debts and payments as CSV for Excel / Google Sheets (UTF-8 with BOM so Excel shows Vietnamese). */
    suspend fun csv(db: AppDatabase): String = withContext(Dispatchers.IO) {
        val people = db.people().all().associateBy { it.id }
        val payments = db.payments().all().groupBy { it.debtId }
        val today = Dates.todayDay()
        val out = StringBuilder("﻿")
        out.append("Người,Loại,Số tiền,Ngày,Hạn/kỳ đầu,Trả góp,Lãi,Đã trả,Còn lại,Tình trạng,Ghi chú\r\n")
        for (d in db.debts().all().sortedBy { it.startDay }) {
            val s = DebtState(d, payments[d.id].orEmpty(), today)
            listOf(
                people[d.personId]?.name.orEmpty(),
                if (d.direction == Direction.LENT) "Cho mượn" else "Đi mượn",
                d.amount.toString(),
                Dates.format(d.startDay),
                d.dueDay?.let { Dates.format(it) }.orEmpty(),
                if (d.plan == com.samuelcruz.sono.core.DebtPlan.INSTALLMENTS) "${d.term} kỳ" else "",
                if (d.hasInterest) "${d.ratePercent}%/${if (d.rateYearly) "năm" else "tháng"}" else "",
                s.paid.toString(),
                s.left.toString(),
                if (d.closed) "Đã xong" else "Đang nợ",
                d.note,
            ).joinTo(out, ",") { cell(it) }
            out.append("\r\n")
        }
        out.append("\r\nNgười,Ngày trả,Số tiền,Kỳ,Ghi chú\r\n")
        val debts = db.debts().all().associateBy { it.id }
        for (p in db.payments().all().sortedBy { it.day }) {
            val d = debts[p.debtId] ?: continue
            listOf(
                people[d.personId]?.name.orEmpty(), Dates.format(p.day), p.amount.toString(),
                p.installment?.toString().orEmpty(), p.note,
            ).joinTo(out, ",") { cell(it) }
            out.append("\r\n")
        }
        out.toString()
    }

    private fun cell(v: String): String =
        if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v
}

/**
 * Weekly automatic backup (like a cron job): the same `.zip` written into a folder picked once (Android's folder
 * picker grants lasting access). Only the newest [KEEP] automatic backups are kept; other files are never touched.
 */
object AutoBackup {
    const val KEEP = 5
    const val PREFIX = "sono-auto-"
    private const val WORK = "auto-backup"
    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm")

    fun enable(context: Context, tree: Uri) {
        val app = context.applicationContext as SoNoApp
        context.contentResolver.takePersistableUriPermission(
            tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        val old = app.prefs.autoBackupDir
        if (old.isNotEmpty() && old != tree.toString()) release(context, old)
        app.prefs.autoBackupDir = tree.toString()
        app.prefs.autoBackupFailed = false
        schedule(context)
    }

    fun disable(context: Context) {
        val app = context.applicationContext as SoNoApp
        release(context, app.prefs.autoBackupDir)
        app.prefs.autoBackupDir = ""
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
    }

    private fun release(context: Context, uri: String) {
        if (uri.isEmpty()) return
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    /** Every 7 days, when the battery is not low. KEEP: re-scheduling on each start does not reset the timer. */
    fun schedule(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<AutoBackupWorker>(7, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build(),
        )
    }

    fun folderName(context: Context, uri: String): String =
        runCatching { DocumentFile.fromTreeUri(context, Uri.parse(uri))?.name }.getOrNull()
            ?: Uri.parse(uri).lastPathSegment?.substringAfterLast(':').orEmpty()

    suspend fun run(context: Context) = withContext(Dispatchers.IO) {
        val app = context.applicationContext as SoNoApp
        val dirUri = app.prefs.autoBackupDir
        check(dirUri.isNotEmpty()) { "Off" }
        try {
            val dir = DocumentFile.fromTreeUri(context, Uri.parse(dirUri))
            check(dir != null && dir.canWrite()) { "No access to the folder" }
            val name = PREFIX + LocalDateTime.now().format(stamp) + ".zip"
            val file = dir.createFile("application/zip", name) ?: error("Cannot create $name")
            context.contentResolver.openOutputStream(file.uri)!!.use { Backup.write(context, app.db, it) }
            dir.listFiles().filter { it.name.orEmpty().startsWith(PREFIX) }
                .sortedByDescending { it.name }
                .drop(KEEP)
                .forEach { it.delete() }
            app.prefs.autoBackupLast = System.currentTimeMillis()
            app.prefs.autoBackupFailed = false
        } catch (e: Exception) {
            app.prefs.autoBackupFailed = true
            throw e
        }
    }
}

class AutoBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as SoNoApp
        if (app.prefs.autoBackupDir.isEmpty()) return Result.success()
        return runCatching { AutoBackup.run(applicationContext) }.fold({ Result.success() }, { Result.failure() })
    }
}

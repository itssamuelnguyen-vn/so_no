package com.samuelcruz.sono

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.samuelcruz.sono.core.Dates
import com.samuelcruz.sono.core.DebtPlan
import com.samuelcruz.sono.core.Direction
import com.samuelcruz.sono.core.Money
import com.samuelcruz.sono.data.AppDatabase
import com.samuelcruz.sono.data.DebtState
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * One notification each morning (like a daily cron job) listing what needs attention: debts and installments due
 * in their "remind X days before" window, due today or late, and hụi rounds coming up.
 */
object Reminders {
    const val CHANNEL = "reminders"
    private const val WORK = "daily-reminders"
    private const val NOTIFICATION_ID = 1

    /** (Re)schedules the daily check at [Prefs.reminderHour]; [reset] when the hour changed. */
    fun schedule(context: Context, reset: Boolean = false) {
        val app = context.applicationContext as SoNoApp
        val wm = WorkManager.getInstance(context)
        if (!app.prefs.reminders) {
            wm.cancelUniqueWork(WORK)
            return
        }
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(app.prefs.reminderHour, 0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val request = PeriodicWorkRequestBuilder<ReminderWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(Duration.between(now, next).toMinutes(), TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork(WORK, if (reset) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Lines for today's notification. */
    suspend fun lines(db: AppDatabase, today: Long = Dates.todayDay()): List<String> {
        val people = db.people().all().associateBy { it.id }
        val payments = db.payments().all().groupBy { it.debtId }
        val out = mutableListOf<String>()
        for (d in db.debts().all()) {
            if (d.closed || d.remindDays < 0) continue
            val s = DebtState(d, payments[d.id].orEmpty(), today)
            val name = people[d.personId]?.name ?: "?"
            val dues = s.dueDates()
            dues.forEachIndexed { i, (day, amount) ->
                val diff = day - today
                val late = -diff
                val show = diff == 0L || (diff > 0 && diff == d.remindDays.toLong()) ||
                    (i == 0 && late > 0 && (late == 1L || late == 3L || late % 7 == 0L))
                if (!show) return@forEachIndexed
                val what = if (d.direction == Direction.LENT) "$name trả bạn ${Money.format(amount)}"
                else "bạn trả $name ${Money.format(amount)}"
                val part = if (d.plan == DebtPlan.INSTALLMENTS) {
                    val n = s.plan!!.items.first { it.installment.due.toEpochDay() == day }.installment.number
                    " (kỳ $n/${d.term})"
                } else ""
                out += when {
                    diff == 0L -> "Hôm nay: $what$part"
                    diff > 0 -> "Còn $diff ngày: $what$part"
                    else -> "Quá hạn $late ngày: $what$part"
                }
            }
        }
        val rounds = db.hui().allRounds().groupBy { it.huiId }
        for (g in db.hui().groups()) {
            if (g.remindDays < 0) continue
            val held = rounds[g.id].orEmpty().size
            if (held >= g.slots) continue
            val date = g.dates()[held].toEpochDay()
            val diff = date - today
            if (diff == 0L || (diff > 0 && diff == g.remindDays.toLong())) {
                out += "${if (diff == 0L) "Hôm nay" else "Còn $diff ngày"}: khui hụi ${g.name} kỳ ${held + 1}/${g.slots}"
            }
        }
        return out
    }

    suspend fun notify(context: Context) {
        val app = context.applicationContext as SoNoApp
        val today = Dates.todayDay()
        if (app.prefs.lastReminderDay == today) return
        val lines = lines(app.db, today)
        app.prefs.lastReminderDay = today
        if (lines.isEmpty()) return
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val style = NotificationCompat.InboxStyle()
        lines.take(7).forEach { style.addLine(it) }
        if (lines.size > 7) style.setSummaryText("và ${lines.size - 7} việc khác")
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (lines.size == 1) "Nhắc nợ & hụi" else "Nhắc nợ & hụi: ${lines.size} việc")
            .setContentText(lines.first())
            .setStyle(style)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, n)
    }

    fun channelImportance() = NotificationManager.IMPORTANCE_DEFAULT
}

class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runCatching { Reminders.notify(applicationContext) }
        return Result.success()
    }
}

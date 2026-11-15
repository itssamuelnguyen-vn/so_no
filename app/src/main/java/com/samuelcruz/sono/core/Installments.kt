package com.samuelcruz.sono.core

import java.time.LocalDate
import kotlin.math.pow
import kotlin.math.roundToLong

/** How a debt is paid back. */
object DebtPlan {
    /** One payment (or several, whenever), optionally with a due date. */
    const val ONCE = 0
    /** A fixed number of installments (kỳ) on a schedule: monthly on a fixed day, or every N days (góp ngày/tuần). */
    const val INSTALLMENTS = 1
}

/** How interest is charged. */
object InterestMethod {
    const val NONE = 0
    /** One-time debts: simple interest by the day on what is still owed (see [DebtMath]). */
    const val SIMPLE = 1
    /** Installments, "lãi phẳng": interest on the original amount every period. */
    const val FLAT = 2
    /** Installments, "dư nợ giảm dần": equal principal parts, interest on what is still owed. */
    const val DECLINING = 3
    /** Installments, "góp đều": the same total every period (annuity), interest on what is still owed. */
    const val ANNUITY = 4
}

/** One installment of the schedule. */
data class Installment(
    val number: Int,
    val due: LocalDate,
    val principal: Long,
    val interest: Long,
    /** Principal still owed after this installment is paid. */
    val balanceAfter: Long,
) {
    val total: Long get() = principal + interest
}

/** How far an installment is paid. */
data class InstallmentStatus(
    val installment: Installment,
    val paid: Long,
    /** Day of the payment that completed it, null while not fully paid. */
    val fullDay: Long? = null,
) {
    val full: Boolean get() = paid >= installment.total
    val left: Long get() = (installment.total - paid).coerceAtLeast(0)
}

/** Where an installment debt stands. */
data class PlanBalance(
    val items: List<InstallmentStatus>,
    val totalDue: Long,
    val paid: Long,
    /** Principal not covered by fully paid installments yet. */
    val principalLeft: Long,
) {
    val left: Long get() = (totalDue - paid).coerceAtLeast(0)
    val next: InstallmentStatus? get() = items.firstOrNull { !it.full }
    fun overdue(today: LocalDate): List<InstallmentStatus> = items.filter { !it.full && it.installment.due < today }
    val paidCount: Int get() = items.count { it.full }
}

object Installments {
    /** Monthly rate as a fraction from what the user typed: 1,5%/tháng or 18%/năm → 0.015. */
    fun monthlyRate(percent: Double, yearly: Boolean): Double = (if (yearly) percent / 12 else percent) / 100

    /** Rate for one period: monthly, or by days (30 days = one month). */
    fun periodRate(monthlyRate: Double, cycle: Int, everyDays: Int): Double =
        if (cycle == HuiCycle.MONTHLY) monthlyRate else monthlyRate * everyDays.coerceAtLeast(1) / 30.0

    /**
     * The schedule of [term] installments for [principal], the first due on [firstDue], then monthly on the same day
     * (clamped in short months) or every [everyDays] days. Rounding leftovers go into the last installment, so the
     * principal parts always add up exactly.
     */
    fun schedule(
        principal: Long,
        term: Int,
        method: Int,
        monthlyRate: Double,
        cycle: Int,
        everyDays: Int,
        firstDue: LocalDate,
        /** Installments moved to another day ("dời hạn"); amounts do not change. */
        overrides: Map<Int, LocalDate> = emptyMap(),
    ): List<Installment> {
        val n = term.coerceAtLeast(1)
        val r = if (method == InterestMethod.NONE || method == InterestMethod.SIMPLE) 0.0 else periodRate(monthlyRate, cycle, everyDays)
        val out = ArrayList<Installment>(n)
        var left = principal
        val equalPart = principal / n
        val annuity = if (method == InterestMethod.ANNUITY && r > 0) principal * r / (1 - (1 + r).pow(-n)) else 0.0
        for (i in 1..n) {
            val interest = when {
                r == 0.0 -> 0L
                method == InterestMethod.FLAT -> (principal * r).roundToLong()
                else -> (left * r).roundToLong()
            }
            val part = when {
                i == n -> left
                method == InterestMethod.ANNUITY && r > 0 -> (annuity - left * r).roundToLong().coerceIn(0, left)
                else -> equalPart
            }
            left -= part
            out += Installment(i, overrides[i] ?: HuiSchedule.date(cycle, everyDays, firstDue, i), part, interest, left)
        }
        return out
    }

    /** A payment as the schedule sees it: which installment (null = the earliest unpaid ones), how much, when. */
    data class Pay(val installment: Int?, val amount: Long, val day: Long = 0)

    /**
     * Applies payments to installments in date order. Payments tagged with an installment number go to it;
     * untagged ones fill the earliest unpaid installments (paying ahead). Remembers when each one became full.
     */
    fun balance(schedule: List<Installment>, payments: List<Pay>): PlanBalance {
        val paid = LongArray(schedule.size)
        val fullDay = arrayOfNulls<Long>(schedule.size)
        var extra = 0L
        fun mark(i: Int, day: Long) { if (fullDay[i] == null && paid[i] >= schedule[i].total) fullDay[i] = day }
        for (p in payments.sortedBy { it.day }) {
            val n = p.installment
            if (n != null && n in 1..schedule.size) {
                paid[n - 1] += p.amount
                mark(n - 1, p.day)
                continue
            }
            var loose = p.amount
            for (i in schedule.indices) {
                if (loose <= 0) break
                val room = (schedule[i].total - paid[i]).coerceAtLeast(0)
                val add = minOf(room, loose)
                paid[i] += add
                loose -= add
                mark(i, p.day)
            }
            extra += loose
        }
        // Anything beyond the whole schedule (overpaid, or late fees) stays on the last installment so totals add up.
        if (extra > 0 && schedule.isNotEmpty()) paid[schedule.lastIndex] += extra
        val items = schedule.mapIndexed { i, s -> InstallmentStatus(s, paid[i], fullDay[i]) }
        val principalLeft = schedule.sumOf { it.principal } - items.filter { it.full }.sumOf { it.installment.principal }
        return PlanBalance(items, schedule.sumOf { it.total }, paid.sum(), principalLeft)
    }
}

/** Late fees ("phạt trễ hạn"), counted for every installment (or the one-time due date) paid late. */
object PenaltyMode {
    const val NONE = 0
    /** A fixed amount in đồng for each day late. */
    const val PER_DAY = 1
    /** A percentage of the late amount for each day late. */
    const val PERCENT_PER_DAY = 2
    /** A fixed amount once, as soon as it is late. */
    const val ONCE = 3

    /**
     * The fee for an [amount] due on [due] and fully paid on [paidDay] (null = still unpaid, counted up to [today]),
     * after [grace] free days.
     */
    fun fee(mode: Int, value: Double, amount: Long, due: Long, paidDay: Long?, today: Long, grace: Int = 0): Long {
        val late = ((paidDay ?: today) - due - grace).coerceAtLeast(0)
        if (late == 0L) return 0
        return when (mode) {
            PER_DAY -> (value * late).toLong()
            PERCENT_PER_DAY -> (amount * value / 100.0 * late).toLong()
            ONCE -> value.toLong()
            else -> 0
        }
    }
}

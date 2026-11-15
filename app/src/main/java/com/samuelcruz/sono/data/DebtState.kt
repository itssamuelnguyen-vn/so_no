package com.samuelcruz.sono.data

import com.samuelcruz.sono.core.DebtBalance
import com.samuelcruz.sono.core.DebtMath
import com.samuelcruz.sono.core.DebtPlan
import com.samuelcruz.sono.core.Direction
import com.samuelcruz.sono.core.Installments
import com.samuelcruz.sono.core.PenaltyMode
import com.samuelcruz.sono.core.PlanBalance
import java.time.LocalDate

/** A debt with its payments, worked out for [today]: what is left, what is due next, what is late. */
class DebtState(val debt: Debt, payments: List<Payment>, val today: Long) {
    /** Payments and top-ups, oldest first. */
    val payments: List<Payment> = payments.sortedWith(compareBy({ it.day }, { it.createdAt }))
    private val pays = this.payments.filter { !it.isTopUp }
    val topUps: List<Payment> = this.payments.filter { it.isTopUp }

    /** The first amount plus every top-up ("vay thêm"). */
    val principal: Long = debt.amount + topUps.sumOf { it.amount }

    /** Installment debts only. */
    val plan: PlanBalance? =
        if (debt.plan == DebtPlan.INSTALLMENTS) Installments.balance(debt.schedule(), pays.map { Installments.Pay(it.installment, it.amount, it.day) })
        else null

    /** One-time debts only. */
    val once: DebtBalance? =
        if (debt.plan == DebtPlan.INSTALLMENTS) null
        else DebtMath.balance(debt.amount, debt.startDay, debt.monthlyRate, this.payments.map { it.toRepayment() }, today, debt.closedDay)

    val paid: Long = pays.sumOf { it.amount }

    /** Late fees so far (frozen once the late money is paid; nothing after settling). */
    val penalty: Long = when {
        debt.penaltyMode == PenaltyMode.NONE -> 0
        plan != null -> plan.items.sumOf { s ->
            PenaltyMode.fee(debt.penaltyMode, debt.penaltyValue, s.installment.total, s.installment.due.toEpochDay(), s.fullDay ?: debt.closedDay, today)
        }
        debt.dueDay != null -> {
            val doneDay = debt.closedDay ?: if (once!!.left <= 0) pays.lastOrNull()?.day else null
            PenaltyMode.fee(debt.penaltyMode, debt.penaltyValue, principal, debt.dueDay, doneDay, today)
        }
        else -> 0
    }

    /** Still owed (0 once settled; negative = overpaid). */
    val left: Long = when {
        debt.closed -> 0
        plan != null -> (plan.totalDue + penalty - plan.paid).coerceAtLeast(0)
        else -> once!!.left + penalty
    }

    /** Interest over the whole debt so far (one-time) or in the whole schedule (installments). */
    val interest: Long = plan?.let { it.totalDue - debt.amount } ?: (once!!.interestPaid + once.interestDue)

    /** + when it comes back to me, − when I owe it. */
    val signedLeft: Long get() = Direction.sign(debt.direction) * left

    /** Next date something is due, or null (no due date / settled). */
    val nextDueDay: Long? = when {
        debt.closed -> null
        plan != null -> plan.next?.installment?.due?.toEpochDay() ?: if (left > 0) today else null
        left > 0 -> debt.dueDay
        else -> null
    }

    /** Amount due on [nextDueDay]: the next installment's unpaid part, or everything for a one-time debt. */
    val nextDueAmount: Long = when {
        debt.closed -> 0
        plan != null -> plan.next?.left ?: left
        else -> left.coerceAtLeast(0)
    }

    /** How late the oldest unpaid due date is, in days (0 = not late). */
    val overdueDays: Long = nextDueDay?.let { (today - it).coerceAtLeast(0) } ?: 0

    /** Late money: unpaid parts of past installments (+ fees), or everything for a late one-time debt. */
    val overdueAmount: Long = when {
        overdueDays == 0L -> 0
        plan != null -> plan.overdue(LocalDate.ofEpochDay(today)).sumOf { it.left } + penalty
        else -> left
    }

    /** Fully paid but not marked settled yet (offer "Tất toán"). */
    val paidOff: Boolean = !debt.closed && left <= 0 && paid > 0

    /** Upcoming unpaid due dates with their amounts (for reminders, the calendar and the "sắp đến hạn" list). */
    fun dueDates(): List<Pair<Long, Long>> = when {
        debt.closed -> emptyList()
        plan != null -> plan.items.filter { !it.full }.map { it.installment.due.toEpochDay() to it.left }
        debt.dueDay != null && left > 0 -> listOf(debt.dueDay to left)
        else -> emptyList()
    }

    /** Interest (and late fees) actually received or paid so far: payments beyond the principal. */
    val interestPaid: Long = when {
        plan != null -> plan.items.sumOf { minOf(it.paid, it.installment.interest) } + (plan.paid - plan.totalDue).coerceAtLeast(0)
        else -> once!!.interestPaid
    }
}

/** Totals over many debts. */
data class DebtTotals(val toCollect: Long, val toPay: Long, val overdueCollect: Long, val overduePay: Long)

fun List<DebtState>.totals(): DebtTotals {
    var c = 0L; var p = 0L; var oc = 0L; var op = 0L
    for (s in this) {
        if (s.debt.closed || s.left <= 0) continue
        if (s.debt.direction == Direction.LENT) { c += s.left; oc += s.overdueAmount } else { p += s.left; op += s.overdueAmount }
    }
    return DebtTotals(c, p, oc, op)
}

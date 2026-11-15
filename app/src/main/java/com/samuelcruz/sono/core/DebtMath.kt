package com.samuelcruz.sono.core

import kotlin.math.roundToLong

/** Where the money went: [LENT] = I lent it (they owe me), [BORROWED] = I borrowed it (I owe them). */
object Direction {
    const val LENT = 1
    const val BORROWED = 2

    /** +1 when the money comes back to me, −1 when I have to pay. */
    fun sign(direction: Int): Int = if (direction == LENT) 1 else -1
}

/** A repayment, or with [topUp] more money lent / borrowed on the same debt ("vay thêm"). */
data class Repayment(val day: Long, val amount: Long, val topUp: Boolean = false)

/** Where a debt stands on a given day. A negative [left] means it was overpaid. */
data class DebtBalance(
    /** The first amount plus every top-up. */
    val principal: Long,
    val principalLeft: Long,
    /** Interest accrued and not paid yet. */
    val interestDue: Long,
    val interestPaid: Long,
    val paid: Long,
) {
    val left: Long get() = principalLeft + interestDue
}

/**
 * Simple interest, the way informal loans work in Vietnam: "lãi 2%/tháng" = 2% of what is still owed per 30 days,
 * counted by the day. A repayment first clears the interest accrued so far, then reduces the principal, so interest
 * stops growing on what has been paid back.
 */
object DebtMath {
    /**
     * [monthlyRate] is a fraction per 30 days (0.015 = 1,5%/tháng). Interest runs from [startDay] to [asOfDay]
     * (or [closedDay] if the debt is closed earlier).
     */
    fun balance(
        principal: Long,
        startDay: Long,
        monthlyRate: Double,
        payments: List<Repayment>,
        asOfDay: Long,
        closedDay: Long? = null,
    ): DebtBalance {
        val end = minOf(asOfDay, closedDay ?: Long.MAX_VALUE)
        val daily = monthlyRate / 30.0
        var principalLeft = principal
        var total = principal
        var interest = 0.0 // accrued, unpaid, in đồng (fractional until paid)
        var interestPaid = 0L
        var paid = 0L
        var lastDay = startDay
        for (p in payments.sortedBy { it.day }) {
            if (p.day > end) break
            val day = maxOf(p.day, startDay)
            if (principalLeft > 0) interest += principalLeft * daily * (day - lastDay)
            lastDay = day
            if (p.topUp) {
                principalLeft += p.amount
                total += p.amount
                continue
            }
            paid += p.amount
            val dueInterest = interest.roundToLong()
            val toInterest = minOf(p.amount, dueInterest)
            interestPaid += toInterest
            interest = (dueInterest - toInterest).toDouble()
            principalLeft -= p.amount - toInterest
        }
        if (principalLeft > 0 && end > lastDay) interest += principalLeft * daily * (end - lastDay)
        return DebtBalance(total, principalLeft, interest.roundToLong(), interestPaid, paid)
    }
}

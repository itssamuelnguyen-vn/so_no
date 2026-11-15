package com.samuelcruz.sono

import com.samuelcruz.sono.core.DebtMath
import com.samuelcruz.sono.core.DebtPlan
import com.samuelcruz.sono.core.Direction
import com.samuelcruz.sono.core.HuiCycle
import com.samuelcruz.sono.core.Installments
import com.samuelcruz.sono.core.InterestMethod
import com.samuelcruz.sono.core.Money
import com.samuelcruz.sono.core.Repayment
import com.samuelcruz.sono.data.Debt
import com.samuelcruz.sono.data.DebtState
import com.samuelcruz.sono.data.Payment
import com.samuelcruz.sono.data.totals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DebtMathTest {
    @Test fun noInterest() {
        val b = DebtMath.balance(5_000_000, 0, 0.0, listOf(Repayment(10, 2_000_000)), 100)
        assertEquals(3_000_000, b.left)
        assertEquals(0, b.interestDue)
    }

    @Test fun simpleInterestByTheDayPaysInterestFirst() {
        // 10M at 2%/month: 200k after 30 days.
        assertEquals(200_000, DebtMath.balance(10_000_000, 0, 0.02, emptyList(), 30).interestDue)
        // Pay 1.2M on day 30: 200k interest + 1M principal; the next 30 days run on 9M → 180k.
        val b = DebtMath.balance(10_000_000, 0, 0.02, listOf(Repayment(30, 1_200_000)), 60)
        assertEquals(9_000_000, b.principalLeft)
        assertEquals(180_000, b.interestDue)
        assertEquals(200_000, b.interestPaid)
        assertEquals(9_180_000, b.left)
    }

    @Test fun interestStopsWhenSettled() {
        val b = DebtMath.balance(10_000_000, 0, 0.02, emptyList(), 300, closedDay = 15)
        assertEquals(100_000, b.interestDue)
    }

    @Test fun overpaidGoesNegative() {
        assertEquals(-50_000, DebtMath.balance(100_000, 0, 0.0, listOf(Repayment(1, 150_000)), 5).left)
    }
}

class InstallmentsTest {
    private val jan15 = LocalDate.of(2027, 1, 15)

    @Test fun noInterestSplitsEvenlyAndLastTakesTheRest() {
        val s = Installments.schedule(10_000_000, 3, InterestMethod.NONE, 0.0, HuiCycle.MONTHLY, 30, jan15)
        assertEquals(listOf(3_333_333L, 3_333_333L, 3_333_334L), s.map { it.total })
        assertEquals(listOf(jan15, LocalDate.of(2027, 2, 15), LocalDate.of(2027, 3, 15)), s.map { it.due })
        assertEquals(0, s.last().balanceAfter)
    }

    @Test fun flatInterestIsOnTheOriginalAmount() {
        val s = Installments.schedule(12_000_000, 12, InterestMethod.FLAT, 0.01, HuiCycle.MONTHLY, 30, jan15)
        assertTrue(s.all { it.interest == 120_000L && it.principal == 1_000_000L })
        assertEquals(13_440_000, s.sumOf { it.total })
    }

    @Test fun decliningInterestShrinks() {
        val s = Installments.schedule(12_000_000, 12, InterestMethod.DECLINING, 0.01, HuiCycle.MONTHLY, 30, jan15)
        assertEquals(120_000, s.first().interest)
        assertEquals(10_000, s.last().interest)
        assertEquals(780_000, s.sumOf { it.interest })
        assertEquals(12_000_000, s.sumOf { it.principal })
    }

    @Test fun annuityPaysTheSameEachTime() {
        val s = Installments.schedule(12_000_000, 12, InterestMethod.ANNUITY, 0.01, HuiCycle.MONTHLY, 30, jan15)
        assertEquals(1_066_185, s.first().total)
        assertTrue(s.dropLast(1).all { kotlin.math.abs(it.total - 1_066_185) <= 1 })
        assertTrue(kotlin.math.abs(s.last().total - 1_066_185) <= 12)
        assertEquals(12_000_000, s.sumOf { it.principal })
    }

    @Test fun ratesPerYearAndPerDays() {
        assertEquals(0.01, Installments.monthlyRate(12.0, yearly = true), 1e-12)
        assertEquals(0.015, Installments.monthlyRate(1.5, yearly = false), 1e-12)
        // Góp ngày: 3%/month → 0.1% a day.
        assertEquals(0.001, Installments.periodRate(0.03, HuiCycle.DAYS, 1), 1e-12)
        val daily = Installments.schedule(3_000_000, 30, InterestMethod.FLAT, 0.03, HuiCycle.DAYS, 1, jan15)
        assertEquals(LocalDate.of(2027, 2, 13), daily.last().due)
        assertEquals(3_000, daily.first().interest)
    }

    @Test fun monthlyOnTheLastDays() {
        val s = Installments.schedule(3_000_000, 3, InterestMethod.NONE, 0.0, HuiCycle.MONTHLY, 30, LocalDate.of(2027, 1, 31))
        assertEquals(listOf(LocalDate.of(2027, 1, 31), LocalDate.of(2027, 2, 28), LocalDate.of(2027, 3, 31)), s.map { it.due })
    }

    @Test fun paymentsGoToTheirInstallmentOrFillInOrder() {
        val s = Installments.schedule(3_000_000, 3, InterestMethod.NONE, 0.0, HuiCycle.MONTHLY, 30, jan15)
        val b = Installments.balance(s, listOf(Installments.Pay(2, 1_000_000L, 10), Installments.Pay(null, 1_500_000L, 20)))
        assertEquals(listOf(true, true, false), b.items.map { it.full })
        assertEquals(500_000, b.items[2].paid)
        assertEquals(500_000, b.left)
        assertEquals(3, b.next!!.installment.number)
        assertEquals(1_000_000, b.principalLeft)
        assertEquals(2, b.paidCount)
    }
}

class DebtStateTest {
    private val today = LocalDate.of(2027, 3, 20).toEpochDay()

    private fun plan(direction: Int = Direction.LENT) = Debt(
        id = 1, personId = 1, direction = direction, amount = 3_000_000, startDay = LocalDate.of(2027, 1, 1).toEpochDay(),
        dueDay = LocalDate.of(2027, 2, 15).toEpochDay(), plan = DebtPlan.INSTALLMENTS, term = 3, cycle = HuiCycle.MONTHLY,
    )

    @Test fun installmentsOverdueAndNext() {
        val s = DebtState(plan(), listOf(Payment(debtId = 1, amount = 1_000_000, day = today - 30, installment = 1)), today)
        assertEquals(2_000_000, s.left)
        assertEquals(LocalDate.of(2027, 3, 15).toEpochDay(), s.nextDueDay)
        assertEquals(5, s.overdueDays)
        assertEquals(1_000_000, s.overdueAmount)
        assertEquals(1_000_000, s.nextDueAmount)
        assertEquals(2, s.dueDates().size)
        assertFalse(s.paidOff)
    }

    @Test fun oneTimeDebtWithDueDate() {
        val d = Debt(id = 2, personId = 1, direction = Direction.BORROWED, amount = 500_000, startDay = today - 10, dueDay = today + 3)
        val s = DebtState(d, emptyList(), today)
        assertEquals(500_000, s.left)
        assertEquals(-500_000, s.signedLeft)
        assertEquals(0, s.overdueDays)
        val paid = DebtState(d, listOf(Payment(debtId = 2, amount = 500_000, day = today)), today)
        assertTrue(paid.paidOff)
        assertNull(paid.nextDueDay)
    }

    @Test fun totalsByDirection() {
        val lent = DebtState(plan(), emptyList(), today)
        val owed = DebtState(Debt(id = 3, personId = 2, direction = Direction.BORROWED, amount = 700_000, startDay = today), emptyList(), today)
        val closed = DebtState(Debt(id = 4, personId = 2, direction = Direction.LENT, amount = 9_000_000, startDay = today, closedDay = today), emptyList(), today)
        val t = listOf(lent, owed, closed).totals()
        assertEquals(3_000_000, t.toCollect)
        assertEquals(700_000, t.toPay)
        assertEquals(2_000_000, t.overdueCollect) // installments of 15/2 and 15/3 are late
    }
}

class MoneyTest {
    @Test fun formats() {
        assertEquals("1.250.000đ", Money.format(1_250_000))
        assertEquals("-50.000đ", Money.format(-50_000))
        assertEquals("1,25tr", Money.compact(1_250_000))
        assertEquals("45,5k", Money.compact(45_500))
        assertEquals("1,5 triệu đồng", Money.words(1_500_000))
        assertEquals("250 nghìn đồng", Money.words(250_000))
        assertEquals("2 tỷ đồng", Money.words(2_000_000_000))
        assertEquals(1_500_000L, Money.parse("1.500.000"))
        assertNull(Money.parse(""))
    }
}

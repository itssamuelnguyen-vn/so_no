package com.samuelcruz.sono

import com.samuelcruz.sono.core.DebtMath
import com.samuelcruz.sono.core.DebtPlan
import com.samuelcruz.sono.core.Direction
import com.samuelcruz.sono.core.Hui
import com.samuelcruz.sono.core.HuiBids
import com.samuelcruz.sono.core.HuiCycle
import com.samuelcruz.sono.core.HuiRoundInput
import com.samuelcruz.sono.core.HuiRules
import com.samuelcruz.sono.core.Installments
import com.samuelcruz.sono.core.InterestMethod
import com.samuelcruz.sono.core.PenaltyMode
import com.samuelcruz.sono.core.Repayment
import com.samuelcruz.sono.core.ThaoMode
import com.samuelcruz.sono.core.VnNumber
import com.samuelcruz.sono.data.Debt
import com.samuelcruz.sono.data.DebtState
import com.samuelcruz.sono.data.DueOverrides
import com.samuelcruz.sono.data.Payment
import com.samuelcruz.sono.data.PaymentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class VnNumberTest {
    @Test fun readsAmountsTheFormalWay() {
        assertEquals("Không đồng", VnNumber.words(0))
        assertEquals("Mười lăm đồng", VnNumber.words(15))
        assertEquals("Hai mươi mốt đồng", VnNumber.words(21))
        assertEquals("Hai mươi tư đồng", VnNumber.words(24))
        assertEquals("Một trăm linh năm đồng", VnNumber.words(105))
        assertEquals("Một trăm mười đồng", VnNumber.words(110))
        assertEquals("Ba nghìn năm trăm đồng", VnNumber.words(3_500))
        assertEquals("Một triệu năm trăm nghìn đồng", VnNumber.words(1_500_000))
        assertEquals("Một triệu không trăm linh năm nghìn đồng", VnNumber.words(1_005_000))
        assertEquals("Một triệu không trăm mười lăm đồng", VnNumber.words(1_000_015))
        assertEquals("Hai tỷ đồng", VnNumber.words(2_000_000_000))
        assertEquals("Mười hai tỷ ba trăm bốn mươi lăm triệu đồng", VnNumber.words(12_345_000_000))
    }
}

class TopUpAndPenaltyTest {
    private val jan15 = LocalDate.of(2027, 1, 15)

    @Test fun topUpAddsPrincipalAndInterestFollows() {
        // 10M at 2%/month, 10M more on day 30: day 60 has 200k + 400k interest.
        val b = DebtMath.balance(10_000_000, 0, 0.02, listOf(Repayment(30, 10_000_000, topUp = true)), 60)
        assertEquals(20_000_000, b.principal)
        assertEquals(600_000, b.interestDue)
        assertEquals(0, b.paid)
    }

    @Test fun debtStateCountsTopUps() {
        val d = Debt(id = 1, personId = 1, direction = Direction.LENT, amount = 1_000_000, startDay = 0)
        val s = DebtState(d, listOf(Payment(debtId = 1, amount = 500_000, day = 5, kind = PaymentKind.TOP_UP), Payment(debtId = 1, amount = 300_000, day = 6)), 10)
        assertEquals(1_500_000, s.principal)
        assertEquals(1_200_000, s.left)
        assertEquals(300_000, s.paid)
    }

    @Test fun overridesMoveOnlyThatInstallment() {
        val moved = LocalDate.of(2027, 2, 28)
        val s = Installments.schedule(3_000_000, 3, InterestMethod.NONE, 0.0, HuiCycle.MONTHLY, 30, jan15, mapOf(2 to moved))
        assertEquals(listOf(jan15, moved, LocalDate.of(2027, 3, 15)), s.map { it.due })
        assertEquals(mapOf(2 to moved, 3 to jan15), DueOverrides.parse(DueOverrides.format(mapOf(3 to jan15, 2 to moved))))
        assertTrue(DueOverrides.parse("").isEmpty())
    }

    @Test fun fullDayIsWhenTheLastPieceArrived() {
        val s = Installments.schedule(2_000_000, 2, InterestMethod.NONE, 0.0, HuiCycle.MONTHLY, 30, jan15)
        val b = Installments.balance(s, listOf(Installments.Pay(1, 400_000, 100), Installments.Pay(1, 600_000, 105), Installments.Pay(null, 500_000, 110)))
        assertEquals(105L, b.items[0].fullDay)
        assertEquals(null, b.items[1].fullDay)
        assertEquals(500_000, b.items[1].paid)
    }

    @Test fun penaltyModes() {
        assertEquals(50_000, PenaltyMode.fee(PenaltyMode.PER_DAY, 10_000.0, 1_000_000, due = 100, paidDay = 105, today = 200))
        assertEquals(0, PenaltyMode.fee(PenaltyMode.PER_DAY, 10_000.0, 1_000_000, due = 100, paidDay = 100, today = 200))
        assertEquals(3_000, PenaltyMode.fee(PenaltyMode.PERCENT_PER_DAY, 0.1, 1_000_000, due = 100, paidDay = null, today = 103))
        assertEquals(200_000, PenaltyMode.fee(PenaltyMode.ONCE, 200_000.0, 1_000_000, due = 100, paidDay = 140, today = 200))
    }

    @Test fun latePenaltyAddsToWhatIsLeft() {
        val first = jan15.toEpochDay()
        val d = Debt(
            id = 1, personId = 1, direction = Direction.LENT, amount = 2_000_000, startDay = first - 30, dueDay = first,
            plan = DebtPlan.INSTALLMENTS, term = 2, penaltyMode = PenaltyMode.PER_DAY, penaltyValue = 10_000.0,
        )
        // Installment 1 paid 4 days late, installment 2 (due 15/2) not paid, today = 20/2 (5 days late).
        val today = LocalDate.of(2027, 2, 20).toEpochDay()
        val s = DebtState(d, listOf(Payment(debtId = 1, amount = 1_000_000, day = first + 4, installment = 1)), today)
        assertEquals(90_000, s.penalty)
        assertEquals(1_090_000, s.left)
        assertFalse(s.paidOff)
    }
}

class HuiV2Test {
    @Test fun fixedInterestActsLikeAFixedBid() {
        val r = HuiRules(1_000_000, 10, ownerFirst = true, bidding = true, thaoMode = ThaoMode.NONE, fixedBid = 100_000)
        val c = Hui.round(r, listOf(HuiRoundInput(1, 1, 0)), 2, 2, 999_999)
        assertEquals(100_000, c.bid)
        assertEquals(900_000, c.livePays)
        assertFalse(r.hasBid(2))
        assertEquals(0, Hui.round(r, (1..9).map { HuiRoundInput(it, it, 100_000) }, 10, 10, 0).bid)
    }

    @Test fun bidsTextAndTies() {
        val b = HuiBids.parse("3=150000;5=120000;7=150000")
        assertEquals(mapOf(3 to 150_000L, 5 to 120_000L, 7 to 150_000L), b)
        assertEquals(listOf(3, 7), HuiBids.top(b))
        assertEquals("3=150000;5=120000", HuiBids.format(mapOf(5 to 120_000L, 3 to 150_000L, 9 to 0L)))
        assertTrue(HuiBids.top(emptyMap()).isEmpty())
    }
}

package com.samuelcruz.sono

import com.samuelcruz.sono.core.Hui
import com.samuelcruz.sono.core.HuiCycle
import com.samuelcruz.sono.core.HuiRoundInput
import com.samuelcruz.sono.core.HuiRules
import com.samuelcruz.sono.core.HuiSchedule
import com.samuelcruz.sono.core.ThaoMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class HuiTest {
    /** Hụi tháng 10 chân, 1 triệu, thảo 200 nghìn, chủ hụi hốt kỳ đầu (the common Southern dây). */
    private val rules = HuiRules(amount = 1_000_000, slots = 10, ownerFirst = true, bidding = true, thaoMode = ThaoMode.FIXED, thaoValue = 200_000)

    /** Rounds 1..n: owner first, then slot k wins round k bidding [bid]. */
    private fun held(n: Int, bid: Long = 150_000) = (1..n).map { HuiRoundInput(it, it, if (it == 1) 0 else bid) }

    @Test fun ownerTakesRoundOneInFullWithoutFee() {
        val c = Hui.round(rules, emptyList(), 1, 1, 500_000)
        assertEquals(0, c.bid) // no bidding in round 1
        assertEquals(9, c.liveCount)
        assertEquals(9_000_000, c.gross)
        assertEquals(0, c.thao)
        assertEquals(9_000_000, c.net)
    }

    @Test fun liveSharesPayLessTheBidDeadSharesPayInFull() {
        // Round 3: chân 1 and 2 are dead; chân 3 bids 150k.
        val c = Hui.round(rules, held(2), 3, 3, 150_000)
        assertEquals(7, c.liveCount)
        assertEquals(2, c.deadCount)
        assertEquals(850_000, c.livePays)
        assertEquals(1_000_000, c.deadPays)
        assertEquals(850_000L, c.dues[4])
        assertEquals(1_000_000L, c.dues[1])
        assertNull(c.dues[3]) // the winner pays nothing
        assertEquals(7_950_000, c.gross)
        assertEquals(200_000, c.thao)
        assertEquals(7_750_000, c.net)
    }

    @Test fun lastRoundHasNoBidAndEveryonePaysInFull() {
        val c = Hui.round(rules, held(9), 10, 10, 300_000)
        assertEquals(0, c.bid)
        assertEquals(9, c.deadCount)
        assertEquals(8_800_000, c.net)
        assertEquals(9_000_000, Hui.round(rules.copy(thaoLast = false), held(9), 10, 10, 0).net)
    }

    @Test fun feeAsPercentOfFaceValue() {
        val r = rules.copy(thaoMode = ThaoMode.PERCENT, thaoValue = 100) // 10%
        assertEquals(100_000, Hui.thao(r, 5))
        assertEquals(0, Hui.thao(r, 1))
        assertEquals(0, Hui.thao(rules.copy(thaoMode = ThaoMode.NONE), 5))
    }

    @Test fun withoutOwnerFirstRoundOneIsBidAndPaysFee() {
        val r = rules.copy(ownerFirst = false)
        val c = Hui.round(r, emptyList(), 1, 4, 100_000)
        assertEquals(9 * 900_000L, c.gross)
        assertEquals(200_000, c.thao)
        assertNull(Hui.forcedWinner(r, emptyList(), 1, null))
    }

    @Test fun noInterestDayNeverBids() {
        val r = rules.copy(bidding = false, thaoMode = ThaoMode.NONE)
        val c = Hui.round(r, held(4, 0), 5, 5, 200_000)
        assertEquals(0, c.bid)
        assertEquals(9_000_000, c.net)
        assertEquals(0, Hui.maxBid(r, held(4, 0), 5))
    }

    @Test fun forcedWinners() {
        assertEquals(1, Hui.forcedWinner(rules, emptyList(), 1, 1))
        assertEquals(10, Hui.forcedWinner(rules, held(9), 10, 1))
        assertNull(Hui.forcedWinner(rules, held(3), 4, 1))
        assertEquals((4..10).toList(), Hui.live(rules, held(3), 4))
    }

    @Test fun maxBidKeepsThePotAboveTheFee() {
        assertEquals(999_999, Hui.maxBid(rules, held(2), 3))
        assertEquals(0, Hui.maxBid(rules, emptyList(), 1))
        // Tiny dây where the fee is big: 2 live of 3, 1 dead, fee 1.5M → net = (1M − b) + 1M − 1.5M > 0 → b < 500k.
        val small = HuiRules(1_000_000, 3, ownerFirst = true, thaoMode = ThaoMode.FIXED, thaoValue = 1_500_000)
        assertEquals(499_999, Hui.maxBid(small, listOf(HuiRoundInput(1, 1, 0)), 2))
    }

    @Test fun moneyIsConservedOverTheWholeDay() {
        val all = held(10, 120_000)
        val calcs = Hui.rounds(rules, all)
        val fees = calcs.sumOf { it.thao }
        val total = (1..10).sumOf { s -> Hui.flows(rules, all, s).sum() }
        assertEquals(-fees, total)
        assertEquals(1_800_000, fees) // 200k in rounds 2..10
    }

    @Test fun earlyCollectorsPayInterestLateOnesEarn() {
        val all = held(10, 150_000)
        val early = Hui.summary(rules, all, 2, 30.44)
        val late = Hui.summary(rules, all, 10, 30.44)
        assertTrue(early.result!! < 0)
        assertTrue(late.result!! > 0)
        assertTrue(early.monthlyRate!! < 0)
        assertTrue(late.monthlyRate!! > 0)
        // A live share has no known result yet.
        val live = Hui.summary(rules, held(3), 7, 30.44)
        assertNull(live.wonRound)
        assertNull(live.result)
    }

    @Test fun winnerRateAndLegalCap() {
        // Bidding 300k of 1M in round 2 is far above 20%/year for the winner.
        val r = Hui.winnerMonthlyRate(rules, held(1), 2, 2, 300_000, 30.44)
        assertNotNull(r)
        assertTrue(Hui.exceedsLegal(r))
        // Bidding nothing, without fee: no interest at all.
        val free = HuiRules(1_000_000, 10, ownerFirst = true, thaoMode = ThaoMode.NONE)
        val r0 = Hui.winnerMonthlyRate(free, held(1, 0), 2, 2, 0, 30.44)
        assertTrue(r0 == null || kotlin.math.abs(r0) < 1e-6)
    }

    @Test fun irrOfASimpleLoan() {
        // Get 1000 now, pay 1100 one period later: 10% per period.
        assertEquals(0.10, Hui.periodRate(listOf(1000, -1100))!!, 1e-6)
        assertNull(Hui.periodRate(listOf(-5, -5)))
    }

    @Test fun solarMonthlyScheduleClampsShortMonths() {
        val d = HuiSchedule.dates(HuiCycle.MONTHLY, 30, LocalDate.of(2027, 1, 31), 3)
        assertEquals(listOf(LocalDate.of(2027, 1, 31), LocalDate.of(2027, 2, 28), LocalDate.of(2027, 3, 31)), d)
        assertEquals(LocalDate.of(2026, 11, 29), HuiSchedule.date(HuiCycle.DAYS, 7, LocalDate.of(2026, 11, 15), 3))
    }

    @Test fun lunarMonthlyScheduleKeepsTheLunarDay() {
        // 2026-11-24 is the 15th of a lunar month; every round must also be on a 15th, a lunar month apart.
        val start = LocalDate.of(2026, 1, 1).let { d -> generateSequence(d) { it.plusDays(1) }.first { HuiSchedule.lunar(it).day == 15 } }
        val dates = HuiSchedule.dates(HuiCycle.LUNAR_MONTHLY, 30, start, 14)
        dates.forEach { assertEquals(15, HuiSchedule.lunar(it).day) }
        dates.zipWithNext().forEach { (a, b) ->
            val gap = java.time.temporal.ChronoUnit.DAYS.between(a, b)
            assertTrue("gap $gap", gap in 29..30)
        }
        assertEquals(dates[5], HuiSchedule.date(HuiCycle.LUNAR_MONTHLY, 30, start, 6))
    }
}

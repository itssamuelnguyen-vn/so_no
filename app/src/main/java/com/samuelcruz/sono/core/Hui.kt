package com.samuelcruz.sono.core

import java.time.LocalDate
import kotlin.math.abs

/** How often a dây hụi is opened ("khui"). */
object HuiCycle {
    /** Every [HuiRules] `everyDays` days: hụi ngày (1), hụi tuần (7), hụi nửa tháng (15)… */
    const val DAYS = 0
    /** Same day of the solar month as the first round (31 → the month's last day in short months). */
    const val MONTHLY = 1
    /** Same day of the lunar month as the first round (leap months count as months, as people still pay). */
    const val LUNAR_MONTHLY = 2
}

/** How the organizer's fee (tiền thảo) is worked out. */
object ThaoMode {
    const val NONE = 0
    /** A fixed amount in đồng each round. */
    const val FIXED = 1
    /** A share of the face value, in tenths of a percent (100 = 10%). */
    const val PERCENT = 2
}

/**
 * The rules of one dây hụi, chosen before it starts. Southern style ("hụi bỏ thăm"):
 * - N chân (shares), N rounds, one share collects ("hốt") each round.
 * - Each round the live shares (not collected yet, "hụi sống") who want the money bid ("kêu thăm"); the highest bid
 *   wins. Every other live share pays [amount] − bid, every dead share ("hụi chết", already collected) pays the full
 *   [amount]. The winner gets the sum, minus the organizer's fee (thảo).
 * - [ownerFirst]: the organizer (chủ hụi) collects round 1, with no bid and no fee; everybody pays in full.
 * - The last round has a single live share left, so there is no bid.
 * - [bidding] false = "hụi không lãi": nobody bids, shares collect in an agreed order and always pay in full.
 * - [fixedBid] > 0 = "hụi lãi cố định": nobody bids either, but every winner gives up this fixed amount per live
 *   share, exactly as if they had bid it.
 */
data class HuiRules(
    val amount: Long,
    val slots: Int,
    val ownerFirst: Boolean = true,
    val bidding: Boolean = true,
    val thaoMode: Int = ThaoMode.FIXED,
    /** Đồng for [ThaoMode.FIXED], tenths of a percent of [amount] for [ThaoMode.PERCENT]. */
    val thaoValue: Long = 0,
    /** Whether the last round also pays the fee. */
    val thaoLast: Boolean = true,
    val fixedBid: Long = 0,
) {
    /** Whether the winner types a bid in round [number]. */
    fun hasBid(number: Int): Boolean = !fixedRound(number) && fixedBid <= 0

    /** True when round [number] is collected without bidding. */
    fun fixedRound(number: Int): Boolean = !bidding || (ownerFirst && number == 1) || number == slots
}

/** One round already held: who collected and what they bid. Slots are numbered 1..N. */
data class HuiRoundInput(val number: Int, val winner: Int, val bid: Long)

/** Everything about one round's money. [dues] maps each paying slot to what it pays (the winner pays nothing). */
data class HuiRoundCalc(
    val number: Int,
    val winner: Int,
    val bid: Long,
    val dues: Map<Int, Long>,
    val liveCount: Int,
    val deadCount: Int,
    val livePays: Long,
    val deadPays: Long,
    val gross: Long,
    val thao: Long,
) {
    /** What the winner takes home. */
    val net: Long get() = gross - thao
}

/** What one share pays and gets over the whole dây. */
data class HuiSlotSummary(
    val slot: Int,
    /** Round it collected, or null while it is still live. */
    val wonRound: Int?,
    val paid: Long,
    val received: Long,
    /** Still to pay in later rounds when known (a dead share pays the full amount each round), else null. */
    val toPay: Long?,
    /** Per-month rate that makes payments and receipts even (negative = a cost), null when not known yet. */
    val monthlyRate: Double?,
) {
    /** received − (paid + toPay): positive = profit (late collectors), negative = cost (early collectors). */
    val result: Long? get() = toPay?.let { received - paid - it }
}

object Hui {
    /** Thảo for round [number]; nothing on the organizer's own first round. */
    fun thao(rules: HuiRules, number: Int): Long = when {
        rules.ownerFirst && number == 1 -> 0
        number == rules.slots && !rules.thaoLast -> 0
        rules.thaoMode == ThaoMode.FIXED -> rules.thaoValue
        rules.thaoMode == ThaoMode.PERCENT -> rules.amount * rules.thaoValue / 1000
        else -> 0
    }

    /** Slots that have collected before round [number]. */
    fun dead(rounds: List<HuiRoundInput>, number: Int): Set<Int> =
        rounds.filter { it.number < number }.map { it.winner }.toSet()

    /** Slots that can still collect in round [number]. */
    fun live(rules: HuiRules, rounds: List<HuiRoundInput>, number: Int): List<Int> {
        val dead = dead(rounds, number)
        return (1..rules.slots).filter { it !in dead }
    }

    /**
     * The winner forced by the rules, if any: the organizer's share in round 1 ([ownerSlot]) or the only live share
     * in the last round.
     */
    fun forcedWinner(rules: HuiRules, rounds: List<HuiRoundInput>, number: Int, ownerSlot: Int?): Int? {
        if (rules.ownerFirst && number == 1 && ownerSlot != null) return ownerSlot
        return live(rules, rounds, number).singleOrNull()
    }

    /** Largest bid allowed in round [number]: the winner must still get more than the fee. */
    fun maxBid(rules: HuiRules, rounds: List<HuiRoundInput>, number: Int): Long {
        if (!rules.hasBid(number)) return if (rules.fixedRound(number)) 0 else rules.fixedBid
        val live = live(rules, rounds, number).size
        if (live <= 1) return 0
        // net = (live−1)(amount−bid) + dead·amount − thao > 0, and bid < amount.
        val dead = rules.slots - live
        val byNet = ((live - 1) * rules.amount + dead * rules.amount - thao(rules, number) - 1) / (live - 1)
        return minOf(rules.amount - 1, byNet).coerceAtLeast(0)
    }

    /** The money of round [number] won by [winner] with [bid]; [past] are the rounds before it. */
    fun round(rules: HuiRules, past: List<HuiRoundInput>, number: Int, winner: Int, bid: Long): HuiRoundCalc {
        val b = when {
            rules.fixedRound(number) -> 0
            rules.fixedBid > 0 -> rules.fixedBid
            else -> bid
        }
        val dead = dead(past, number)
        val dues = LinkedHashMap<Int, Long>()
        var live = 0
        var deadCount = 0
        for (s in 1..rules.slots) {
            if (s == winner) continue
            if (s in dead) {
                dues[s] = rules.amount
                deadCount++
            } else {
                dues[s] = rules.amount - b
                live++
            }
        }
        val gross = dues.values.sum()
        return HuiRoundCalc(
            number = number, winner = winner, bid = b, dues = dues,
            liveCount = live, deadCount = deadCount, livePays = rules.amount - b, deadPays = rules.amount,
            gross = gross, thao = thao(rules, number),
        )
    }

    /** All held rounds, in order. */
    fun rounds(rules: HuiRules, held: List<HuiRoundInput>): List<HuiRoundCalc> {
        val sorted = held.sortedBy { it.number }
        return sorted.map { r -> round(rules, sorted, r.number, r.winner, r.bid) }
    }

    /**
     * Cash flow of [slot] per round (index 0 = round 1): negative = paid, positive = received. Rounds not held yet
     * are filled in only when certain (a dead share pays the full amount); otherwise the list stops there.
     */
    fun flows(rules: HuiRules, held: List<HuiRoundInput>, slot: Int): List<Long> {
        val calcs = rounds(rules, held).associateBy { it.number }
        val out = ArrayList<Long>()
        var won = false
        for (n in 1..rules.slots) {
            val c = calcs[n]
            when {
                c != null && c.winner == slot -> { out += c.net; won = true }
                c != null -> out += -(c.dues[slot] ?: 0)
                won -> out += -rules.amount
                else -> return out
            }
        }
        return out
    }

    fun summary(rules: HuiRules, held: List<HuiRoundInput>, slot: Int, periodDays: Double): HuiSlotSummary {
        val calcs = rounds(rules, held)
        val wonRound = calcs.firstOrNull { it.winner == slot }?.number
        val paid = calcs.sumOf { it.dues[slot] ?: 0 }
        val received = calcs.firstOrNull { it.winner == slot }?.net ?: 0
        val toPay = if (wonRound != null) (rules.slots - (calcs.maxOfOrNull { it.number } ?: 0)).toLong() * rules.amount else null
        val flows = if (wonRound != null) flows(rules, held, slot) else emptyList()
        val rate = if (flows.size == rules.slots) signedMonthlyRate(flows, periodDays) else null
        return HuiSlotSummary(slot, wonRound, paid, received, toPay, rate)
    }

    /**
     * Per-month rate the winner of round [number] effectively pays (negative, early rounds) or earns (positive, late
     * rounds), from their whole cash flow; null when it has no clear answer. Compared with the legal cap (20% a year).
     */
    fun winnerMonthlyRate(rules: HuiRules, past: List<HuiRoundInput>, number: Int, winner: Int, bid: Long, periodDays: Double): Double? {
        val held = past.filter { it.number < number } + HuiRoundInput(number, winner, bid)
        val flows = flows(rules, held, winner)
        if (flows.size != rules.slots) return null
        return signedMonthlyRate(flows, periodDays)
    }

    /** The IRR per month, negative when the share pays more than it gets (a cost), positive when it earns. */
    private fun signedMonthlyRate(flows: List<Long>, periodDays: Double): Double? {
        val r = periodRate(flows) ?: return null
        val sign = if (flows.sum() < 0) -1 else 1
        return sign * kotlin.math.abs(r) * 30.0 / periodDays
    }

    /**
     * The rate r per period that makes the cash flow's present value zero (an IRR), looking for the root closest to
     * zero in −50%..+100% per period. Null when there is none (e.g. no money changes hands).
     */
    fun periodRate(flows: List<Long>): Double? {
        if (flows.none { it > 0 } || flows.none { it < 0 }) return null
        fun npv(r: Double): Double {
            var sum = 0.0
            var d = 1.0
            for (f in flows) {
                sum += f / d
                d *= 1 + r
            }
            return sum
        }
        val v0 = npv(0.0)
        if (v0 == 0.0) return 0.0
        // Step outward from 0 in both directions and take the first sign change.
        val step = 0.0005
        var k = 1
        while (k * step <= 1.0) {
            for (dir in intArrayOf(1, -1)) {
                val hi = dir * k * step
                if (hi <= -0.5) continue
                val lo = dir * (k - 1) * step
                val vHi = npv(hi)
                val vLo = npv(lo)
                if (vLo == 0.0) return lo
                if ((vLo < 0) != (vHi < 0)) return bisect(::npv, lo, hi)
            }
            k++
        }
        return null
    }

    private fun bisect(f: (Double) -> Double, a0: Double, b0: Double): Double {
        var a = a0
        var b = b0
        val fa0 = f(a)
        repeat(60) {
            val m = (a + b) / 2
            val fm = f(m)
            if ((fm < 0) == (fa0 < 0)) a = m else b = m
        }
        return (a + b) / 2
    }

    /** Days between rounds on average, for turning a per-round rate into a per-month one. */
    fun periodDays(cycle: Int, everyDays: Int): Double = when (cycle) {
        HuiCycle.DAYS -> everyDays.coerceAtLeast(1).toDouble()
        HuiCycle.LUNAR_MONTHLY -> 29.53
        else -> 30.44
    }

    /** Above this per-month rate the bid breaks the 20%-a-year cap of the Civil Code (Decree 19/2019 on hụi). */
    const val LEGAL_MONTHLY = 0.20 / 12

    fun exceedsLegal(monthlyRate: Double?): Boolean = monthlyRate != null && abs(monthlyRate) > LEGAL_MONTHLY + 1e-9

    /** Per-month rate to a simple yearly one, the way people quote it ("2%/tháng = 24%/năm"). */
    fun yearly(monthlyRate: Double): Double = monthlyRate * 12
}

/** When each round falls. */
object HuiSchedule {
    fun date(cycle: Int, everyDays: Int, start: LocalDate, number: Int): LocalDate = when (cycle) {
        HuiCycle.DAYS -> start.plusDays((number - 1).toLong() * everyDays.coerceAtLeast(1))
        HuiCycle.MONTHLY -> start.plusMonths((number - 1).toLong())
        else -> lunarMonthly(start, number)
    }

    /** All round dates, round 1 first. */
    fun dates(cycle: Int, everyDays: Int, start: LocalDate, count: Int): List<LocalDate> {
        if (cycle != HuiCycle.LUNAR_MONTHLY) return (1..count).map { date(cycle, everyDays, start, it) }
        val out = ArrayList<LocalDate>(count)
        val day = lunar(start).day
        var d = start
        repeat(count) { i ->
            if (i > 0) d = nextLunarMonthDay(d, day)
            out += d
        }
        return out
    }

    private fun lunarMonthly(start: LocalDate, number: Int): LocalDate {
        val day = lunar(start).day
        var d = start
        repeat(number - 1) { d = nextLunarMonthDay(d, day) }
        return d
    }

    /** Lunar day [day] of the lunar month after the one [from] is in (the month's last day when it is shorter). */
    private fun nextLunarMonthDay(from: LocalDate, day: Int): LocalDate {
        val l = lunar(from)
        val firstOfNext = from.plusDays((l.monthLength - l.day + 1).toLong())
        val next = lunar(firstOfNext)
        return firstOfNext.plusDays((minOf(day, next.monthLength) - 1).toLong())
    }

    fun lunar(d: LocalDate): LunarDate = LunarCalendar.solarToLunar(d.dayOfMonth, d.monthValue, d.year)
}

/** Every bid of a round, kept as text "slot=amount;…" on the round. */
object HuiBids {
    fun parse(text: String): Map<Int, Long> = text.split(';').mapNotNull { part ->
        val (s, a) = part.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
        (s.toIntOrNull() ?: return@mapNotNull null) to (a.toLongOrNull() ?: return@mapNotNull null)
    }.toMap()

    fun format(bids: Map<Int, Long>): String =
        bids.entries.filter { it.value > 0 }.sortedBy { it.key }.joinToString(";") { "${it.key}=${it.value}" }

    /** The slots with the highest bid (more than one = a tie, settled by drawing lots). */
    fun top(bids: Map<Int, Long>): List<Int> {
        val max = bids.values.filter { it > 0 }.maxOrNull() ?: return emptyList()
        return bids.filter { it.value == max }.keys.sorted()
    }
}

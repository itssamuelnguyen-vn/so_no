package com.samuelcruz.sono.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.samuelcruz.sono.core.Direction
import com.samuelcruz.sono.core.Hui
import com.samuelcruz.sono.core.Money
import com.samuelcruz.sono.data.DebtState
import com.samuelcruz.sono.data.HuiRole
import java.time.LocalDate
import java.time.YearMonth

/** Money expected in one month: from debts to me, to my creditors, into my hụi shares and out of them. */
class MonthFlow(val month: YearMonth) {
    var collect = 0L
    var pay = 0L
    var huiPay = 0L
    var huiGet = 0L
    /** Hụi dues of live shares are the most they could be (the bid is not known yet). */
    var huiPayIsMax = false
    val totalIn get() = collect + huiGet
    val totalOut get() = pay + huiPay
}

/** The next [months] months, the current one first (late money counts in the current month). */
fun cashFlow(states: List<DebtState>, hui: List<HuiView>, today: LocalDate, months: Int = 6): List<MonthFlow> {
    val first = YearMonth.from(today)
    val flows = (0 until months).map { MonthFlow(first.plusMonths(it.toLong())) }
    fun slot(day: LocalDate): MonthFlow? {
        val m = YearMonth.from(day)
        if (m < first) return flows[0]
        return flows.firstOrNull { it.month == m }
    }
    for (s in states) {
        for ((day, amount) in s.dueDates()) {
            val f = slot(LocalDate.ofEpochDay(day)) ?: continue
            if (s.debt.direction == Direction.LENT) f.collect += amount else f.pay += amount
        }
    }
    for (v in hui) {
        if (v.mine.isEmpty() || v.finished) continue
        val rules = v.rules
        for (n in (v.held + 1)..v.group.slots) {
            val f = slot(v.dates[n - 1]) ?: continue
            val live = Hui.live(rules, v.inputs, n)
            for (s in v.mine) {
                val won = v.wonRound(s.number) != null
                if (won) f.huiPay += rules.amount
                else if (live.size == 1 && live.first() == s.number) {
                    // The last live share collects for sure; everyone else is dead and pays in full.
                    f.huiGet += Hui.round(rules, v.inputs, n, s.number, 0).net
                } else {
                    f.huiPay += rules.amount - if (rules.fixedBid > 0 && !rules.fixedRound(n)) rules.fixedBid else 0
                    if (rules.hasBid(n)) f.huiPayIsMax = true
                }
            }
        }
    }
    return flows
}

@Composable
fun StatsScreen(pad: PaddingValues) {
    val (people, states) = rememberDebtStates()
    val hui = rememberHuiViews()
    val today = rememberToday()
    val money = LocalMoneyColors.current
    val todayDate = LocalDate.ofEpochDay(today)
    var year by rememberSaveable { mutableIntStateOf(todayDate.year) }
    val flows = remember(states, hui, today) { cashFlow(states, hui, todayDate) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = pad.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Thống kê", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 4.dp))

        SectionHeader("Dòng tiền 6 tháng tới")
        Card {
            Column(Modifier.padding(16.dp)) {
                Bars(flows, money.collect, money.pay)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Dot(money.collect, "Tiền vào")
                    Dot(money.pay, "Tiền ra")
                }
            }
            flows.forEach { f ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Row {
                        Text("Tháng ${f.month.monthValue}/${f.month.year}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        val net = f.totalIn - f.totalOut
                        Text((if (net >= 0) "+" else "") + Money.format(net), style = MaterialTheme.typography.titleSmall, color = if (net >= 0) money.collect else money.pay)
                    }
                    Text(
                        listOfNotNull(
                            "thu nợ ${Money.compact(f.collect)}".takeIf { f.collect > 0 },
                            "trả nợ ${Money.compact(f.pay)}".takeIf { f.pay > 0 },
                            ("đóng hụi " + (if (f.huiPayIsMax) "tối đa " else "") + Money.compact(f.huiPay)).takeIf { f.huiPay > 0 },
                            "hốt hụi ${Money.compact(f.huiGet)}".takeIf { f.huiGet > 0 },
                        ).joinToString(" · ").ifEmpty { "không có khoản nào" },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                "Tháng này gồm cả các khoản đã quá hạn. Hụi sống chưa biết tiền thăm nên tính tối đa (đủ mệnh giá).",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionHeader("Năm $year")
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { year-- }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Năm trước") }
            IconButton(onClick = { year++ }, enabled = year < todayDate.year) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Năm sau") }
        }
        val y = remember(states, hui, year) { yearStats(states, hui, year) }
        Card {
            Spacer(Modifier.height(6.dp))
            InfoRow("Cho mượn mới", Money.format(y.lent), money.collect)
            InfoRow("Đã thu về", Money.format(y.collected), money.collect)
            InfoRow("Đi mượn mới", Money.format(y.borrowed), money.pay)
            InfoRow("Đã trả", Money.format(y.repaid), money.pay)
            if (y.thao > 0) InfoRow("Tiền thảo chủ hụi thu", Money.format(y.thao), money.collect)
            if (y.huiPaid > 0) InfoRow("Đã đóng hụi (chân của tôi)", Money.format(y.huiPaid), money.pay)
            if (y.huiGot > 0) InfoRow("Đã hốt hụi", Money.format(y.huiGot), money.collect)
            Spacer(Modifier.height(6.dp))
        }

        SectionHeader("Từ trước tới nay")
        val all = remember(states) { allTime(states) }
        Card {
            Spacer(Modifier.height(6.dp))
            InfoRow("Lãi và phạt đã thu", Money.format(all.first), money.collect, bold = true)
            InfoRow("Lãi và phạt đã trả", Money.format(all.second), money.pay, bold = true)
            Spacer(Modifier.height(6.dp))
        }

        val names = people.associate { it.id to it.name }
        val top = states.filter { !it.debt.closed && it.debt.direction == Direction.LENT && it.left > 0 }
            .groupBy { it.debt.personId }.mapValues { (_, l) -> l.sumOf { it.left } }
            .entries.sortedByDescending { it.value }.take(5)
        if (top.isNotEmpty()) {
            SectionHeader("Nợ bạn nhiều nhất")
            Card {
                Spacer(Modifier.height(6.dp))
                top.forEach { (id, amount) -> InfoRow(names[id].orEmpty(), Money.format(amount), money.collect) }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

class YearStats(val lent: Long, val borrowed: Long, val collected: Long, val repaid: Long, val thao: Long, val huiPaid: Long, val huiGot: Long)

fun yearStats(states: List<DebtState>, hui: List<HuiView>, year: Int): YearStats {
    fun inYear(day: Long) = LocalDate.ofEpochDay(day).year == year
    var lent = 0L; var borrowed = 0L; var collected = 0L; var repaid = 0L
    for (s in states) {
        val l = s.debt.direction == Direction.LENT
        var newMoney = if (inYear(s.debt.startDay)) s.debt.amount else 0L
        newMoney += s.topUps.filter { inYear(it.day) }.sumOf { it.amount }
        val back = s.payments.filter { !it.isTopUp && inYear(it.day) }.sumOf { it.amount }
        if (l) { lent += newMoney; collected += back } else { borrowed += newMoney; repaid += back }
    }
    var thao = 0L; var huiPaid = 0L; var huiGot = 0L
    for (v in hui) {
        for (r in v.rounds.filter { inYear(it.day) }) {
            val c = v.calcs.firstOrNull { it.number == r.number } ?: continue
            if (v.group.role == HuiRole.OWNER) thao += c.thao
            for (m in v.mine) {
                if (m.number == c.winner) huiGot += c.net else huiPaid += c.dues[m.number] ?: 0
            }
        }
    }
    return YearStats(lent, borrowed, collected, repaid, thao, huiPaid, huiGot)
}

/** (interest + fees received on money I lent, interest + fees paid on money I borrowed). */
fun allTime(states: List<DebtState>): Pair<Long, Long> {
    var got = 0L; var paid = 0L
    for (s in states) if (s.debt.direction == Direction.LENT) got += s.interestPaid else paid += s.interestPaid
    return got to paid
}

/** Two bars per month: money in, money out. */
@Composable
private fun Bars(flows: List<MonthFlow>, inColor: Color, outColor: Color) {
    val max = flows.maxOfOrNull { maxOf(it.totalIn, it.totalOut) }?.coerceAtLeast(1) ?: 1
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Column {
        Canvas(Modifier.fillMaxWidth().height(140.dp)) {
            val slot = size.width / flows.size
            val bar = slot * 0.28f
            flows.forEachIndexed { i, f ->
                val x = i * slot + slot / 2
                drawRoundRect(track, Offset(x - bar - 2, 0f), Size(bar * 2 + 4, size.height), CornerRadius(8f))
                val hIn = size.height * f.totalIn / max
                val hOut = size.height * f.totalOut / max
                drawRoundRect(inColor, Offset(x - bar - 1, size.height - hIn), Size(bar, hIn), CornerRadius(6f))
                drawRoundRect(outColor, Offset(x + 1, size.height - hOut), Size(bar, hOut), CornerRadius(6f))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            flows.forEach {
                Text("T${it.month.monthValue}", style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Dot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

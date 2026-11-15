package com.samuelcruz.sono.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.samuelcruz.sono.core.Dates
import com.samuelcruz.sono.core.DebtPlan
import com.samuelcruz.sono.core.Direction
import com.samuelcruz.sono.core.LunarCalendar
import com.samuelcruz.sono.core.Money
import com.samuelcruz.sono.data.DebtState
import com.samuelcruz.sono.data.Person
import java.time.LocalDate
import java.time.YearMonth

/** Something on a day of the calendar. [debtId] / [huiId] say what tapping it opens. */
class CalEvent(
    val day: Long,
    val kind: Int,
    val title: String,
    val amount: Long?,
    val done: Boolean,
    val debtId: Long? = null,
    val huiId: Long? = null,
    val round: Int? = null,
) {
    companion object {
        const val COLLECT = 0
        const val PAY = 1
        const val HUI = 2
    }
}

/** Unpaid due dates of open debts, and every round of every dây (held ones on the day they were held). */
fun calendarEvents(people: List<Person>, states: List<DebtState>, hui: List<HuiView>): List<CalEvent> {
    val names = people.associate { it.id to it.name }
    val out = ArrayList<CalEvent>()
    for (s in states) {
        val d = s.debt
        if (d.closed) continue
        val who = names[d.personId].orEmpty()
        val kind = if (d.direction == Direction.LENT) CalEvent.COLLECT else CalEvent.PAY
        val base = if (d.direction == Direction.LENT) "$who trả bạn" else "Bạn trả $who"
        if (d.plan == DebtPlan.INSTALLMENTS) {
            s.plan?.items?.forEach { item ->
                out += CalEvent(
                    item.installment.due.toEpochDay(), kind, "$base · kỳ ${item.installment.number}/${d.term}",
                    if (item.full) item.installment.total else item.left, item.full, debtId = d.id,
                )
            }
        } else if (d.dueDay != null) {
            out += CalEvent(d.dueDay, kind, base, s.left, false, debtId = d.id)
        }
    }
    for (v in hui) {
        for (n in 1..v.group.slots) {
            val r = v.rounds.firstOrNull { it.number == n }
            val day = r?.day ?: v.dates[n - 1].toEpochDay()
            val mineDue = v.calcs.firstOrNull { it.number == n }?.let { c -> v.mine.sumOf { c.dues[it.number] ?: 0 } }
            out += CalEvent(
                day, CalEvent.HUI,
                "Hụi ${v.group.name} · kỳ $n/${v.group.slots}" + (r?.let { " · ${v.slot(it.winner).displayName} hốt" }.orEmpty()),
                mineDue?.takeIf { it > 0 }, r != null, huiId = v.group.id, round = n,
            )
        }
    }
    return out
}

@Composable
fun CalendarScreen(pad: PaddingValues, onDebt: (Long) -> Unit, onRound: (Long, Int) -> Unit) {
    val (people, states) = rememberDebtStates()
    val hui = rememberHuiViews()
    val today = rememberToday()
    val cs = MaterialTheme.colorScheme
    val money = LocalMoneyColors.current
    var monthText by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    var selectedText by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    val month = YearMonth.parse(monthText)
    val selected = LocalDate.parse(selectedText)
    val todayDate = LocalDate.ofEpochDay(today)
    val events = remember(people, states, hui) { calendarEvents(people, states, hui) }
    val byDay = remember(events) { events.groupBy { it.day } }
    fun color(kind: Int) = when (kind) {
        CalEvent.COLLECT -> money.collect
        CalEvent.PAY -> money.pay
        else -> cs.primary
    }

    fun goTo(m: YearMonth) {
        monthText = m.toString()
        selectedText = (if (m == YearMonth.from(todayDate)) todayDate else m.atDay(1)).toString()
    }

    val monthEvents = events.filter { YearMonth.from(LocalDate.ofEpochDay(it.day)) == month && !it.done }
    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(bottom = pad.calculateBottomPadding() + 24.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Lịch", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                if (month != YearMonth.from(todayDate)) TextButton(onClick = { goTo(YearMonth.from(todayDate)) }) { Text("Hôm nay") }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { goTo(month.minusMonths(1)) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Tháng trước") }
                Text(
                    "Tháng ${month.monthValue}, ${month.year}", style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { goTo(month.plusMonths(1)) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Tháng sau") }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                listOf("T2", "T3", "T4", "T5", "T6", "T7", "CN").forEach {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(4.dp))
            val offset = month.atDay(1).dayOfWeek.value - 1
            val cells = offset + month.lengthOfMonth()
            Column(Modifier.padding(horizontal = 12.dp)) {
                for (week in 0 until (cells + 6) / 7) {
                    Row(Modifier.fillMaxWidth()) {
                        for (dow in 0 until 7) {
                            val dayNum = week * 7 + dow - offset + 1
                            if (dayNum < 1 || dayNum > month.lengthOfMonth()) {
                                Spacer(Modifier.weight(1f))
                                continue
                            }
                            val date = month.atDay(dayNum)
                            val list = byDay[date.toEpochDay()].orEmpty()
                            val isSel = date == selected
                            Box(
                                Modifier.weight(1f).aspectRatio(0.9f).padding(2.dp).clip(CircleShape)
                                    .then(if (isSel) Modifier.background(cs.primary) else Modifier)
                                    .then(if (date == todayDate && !isSel) Modifier.border(1.5.dp, cs.primary, CircleShape) else Modifier)
                                    .clickable { selectedText = date.toString() },
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        dayNum.toString(),
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (list.any { !it.done }) FontWeight.Bold else FontWeight.Normal),
                                        color = if (isSel) cs.onPrimary else cs.onSurface,
                                    )
                                    val l = LunarCalendar.solarToLunar(date.dayOfMonth, date.monthValue, date.year)
                                    Text(
                                        if (l.day == 1) "${l.day}/${l.month}" else l.day.toString(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isSel) cs.onPrimary.copy(alpha = 0.8f) else cs.onSurfaceVariant.copy(alpha = 0.7f),
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.height(5.dp)) {
                                        list.filter { !it.done }.map { it.kind }.distinct().forEach { k ->
                                            Box(Modifier.size(4.dp).clip(CircleShape).background(if (isSel) cs.onPrimary else color(k)))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Legend(money.collect, "Phải thu")
                Legend(money.pay, "Phải trả")
                Legend(cs.primary, "Khui hụi")
            }
        }
        item {
            val collect = monthEvents.filter { it.kind == CalEvent.COLLECT }.sumOf { it.amount ?: 0 }
            val pay = monthEvents.filter { it.kind == CalEvent.PAY }.sumOf { it.amount ?: 0 }
            if (collect > 0 || pay > 0) {
                Card(Modifier.padding(16.dp)) {
                    InfoRow("Còn phải thu trong tháng", Money.format(collect), money.collect, bold = true)
                    InfoRow("Còn phải trả trong tháng", Money.format(pay), money.pay, bold = true)
                }
            }
        }
        val list = byDay[selected.toEpochDay()].orEmpty()
        item {
            Text(
                Dates.long(selected) + " · " + Dates.lunarShort(selected),
                style = MaterialTheme.typography.labelLarge, color = cs.primary,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
            )
            if (list.isEmpty()) {
                Text("Không có gì đến hạn ngày này", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
            }
        }
        items(list) { e ->
            val late = !e.done && e.day < today
            Card(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth(), onClick = {
                e.debtId?.let(onDebt)
                if (e.huiId != null && e.round != null) onRound(e.huiId, e.round)
            }) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(color(e.kind)))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            when {
                                e.done && e.kind == CalEvent.HUI -> "Đã khui"
                                e.done -> "Đã đóng đủ"
                                late -> "Quá hạn ${today - e.day} ngày"
                                else -> Dates.relative(e.day, today).replaceFirstChar { it.uppercase() }
                            },
                            style = MaterialTheme.typography.bodySmall, color = if (late) money.late else cs.onSurfaceVariant,
                        )
                    }
                    e.amount?.let { Text(Money.format(it), style = MaterialTheme.typography.titleSmall, color = if (e.done) cs.onSurfaceVariant else color(e.kind)) }
                }
            }
        }
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

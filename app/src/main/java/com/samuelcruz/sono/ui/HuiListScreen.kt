package com.samuelcruz.sono.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.samuelcruz.sono.core.Dates
import com.samuelcruz.sono.core.Hui
import com.samuelcruz.sono.core.HuiCycle
import com.samuelcruz.sono.core.Money
import com.samuelcruz.sono.core.ThaoMode
import com.samuelcruz.sono.core.percent
import com.samuelcruz.sono.data.HuiGroup
import com.samuelcruz.sono.data.HuiPaid
import com.samuelcruz.sono.data.HuiRole
import com.samuelcruz.sono.data.HuiRound
import com.samuelcruz.sono.data.HuiSlot
import java.time.LocalDate

/** A dây with its shares, rounds and paid marks, plus what follows from them. */
class HuiView(val group: HuiGroup, val slots: List<HuiSlot>, val rounds: List<HuiRound>, val paid: List<HuiPaid>) {
    val rules = group.rules
    val inputs = rounds.map { it.toInput() }
    val calcs = Hui.rounds(rules, inputs)
    val dates: List<LocalDate> = group.dates()
    val held = rounds.size
    val finished = held >= group.slots
    /** The next round to hold (1-based), or null when finished. */
    val nextNumber: Int? = if (finished) null else held + 1
    val nextDate: LocalDate? = nextNumber?.let { dates[it - 1] }
    val mine = slots.filter { it.mine }
    val ownerSlot: Int? = if (group.ownerFirst) 1 else null
    fun slot(n: Int): HuiSlot = slots.firstOrNull { it.number == n } ?: HuiSlot(group.id, n)
    fun winnerOf(n: Int): Int? = rounds.firstOrNull { it.number == n }?.winner
    fun wonRound(slot: Int): Int? = rounds.firstOrNull { it.winner == slot }?.number
    fun isPaid(number: Int, slot: Int) = paid.any { it.number == number && it.slot == slot }
    /** What the organizer paid for share [slot] (dropped out / paid in its place). */
    fun covered(slot: Int): Long = paid.filter { it.slot == slot && it.covered }.sumOf { p ->
        calcs.firstOrNull { it.number == p.number }?.dues?.get(slot) ?: 0
    }

    /** Shares that still owe their due for a held round. */
    fun unpaid(number: Int): List<Int> {
        val c = calcs.firstOrNull { it.number == number } ?: return emptyList()
        return c.dues.keys.filter { !isPaid(number, it) }
    }
}

@Composable
fun rememberHuiViews(): List<HuiView> {
    val app = app()
    val groups by app.db.hui().observeGroups().collectAsState(emptyList())
    val slots by app.db.hui().observeAllSlots().collectAsState(emptyList())
    val rounds by app.db.hui().observeAllRounds().collectAsState(emptyList())
    val paid by app.db.hui().observeAllPaid().collectAsState(emptyList())
    return remember(groups, slots, rounds, paid) {
        val s = slots.groupBy { it.huiId }
        val r = rounds.groupBy { it.huiId }
        val p = paid.groupBy { it.huiId }
        groups.map { g -> HuiView(g, s[g.id].orEmpty(), r[g.id].orEmpty().sortedBy { it.number }, p[g.id].orEmpty()) }
    }
}

fun cycleText(g: HuiGroup): String = when (g.cycle) {
    HuiCycle.MONTHLY -> "hằng tháng (dương lịch)"
    HuiCycle.LUNAR_MONTHLY -> "hằng tháng (âm lịch)"
    else -> when (g.everyDays) {
        1 -> "hằng ngày"
        7 -> "hằng tuần"
        15 -> "nửa tháng"
        else -> "mỗi ${g.everyDays} ngày"
    }
}

fun thaoText(g: HuiGroup): String = when (g.thaoMode) {
    ThaoMode.FIXED -> "thảo ${Money.format(g.thaoValue)}/kỳ"
    ThaoMode.PERCENT -> "thảo ${percent(g.thaoValue / 10.0, 1)} mệnh giá"
    else -> "không thảo"
}

@Composable
fun HuiListScreen(pad: PaddingValues, onOpen: (Long) -> Unit, onAdd: () -> Unit) {
    val views = rememberHuiViews()
    val money = LocalMoneyColors.current
    val today = rememberToday()
    val active = views.filter { !it.finished }.sortedBy { it.nextDate }
    val done = views.filter { it.finished }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = pad.calculateBottomPadding() + 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp)).background(money.hero)
                        .statusBarsPadding().padding(horizontal = 20.dp, vertical = 18.dp),
                ) {
                    Text("Hụi", style = MaterialTheme.typography.titleLarge, color = Color.White)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (active.isEmpty()) "Chưa có dây hụi nào đang chơi" else "Đang chơi ${active.size} dây",
                        style = MaterialTheme.typography.headlineSmall, color = Color.White,
                    )
                    active.firstOrNull()?.let { v ->
                        Text(
                            "Kỳ khui gần nhất: ${v.group.name}, ${Dates.format(v.nextDate!!.toEpochDay(), short = true)} (${Dates.relative(v.nextDate.toEpochDay(), today)})",
                            style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.85f),
                        )
                    }
                }
            }
            if (views.isEmpty()) {
                item { EmptyState("🤝", "Chưa có dây hụi", "Bấm \"Tạo dây hụi\" để bắt đầu: làm chủ hụi hay chơi hụi viên đều được.") }
            }
            items(active, key = { it.group.id }) { v -> HuiCard(v, today, Modifier.padding(horizontal = 16.dp)) { onOpen(v.group.id) } }
            if (done.isNotEmpty()) {
                item { SectionTitle("Đã mãn (${done.size})") }
                items(done, key = { it.group.id }) { v -> HuiCard(v, today, Modifier.padding(horizontal = 16.dp)) { onOpen(v.group.id) } }
            }
        }
        ExtendedFloatingActionButton(
            onClick = onAdd,
            icon = { Icon(Icons.Default.Add, null) },
            text = { Text("Tạo dây hụi") },
            containerColor = Brand, contentColor = Color.White,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = pad.calculateBottomPadding() + 16.dp),
        )
    }
}

@Composable
private fun HuiCard(v: HuiView, today: Long, modifier: Modifier, onClick: () -> Unit) {
    val g = v.group
    val money = LocalMoneyColors.current
    Card(modifier.fillMaxWidth(), onClick = onClick) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(g.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Tag(if (g.role == HuiRole.OWNER) "Chủ hụi" else "Hụi viên", MaterialTheme.colorScheme.primary)
            }
            Text(
                "${Money.format(g.amount)} × ${g.slots} chân · ${cycleText(g)}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Progress(v.held.toFloat() / g.slots, MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
            Row {
                Text(
                    if (v.finished) "Đã mãn ${g.slots}/${g.slots} kỳ" else "Đã khui ${v.held}/${g.slots} kỳ",
                    style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f),
                )
                v.nextDate?.let { d ->
                    val day = d.toEpochDay()
                    Text(
                        "Kỳ ${v.nextNumber}: ${Dates.format(day, short = true)} · ${Dates.relative(day, today)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (day < today) money.late else MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (v.mine.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    v.mine.joinToString(" · ") { s ->
                        val won = v.wonRound(s.number)
                        "Chân ${s.number}: " + if (won != null) "đã hốt kỳ $won" else "hụi sống"
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (g.role == HuiRole.OWNER) {
                val missing = v.rounds.sumOf { v.unpaid(it.number).size }
                if (missing > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text("Còn $missing lượt chưa đóng tiền", style = MaterialTheme.typography.bodySmall, color = money.late)
                }
            }
        }
    }
}

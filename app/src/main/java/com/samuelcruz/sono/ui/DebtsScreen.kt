package com.samuelcruz.sono.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.samuelcruz.sono.core.Dates
import com.samuelcruz.sono.core.DebtPlan
import com.samuelcruz.sono.core.Direction
import com.samuelcruz.sono.core.Money
import com.samuelcruz.sono.core.plain
import com.samuelcruz.sono.data.DebtState
import com.samuelcruz.sono.data.Person
import com.samuelcruz.sono.data.totals

/** A person with all their debts worked out. */
class PersonSummary(val person: Person, val states: List<DebtState>) {
    val open = states.filter { !it.debt.closed }
    /** + they owe me, − I owe them. */
    val net: Long = open.sumOf { it.signedLeft }
    val nextDue: Long? = open.mapNotNull { it.nextDueDay }.minOrNull()
    val overdue: Boolean = open.any { it.overdueDays > 0 }
}

/** Every debt state, recomputed whenever a table changes. */
@Composable
fun rememberDebtStates(): Pair<List<Person>, List<DebtState>> {
    val app = app()
    val people by app.db.people().observeAll().collectAsState(emptyList())
    val debts by app.db.debts().observeAll().collectAsState(emptyList())
    val payments by app.db.payments().observeAll().collectAsState(emptyList())
    val today = rememberToday()
    val states = remember(debts, payments, today) {
        val byDebt = payments.groupBy { it.debtId }
        debts.map { DebtState(it, byDebt[it.id].orEmpty(), today) }
    }
    return people to states
}

private const val FILTER_OPEN = 0
private const val FILTER_DONE = 1
private const val FILTER_ALL = 2

@Composable
fun DebtsScreen(pad: PaddingValues, onPerson: (Long) -> Unit, onDebt: (Long) -> Unit, onAdd: () -> Unit) {
    val (people, states) = rememberDebtStates()
    val money = LocalMoneyColors.current
    val today = rememberToday()
    var filter by rememberSaveable { mutableStateOf(FILTER_OPEN) }
    var query by rememberSaveable { mutableStateOf("") }
    val totals = remember(states) { states.totals() }
    val summaries = remember(people, states) {
        val byPerson = states.groupBy { it.debt.personId }
        people.map { PersonSummary(it, byPerson[it.id].orEmpty()) }
    }
    val shown = remember(summaries, filter, query) {
        val q = query.trim().plain()
        summaries
            .filter { s ->
                when (filter) {
                    FILTER_OPEN -> s.open.isNotEmpty()
                    FILTER_DONE -> s.states.isNotEmpty() && s.open.isEmpty()
                    else -> true
                }
            }
            .filter { q.isEmpty() || it.person.name.plain().contains(q) || it.person.phone.contains(q) }
            .sortedWith(compareByDescending<PersonSummary> { it.overdue }.thenBy { it.nextDue ?: Long.MAX_VALUE }.thenByDescending { kotlin.math.abs(it.net) })
    }
    // Due in the next 14 days or late, soonest first.
    val upcoming = remember(states, people) {
        val names = people.associate { it.id to it.name }
        states.flatMap { s ->
            val dues = s.dueDates()
            dues.filterIndexed { i, (day, _) -> day - today <= 14 && (day >= today || i == 0) }
                .map { (day, amount) -> Upcoming(s, names[s.debt.personId].orEmpty(), day, amount) }
        }.sortedBy { it.day }.take(6)
    }

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
                    Text("Sổ nợ", style = MaterialTheme.typography.titleLarge, color = Color.White)
                    Spacer(Modifier.height(14.dp))
                    Row {
                        AmountBlock("Người khác nợ tôi", totals.toCollect, Color.White, Modifier.weight(1f))
                        AmountBlock("Tôi đang nợ", totals.toPay, Color.White, Modifier.weight(1f))
                    }
                    if (totals.overdueCollect > 0 || totals.overduePay > 0) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            listOfNotNull(
                                totals.overdueCollect.takeIf { it > 0 }?.let { "Quá hạn chưa thu ${Money.format(it)}" },
                                totals.overduePay.takeIf { it > 0 }?.let { "quá hạn chưa trả ${Money.format(it)}" },
                            ).joinToString(" · ").replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.bodySmall, color = Color(0xFFFFE4C4),
                        )
                    }
                }
            }
            if (upcoming.isNotEmpty()) {
                item { SectionTitle("Sắp đến hạn") }
                item {
                    Card(Modifier.padding(horizontal = 16.dp)) {
                        upcoming.forEach { u -> UpcomingRow(u, today) { onDebt(u.state.debt.id) } }
                    }
                }
            }
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        placeholder = { Text("Tìm theo tên, số điện thoại") },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        shape = RoundedCornerShape(50), modifier = Modifier.fillMaxWidth(),
                    )
                    ChoiceChips(listOf(FILTER_OPEN to "Đang nợ", FILTER_DONE to "Đã xong", FILTER_ALL to "Tất cả"), filter, { filter = it })
                }
            }
            if (shown.isEmpty()) {
                item {
                    if (states.isEmpty()) EmptyState("📒", "Chưa có khoản nợ nào", "Bấm \"Ghi nợ\" để ghi lại tiền cho mượn hoặc đi mượn.")
                    else EmptyState("🔍", "Không có ai", "Thử đổi bộ lọc hoặc từ tìm kiếm.")
                }
            }
            items(shown, key = { it.person.id }) { s ->
                PersonRow(s, today, Modifier.padding(horizontal = 16.dp)) { onPerson(s.person.id) }
            }
        }
        ExtendedFloatingActionButton(
            onClick = onAdd,
            icon = { Icon(Icons.Default.Add, null) },
            text = { Text("Ghi nợ") },
            containerColor = Brand, contentColor = Color.White,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = pad.calculateBottomPadding() + 16.dp),
        )
    }
}

class Upcoming(val state: DebtState, val name: String, val day: Long, val amount: Long)

@Composable
private fun UpcomingRow(u: Upcoming, today: Long, onClick: () -> Unit) {
    val money = LocalMoneyColors.current
    val late = u.day < today
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            val d = u.state.debt
            val who = if (d.direction == Direction.LENT) "${u.name} trả bạn" else "Bạn trả ${u.name}"
            val part = if (d.plan == DebtPlan.INSTALLMENTS) {
                val n = u.state.plan?.items?.firstOrNull { it.installment.due.toEpochDay() == u.day }?.installment?.number
                n?.let { " · kỳ $it/${d.term}" }.orEmpty()
            } else ""
            Text(who + part, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                Dates.format(u.day, short = true) + " · " + Dates.relative(u.day, today),
                style = MaterialTheme.typography.bodySmall, color = if (late) money.late else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(Money.format(u.amount), style = MaterialTheme.typography.titleSmall, color = money.of(u.state.debt.direction))
    }
}

@Composable
private fun PersonRow(s: PersonSummary, today: Long, modifier: Modifier, onClick: () -> Unit) {
    val money = LocalMoneyColors.current
    Card(modifier.fillMaxWidth(), onClick = onClick) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(s.person.name)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(s.person.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = when {
                    s.open.isEmpty() -> "Đã trả xong ${s.states.size} khoản"
                    else -> listOfNotNull(
                        "${s.open.size} khoản",
                        s.nextDue?.let { "hạn ${Dates.format(it, short = true)} (${Dates.relative(it, today)})" },
                    ).joinToString(" · ")
                }
                Text(sub, style = MaterialTheme.typography.bodySmall, color = if (s.overdue) money.late else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                if (s.net != 0L) {
                    Text(Money.format(kotlin.math.abs(s.net)), style = MaterialTheme.typography.titleSmall, color = if (s.net > 0) money.collect else money.pay)
                    Text(if (s.net > 0) "nợ bạn" else "bạn nợ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (s.open.isEmpty()) {
                    Tag("Đã xong", MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

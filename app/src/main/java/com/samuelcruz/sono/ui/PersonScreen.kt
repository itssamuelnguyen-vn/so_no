package com.samuelcruz.sono.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.RequestQuote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.samuelcruz.sono.core.Dates
import com.samuelcruz.sono.core.DebtPlan
import com.samuelcruz.sono.core.Direction
import com.samuelcruz.sono.core.HuiCycle
import com.samuelcruz.sono.core.Money
import com.samuelcruz.sono.data.Debt
import com.samuelcruz.sono.data.DebtState
import com.samuelcruz.sono.data.Person
import kotlinx.coroutines.launch

@Composable
fun PersonScreen(personId: Long, onBack: () -> Unit, onDebt: (Long) -> Unit, onNewDebt: (Int) -> Unit) {
    val app = app()
    val context = LocalContext.current
    val person by app.db.people().observe(personId).collectAsState(null)
    val (_, all) = rememberDebtStates()
    val states = remember(all, personId) { all.filter { it.debt.personId == personId } }
    val open = states.filter { !it.debt.closed }.sortedBy { it.nextDueDay ?: Long.MAX_VALUE }
    val closed = states.filter { it.debt.closed }.sortedByDescending { it.debt.closedDay }
    val net = open.sumOf { it.signedLeft }
    val money = LocalMoneyColors.current
    var editing by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    var asking by rememberSaveable { mutableStateOf(false) }
    var showClosed by rememberSaveable { mutableStateOf(false) }
    val p = person ?: return

    SimpleScaffold(
        p.name, onBack,
        actions = {
            IconButton(onClick = { editing = true }) { Icon(Icons.Default.Edit, "Sửa") }
            IconButton(onClick = { deleting = true }) { Icon(Icons.Default.Delete, "Xóa") }
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = ScreenPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Card {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(p.name, 56.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                when {
                                    net > 0 -> "${p.name} đang nợ bạn"
                                    net < 0 -> "Bạn đang nợ ${p.name}"
                                    else -> "Không còn nợ"
                                },
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(Money.format(kotlin.math.abs(net)), style = MaterialTheme.typography.headlineSmall, color = if (net >= 0) money.collect else money.pay)
                            if (p.phone.isNotBlank()) Text(p.phone, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (p.phone.isNotBlank()) {
                            IconButton(onClick = { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${p.phone}"))) }) { Icon(Icons.Default.Call, "Gọi") }
                            IconButton(onClick = { context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${p.phone}"))) }) { Icon(Icons.AutoMirrored.Filled.Message, "Nhắn tin") }
                        }
                    }
                    if (p.note.isNotBlank()) Text(p.note, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
                    Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onNewDebt(Direction.LENT) }, modifier = Modifier.weight(1f)) { Text("Cho mượn") }
                        OutlinedButton(onClick = { onNewDebt(Direction.BORROWED) }, modifier = Modifier.weight(1f)) { Text("Đi mượn") }
                    }
                    if (open.any { it.debt.direction == Direction.LENT && it.left > 0 }) {
                        FilledTonalButton(onClick = { asking = true }, modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                            Icon(Icons.Default.RequestQuote, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Nhắc trả nợ (kèm mã QR)")
                        }
                    }
                }
            }
            if (open.isEmpty() && closed.isEmpty()) item { EmptyState("📝", "Chưa có khoản nào", "Bấm Cho mượn hoặc Đi mượn để ghi.") }
            if (open.isNotEmpty()) item { SectionTitle("Đang nợ (${open.size})") }
            items(open, key = { it.debt.id }) { s -> DebtCard(s) { onDebt(s.debt.id) } }
            if (closed.isNotEmpty()) {
                item { SectionTitle("Đã xong (${closed.size})", action = if (showClosed) "Ẩn" else "Hiện", onAction = { showClosed = !showClosed }) }
                if (showClosed) items(closed, key = { it.debt.id }) { s -> DebtCard(s) { onDebt(s.debt.id) } }
            }
        }
    }

    if (editing) PersonDialog(p, onDismiss = { editing = false }) { app.scope.launch { app.repo.savePerson(it) } }
    if (deleting) {
        ConfirmDialog(
            "Xóa ${p.name}?", "Xóa luôn ${states.size} khoản nợ và lịch sử trả của người này. Không hoàn tác được.", "Xóa",
            onConfirm = { app.scope.launch { app.repo.deletePerson(p) }; onBack() }, onDismiss = { deleting = false },
        )
    }
    if (asking) {
        val lent = open.filter { it.debt.direction == Direction.LENT && it.left > 0 }
        val due = lent.sumOf { it.left }
        val lines = lent.joinToString("\n") { s ->
            "- ${Money.format(s.debt.amount)} mượn ngày ${Dates.format(s.debt.startDay)}: còn ${Money.format(s.left)}" +
                (s.nextDueDay?.let { " (hạn ${Dates.format(it)})" }.orEmpty())
        }
        QrDialog(
            "Nhắc ${p.name} trả nợ", due, "Tra no",
            { account -> "Chào ${p.name}, mình nhắc nhẹ khoản nợ còn ${Money.format(due)}:\n$lines${account?.let { "\n$it" }.orEmpty()}\nCảm ơn nhé!" },
            onDismiss = { asking = false },
        )
    }
}

/** Edit a person's name, phone and note. */
@Composable
fun PersonDialog(p: Person, onDismiss: () -> Unit, onSave: (Person) -> Unit) {
    var name by rememberSaveable { mutableStateOf(p.name) }
    var phone by rememberSaveable { mutableStateOf(p.phone) }
    var note by rememberSaveable { mutableStateOf(p.note) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Thông tin người") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextInput("Tên", name, { name = it })
                TextInput("Số điện thoại", phone, { phone = it }, keyboard = KeyboardType.Phone)
                TextInput("Ghi chú", note, { note = it }, singleLine = false)
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                onSave(p.copy(name = name.trim(), phone = phone.trim(), note = note.trim()))
                onDismiss()
            }) { Text("Lưu") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Hủy") } },
    )
}

/** "Trả góp 12 kỳ · ngày 15 hằng tháng", "Trả một lần · hạn 20/11/2026". */
fun planText(d: Debt): String = if (d.plan == DebtPlan.INSTALLMENTS) {
    val first = Dates.of(d.dueDay ?: d.startDay)
    "Trả góp ${d.term} kỳ · " + when {
        d.cycle == HuiCycle.MONTHLY -> "ngày ${first.dayOfMonth} hằng tháng"
        d.everyDays == 1 -> "góp mỗi ngày"
        d.everyDays == 7 -> "mỗi tuần"
        else -> "mỗi ${d.everyDays} ngày"
    }
} else {
    "Trả một lần" + (d.dueDay?.let { " · hạn ${Dates.format(it)}" } ?: " · không hạn")
}

fun interestText(d: Debt): String = if (!d.hasInterest) "Không lãi" else {
    val rate = com.samuelcruz.sono.core.percent(d.ratePercent) + if (d.rateYearly) "/năm" else "/tháng"
    when (d.interestMethod) {
        com.samuelcruz.sono.core.InterestMethod.FLAT -> "Lãi $rate (lãi phẳng)"
        com.samuelcruz.sono.core.InterestMethod.DECLINING -> "Lãi $rate (dư nợ giảm dần)"
        com.samuelcruz.sono.core.InterestMethod.ANNUITY -> "Lãi $rate (góp đều)"
        else -> "Lãi $rate, tính theo ngày"
    }
}

@Composable
fun DebtCard(s: DebtState, onClick: () -> Unit) {
    val money = LocalMoneyColors.current
    val d = s.debt
    val total = d.amount + s.interest
    Card(Modifier.fillMaxWidth(), onClick = onClick) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Tag(if (d.direction == Direction.LENT) "Cho mượn" else "Đi mượn", money.of(d.direction))
                Spacer(Modifier.width(8.dp))
                Text(Dates.format(d.startDay), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                when {
                    d.closed -> Tag("Đã xong", MaterialTheme.colorScheme.onSurfaceVariant)
                    s.overdueDays > 0 -> Tag("Quá hạn ${s.overdueDays} ngày", money.late)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(Money.format(d.amount), style = MaterialTheme.typography.titleLarge)
                    Text(planText(d), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (d.note.isNotBlank()) Text(d.note, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (!d.closed) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text("còn lại", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(Money.format(s.left), style = MaterialTheme.typography.titleMedium, color = money.of(d.direction))
                    }
                }
            }
            if (!d.closed && total > 0) {
                Spacer(Modifier.height(10.dp))
                Progress(s.paid.toFloat() / total, money.of(d.direction))
                Spacer(Modifier.height(4.dp))
                val plan = s.plan
                Text(
                    if (plan != null) "Đã đóng ${plan.paidCount}/${d.term} kỳ · ${Money.format(s.paid)}" +
                        (s.nextDueDay?.let { " · kỳ tới ${Dates.format(it, short = true)}: ${Money.format(s.nextDueAmount)}" }.orEmpty())
                    else "Đã trả ${Money.format(s.paid)}" + (if (s.interest > 0) " · lãi đến nay ${Money.format(s.interest)}" else ""),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

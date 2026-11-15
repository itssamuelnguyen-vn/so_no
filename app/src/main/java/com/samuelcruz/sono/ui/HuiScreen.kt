package com.samuelcruz.sono.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.samuelcruz.sono.core.Dates
import com.samuelcruz.sono.core.Hui
import com.samuelcruz.sono.core.HuiCycle
import com.samuelcruz.sono.core.Money
import com.samuelcruz.sono.core.percent
import com.samuelcruz.sono.data.HuiRole
import kotlinx.coroutines.launch

@Composable
fun HuiScreen(huiId: Long, onBack: () -> Unit, onEdit: () -> Unit, onRound: (Int) -> Unit, onDebt: (Long) -> Unit) {
    val app = app()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val v = rememberHuiViews().firstOrNull { it.group.id == huiId } ?: return
    val g = v.group
    val today = rememberToday()
    val money = LocalMoneyColors.current
    var deleting by rememberSaveable { mutableStateOf(false) }
    var showAllRounds by rememberSaveable { mutableStateOf(false) }
    var slotDialog by rememberSaveable { mutableStateOf<Int?>(null) }

    SimpleScaffold(
        g.name, onBack,
        actions = {
            IconButton(onClick = { scope.launch { QrShare.share(context, boardText(v), null) } }) { Icon(Icons.Default.Share, "Gửi bảng hụi") }
            IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Sửa") }
            IconButton(onClick = { deleting = true }) { Icon(Icons.Default.Delete, "Xóa") }
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = ScreenPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Card {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${Money.format(g.amount)} × ${g.slots} chân", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                            Tag(if (g.role == HuiRole.OWNER) "Chủ hụi" else "Hụi viên", MaterialTheme.colorScheme.primary)
                        }
                        Text(
                            listOfNotNull(
                                "Khui ${cycleText(g)}",
                                when {
                                    !g.bidding -> "không lãi"
                                    g.fixedBid > 0 -> "lãi cố định ${Money.compact(g.fixedBid)}"
                                    else -> "bỏ thăm"
                                },
                                "chủ hốt kỳ đầu".takeIf { g.ownerFirst },
                                thaoText(g) + if (g.thaoMode != 0 && !g.thaoLast) " (trừ kỳ cuối)" else "",
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (g.role == HuiRole.MEMBER && g.ownerName.isNotBlank()) {
                            Text("Chủ hụi: ${g.ownerName}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(12.dp))
                        Progress(v.held.toFloat() / g.slots, MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (v.finished) "Dây hụi đã mãn" else "Đã khui ${v.held}/${g.slots} kỳ · còn ${g.slots - v.held} kỳ",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        if (g.note.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(g.note, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    v.nextNumber?.let { n ->
                        val d = v.nextDate!!
                        Button(onClick = { onRound(n) }, modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp).height(52.dp)) {
                            Text("Khui kỳ $n · ${Dates.format(d.toEpochDay(), short = true)}" + if (g.cycle == HuiCycle.LUNAR_MONTHLY) " (${Dates.lunarShort(d)})" else "")
                        }
                    }
                }
            }
            if (v.mine.isNotEmpty()) {
                item { SectionTitle(if (v.mine.size == 1) "Chân của tôi" else "Các chân của tôi") }
                items(v.mine, key = { "m" + it.number }) { s -> MySlotCard(v, s.number) }
            }
            if (g.role == HuiRole.OWNER && v.held > 0) {
                val owed = v.rounds.flatMap { r -> v.unpaid(r.number).map { r.number to it } }
                if (owed.isNotEmpty()) {
                    item {
                        Card {
                            Text("Chưa đóng tiền", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 14.dp, end = 16.dp))
                            owed.groupBy { it.first }.forEach { (n, list) ->
                                val c = v.calcs.first { it.number == n }
                                Text(
                                    "Kỳ $n: " + list.joinToString(", ") { (_, slot) -> v.slot(slot).displayName } +
                                        " · thiếu ${Money.format(list.sumOf { (_, slot) -> c.dues[slot] ?: 0 })}",
                                    style = MaterialTheme.typography.bodySmall, color = money.late,
                                    modifier = Modifier.fillMaxWidth().clickable { onRound(n) }.padding(horizontal = 16.dp, vertical = 6.dp),
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
            item { SectionTitle("Các kỳ", action = if (g.slots > 8) (if (showAllRounds) "Thu gọn" else "Xem tất cả") else null, onAction = { showAllRounds = !showAllRounds }) }
            item {
                Card {
                    val all = (1..g.slots).toList()
                    val shown = if (showAllRounds || g.slots <= 8) all else {
                        val from = (v.held - 2).coerceAtLeast(1)
                        all.filter { it in from..(from + 6) }
                    }
                    shown.forEach { n -> RoundRow(v, n, today) { if (n <= v.held + 1) onRound(n) } }
                }
            }
            item { SectionTitle("Các chân (${g.slots})") }
            item {
                Card {
                    (1..g.slots).forEach { n ->
                        val s = v.slot(n)
                        val won = v.wonRound(n)
                        Row(Modifier.fillMaxWidth().clickable { slotDialog = n }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            SlotBadge(n, won != null)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(s.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                    if (s.mine) { Spacer(Modifier.width(6.dp)); Tag("Của tôi", MaterialTheme.colorScheme.primary) }
                                }
                                Text(
                                    (if (won != null) "Hụi chết · hốt kỳ $won, nhận ${Money.format(v.calcs.first { it.number == won }.net)}" else "Hụi sống") +
                                        (s.droppedFrom?.let { " · bỏ ngang từ kỳ $it" }.orEmpty()),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (s.droppedFrom != null) money.late else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (g.role == HuiRole.OWNER) {
                                val missing = v.rounds.count { r -> n in v.unpaid(r.number) }
                                if (missing > 0) Tag("Nợ $missing kỳ", money.late)
                            }
                        }
                    }
                }
            }
        }
    }
    slotDialog?.let { n -> SlotDialog(v, n, onDismiss = { slotDialog = null }, onDebt = onDebt) }
    if (deleting) {
        ConfirmDialog(
            "Xóa dây hụi?", "Xóa \"${g.name}\" cùng ${v.held} kỳ đã khui và đánh dấu đóng tiền. Không hoàn tác được.", "Xóa",
            onConfirm = { app.scope.launch { app.repo.deleteHui(g) }; onBack() }, onDismiss = { deleting = false },
        )
    }
}

@Composable
fun SlotBadge(n: Int, dead: Boolean) {
    Box(
        Modifier.size(34.dp).clip(CircleShape).background(
            if (dead) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.primaryContainer,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            n.toString(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
            color = if (dead) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun RoundRow(v: HuiView, n: Int, today: Long, onClick: () -> Unit) {
    val g = v.group
    val money = LocalMoneyColors.current
    val date = v.dates[n - 1]
    val calc = v.calcs.firstOrNull { it.number == n }
    val round = v.rounds.firstOrNull { it.number == n }
    val isNext = n == v.nextNumber
    Row(
        Modifier.fillMaxWidth().clickable(enabled = calc != null || isNext, onClick = onClick)
            .background(if (isNext) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(64.dp)) {
            Text("Kỳ $n", style = MaterialTheme.typography.titleSmall, color = if (calc == null && !isNext) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified)
            Text(Dates.format((round?.day ?: date.toEpochDay()), short = true), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.weight(1f)) {
            if (calc != null) {
                Text(v.slot(calc.winner).displayName + " hốt", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val unpaid = v.unpaid(n).size
                Text(
                    listOfNotNull(
                        if (calc.bid > 0) "kêu ${Money.format(calc.bid)}" else null,
                        if (g.role == HuiRole.OWNER) (if (unpaid == 0) "đã thu đủ" else "còn $unpaid chân chưa đóng") else null,
                        if (round?.paidOut == true) "đã giao tiền" else null,
                    ).joinToString(" · ").ifEmpty { "không bỏ thăm" },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (g.role == HuiRole.OWNER && unpaid > 0) money.late else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val day = date.toEpochDay()
                Text(
                    if (isNext) "Kỳ tới · ${Dates.relative(day, today)}" else Dates.weekday(date) + if (g.cycle == HuiCycle.LUNAR_MONTHLY) " · ${Dates.lunarShort(date)}" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isNext && day < today) money.late else if (isNext) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (calc != null) Text(Money.format(calc.net), style = MaterialTheme.typography.titleSmall, color = money.collect)
    }
}

/** What one of my shares has paid, got, and still has to pay; the effective rate once it is known. */
@Composable
private fun MySlotCard(v: HuiView, slot: Int) {
    val g = v.group
    val money = LocalMoneyColors.current
    val sum = Hui.summary(v.rules, v.inputs, slot, g.periodDays)
    Card {
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            SlotBadge(slot, sum.wonRound != null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(v.slot(slot).displayName, style = MaterialTheme.typography.titleSmall)
                Text(
                    if (sum.wonRound != null) "Hụi chết · đã hốt kỳ ${sum.wonRound}" else "Hụi sống · chưa hốt",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        InfoRow("Đã đóng", Money.format(sum.paid))
        if (sum.wonRound != null) {
            InfoRow("Đã nhận khi hốt", Money.format(sum.received), money.collect)
            InfoRow("Còn phải đóng", "${Money.format(sum.toPay ?: 0)} (${g.slots - v.held} kỳ × ${Money.compact(g.amount)})")
            sum.result?.let { r ->
                InfoRow(if (r >= 0) "Lời cả dây" else "Chi phí cả dây (tiền lãi đã chịu)", Money.format(kotlin.math.abs(r)), if (r >= 0) money.collect else money.pay, bold = true)
            }
            sum.monthlyRate?.let { rate ->
                InfoRow(
                    if (rate >= 0) "Lãi được hưởng" else "Lãi phải chịu",
                    "≈ ${percent(kotlin.math.abs(rate) * 100)}/tháng (${percent(kotlin.math.abs(Hui.yearly(rate)) * 100, 1)}/năm)",
                    if (rate < 0 && Hui.exceedsLegal(rate)) money.late else Color.Unspecified,
                )
            }
        } else if (!v.finished) {
            val nextFixed = v.nextNumber?.let { v.rules.fixedRound(it) } == true
            InfoRow(
                "Kỳ tới phải đóng",
                when {
                    nextFixed || !g.bidding -> Money.format(g.amount)
                    g.fixedBid > 0 -> Money.format(g.amount - g.fixedBid)
                    else -> "${Money.format(g.amount)} trừ tiền thăm"
                },
            )
            // Collecting last: everyone else is dead and pays in full.
            val lastNet = (g.slots - 1) * g.amount - Hui.thao(v.rules, g.slots)
            InfoRow("Nếu hốt kỳ cuối sẽ nhận", Money.format(lastNet), money.collect)
        }
        if (g.role == HuiRole.MEMBER) {
            val unpaid = v.rounds.filter { r -> slot in v.unpaid(r.number) }
            if (unpaid.isNotEmpty()) {
                Text(
                    "Chưa đánh dấu đã đóng: kỳ " + unpaid.joinToString(", ") { it.number.toString() },
                    style = MaterialTheme.typography.bodySmall, color = money.late, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}

/** The whole dây as text, to send to the group chat. */
fun boardText(v: HuiView): String {
    val g = v.group
    val out = StringBuilder()
    out.append("📋 Hụi ${g.name}: ${Money.format(g.amount)} × ${g.slots} chân, khui ${cycleText(g)}\n")
    for (n in 1..g.slots) {
        val c = v.calcs.firstOrNull { it.number == n }
        val date = Dates.format(v.rounds.firstOrNull { it.number == n }?.day ?: v.dates[n - 1].toEpochDay())
        out.append("Kỳ $n ($date): ")
        if (c != null) {
            out.append("${v.slot(c.winner).displayName} hốt")
            if (c.bid > 0) out.append(", kêu ${Money.format(c.bid)}")
            out.append(", nhận ${Money.format(c.net)}")
        } else out.append("chưa khui")
        out.append('\n')
    }
    return out.toString().trimEnd()
}

/** One share: name / phone, and "bỏ ngang" (stopped paying, the organizer covers it). */
@Composable
private fun SlotDialog(v: HuiView, n: Int, onDismiss: () -> Unit, onDebt: (Long) -> Unit) {
    val app = app()
    val g = v.group
    val s = v.slot(n)
    var name by rememberSaveable { mutableStateOf(s.name) }
    var phone by rememberSaveable { mutableStateOf(s.phone) }
    var dropped by rememberSaveable { mutableStateOf(s.droppedFrom != null) }
    var from by rememberSaveable { mutableStateOf((s.droppedFrom ?: (v.held + 1).coerceAtMost(g.slots)).toString()) }
    val covered = v.covered(n)
    val won = v.wonRound(n)
    // What the organizer still has to pay for it: a dead share pays the full amount every remaining round.
    val ahead = if (dropped && won != null) (g.slots - v.held).toLong() * g.amount else 0L
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Chân $n") },
        text = {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextInput("Tên", name, { name = it })
                TextInput("Số điện thoại", phone, { phone = it }, keyboard = androidx.compose.ui.text.input.KeyboardType.Phone)
                if (g.role == HuiRole.OWNER) {
                    SwitchRow(
                        "Bỏ ngang (không đóng nữa)", dropped, { dropped = it },
                        subtitle = if (won != null) "Giật hụi: đã hốt rồi bỏ, chủ hụi phải đóng thay các kỳ còn lại"
                        else "Chủ hụi đóng thay từ kỳ đó; khi chân này hốt thì chủ hụi nhận",
                    )
                    if (dropped) NumberField("Từ kỳ", from, { from = it }, max = 3)
                    if (covered > 0 || ahead > 0) {
                        InfoRow("Chủ hụi đã đóng thay", Money.format(covered), LocalMoneyColors.current.late, bold = true)
                        if (ahead > 0) InfoRow("Còn phải đóng thay", Money.format(ahead))
                        TextButton(onClick = {
                            app.scope.launch {
                                val id = app.repo.coveredToDebt(g, s.copy(name = name.trim(), phone = phone.trim()), covered + ahead)
                                app.message("Đã ghi khoản nợ ${Money.format(covered + ahead)}", "Xem") { onDebt(id) }
                            }
                            onDismiss()
                        }) { Text("Ghi ${Money.format(covered + ahead)} thành khoản nợ") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val f = if (dropped) from.toIntOrNull()?.coerceIn(1, g.slots) else null
                app.scope.launch {
                    app.repo.saveSlot(s.copy(name = name.trim(), phone = phone.trim()))
                    if (f != s.droppedFrom) app.repo.dropSlot(g, s.copy(name = name.trim(), phone = phone.trim()), f)
                }
                onDismiss()
            }) { Text("Lưu") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Hủy") } },
    )
}

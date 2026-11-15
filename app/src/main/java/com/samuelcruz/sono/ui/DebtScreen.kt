package com.samuelcruz.sono.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.samuelcruz.sono.core.Dates
import com.samuelcruz.sono.core.DebtPlan
import com.samuelcruz.sono.core.Direction
import com.samuelcruz.sono.core.InstallmentStatus
import com.samuelcruz.sono.core.Money
import com.samuelcruz.sono.core.PenaltyMode
import com.samuelcruz.sono.core.percent
import com.samuelcruz.sono.data.Debt
import com.samuelcruz.sono.data.DebtState
import com.samuelcruz.sono.data.Payment
import com.samuelcruz.sono.data.Photos
import kotlinx.coroutines.launch

/** Which dialog is open on the debt page. */
private sealed interface DebtDialog {
    data object Pay : DebtDialog
    data class PayInstallment(val number: Int) : DebtDialog
    data class EditPayment(val payment: Payment) : DebtDialog
    data object TopUp : DebtDialog
    data object Extend : DebtDialog
    data class Postpone(val number: Int) : DebtDialog
}

@Composable
fun DebtScreen(debtId: Long, onBack: () -> Unit, onEdit: () -> Unit, onIou: () -> Unit) {
    val app = app()
    val context = LocalContext.current
    val debt by app.db.debts().observe(debtId).collectAsState(null)
    val payments by app.db.payments().observeFor(debtId).collectAsState(emptyList())
    val people by app.db.people().observeAll().collectAsState(emptyList())
    val today = rememberToday()
    val money = LocalMoneyColors.current
    var dialog by remember { mutableStateOf<DebtDialog?>(null) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    var settling by rememberSaveable { mutableStateOf(false) }
    var asking by rememberSaveable { mutableStateOf(false) }
    var bigPhoto by rememberSaveable { mutableStateOf(false) }
    val chiTieu = remember { app.prefs.chiTieuLink && ChiTieu.installed(context) }
    val d = debt ?: return
    val s = remember(d, payments, today) { DebtState(d, payments, today) }
    val name = people.firstOrNull { it.id == d.personId }?.name ?: ""
    val color = money.of(d.direction)
    val lent = d.direction == Direction.LENT

    /** Money received (I lent) = income in Chi Tiêu; money paid back (I borrowed) = expense. */
    fun toChiTieu(amount: Long, what: String) =
        ChiTieu.add(context, income = lent, amount = amount, note = (if (lent) "$name trả nợ" else "Trả nợ $name") + what)

    SimpleScaffold(
        if (lent) "$name nợ bạn" else "Bạn nợ $name", onBack,
        actions = {
            IconButton(onClick = onIou) { Icon(Icons.Default.Description, "Giấy xác nhận nợ") }
            IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Sửa") }
            IconButton(onClick = { deleting = true }) { Icon(Icons.Default.Delete, "Xóa") }
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = ScreenPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Card {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (d.closed) "Đã xong" else "Còn lại", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                            if (s.overdueDays > 0) Tag("Quá hạn ${s.overdueDays} ngày", money.late)
                            if (d.closed) Tag("Tất toán ${Dates.format(d.closedDay!!)}", MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(Money.format(if (d.closed) s.paid else s.left), style = MaterialTheme.typography.headlineMedium, color = color)
                        if (s.left < 0) Text("Trả dư ${Money.format(-s.left)}", style = MaterialTheme.typography.bodySmall, color = money.late)
                        Spacer(Modifier.height(10.dp))
                        val total = s.principal + s.interest + s.penalty
                        if (total > 0) Progress(s.paid.toFloat() / total, color)
                    }
                    InfoRow("Tiền gốc", Money.format(d.amount))
                    if (s.topUps.isNotEmpty()) {
                        InfoRow(if (lent) "Cho mượn thêm" else "Vay thêm", Money.format(s.principal - d.amount))
                        InfoRow("Tổng gốc", Money.format(s.principal), bold = true)
                    }
                    if (d.hasInterest) InfoRow(if (s.plan != null) "Tổng lãi" else "Lãi đến hôm nay", Money.format(s.interest))
                    if (s.penalty > 0) InfoRow("Tiền phạt trễ hạn", Money.format(s.penalty), money.late)
                    InfoRow("Đã trả", Money.format(s.paid))
                    s.nextDueDay?.let { InfoRow("Hạn kế tiếp", "${Dates.format(it)} (${Dates.relative(it, today)})", if (it < today) money.late else Color.Unspecified) }
                    if (s.nextDueDay != null && s.plan != null) InfoRow("Số tiền kỳ tới", Money.format(s.nextDueAmount), bold = true)
                    InfoRow(if (lent) "Ngày cho mượn" else "Ngày mượn", Dates.format(d.startDay))
                    InfoRow("Cách trả", planText(d))
                    InfoRow("Lãi suất", interestText(d))
                    if (d.penaltyMode != PenaltyMode.NONE) InfoRow("Phạt trễ hạn", penaltyText(d))
                    InfoRow("Nhắc nhở", if (d.remindDays < 0) "Tắt" else if (d.remindDays == 0) "Đúng ngày đến hạn" else "Trước ${d.remindDays} ngày")
                    if (d.note.isNotBlank()) Text(d.note, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
                    d.photo?.let { photo ->
                        val bitmap by produceState<android.graphics.Bitmap?>(null, photo) { value = Photos.load(context, photo, 1200) }
                        bitmap?.let {
                            Image(
                                it.asImageBitmap(), "Ảnh chứng từ", contentScale = ContentScale.Crop,
                                modifier = Modifier.padding(16.dp).fillMaxWidth().heightIn(max = 220.dp).clip(RoundedCornerShape(16.dp)).clickable { bigPhoto = true },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!d.closed) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { dialog = DebtDialog.Pay }, modifier = Modifier.weight(1f)) {
                                Text(if (lent) "Ghi nhận đã thu" else "Ghi nhận đã trả")
                            }
                            OutlinedButton(onClick = { settling = true }, modifier = Modifier.weight(1f)) { Text("Tất toán") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (d.plan == DebtPlan.ONCE) {
                                OutlinedButton(onClick = { dialog = DebtDialog.Extend }, modifier = Modifier.weight(1f)) { Text("Gia hạn") }
                                OutlinedButton(onClick = { dialog = DebtDialog.TopUp }, modifier = Modifier.weight(1f)) { Text(if (lent) "Cho mượn thêm" else "Vay thêm") }
                            } else {
                                s.plan?.next?.let { next ->
                                    OutlinedButton(onClick = { dialog = DebtDialog.Postpone(next.installment.number) }, modifier = Modifier.weight(1f)) {
                                        Text("Dời hạn kỳ ${next.installment.number}")
                                    }
                                }
                            }
                        }
                        if (lent && s.left > 0) {
                            FilledTonalButton(onClick = { asking = true }, modifier = Modifier.fillMaxWidth()) { Text("Nhắc $name trả (kèm mã QR)") }
                        }
                    } else {
                        OutlinedButton(onClick = { app.scope.launch { app.repo.reopen(d) } }, modifier = Modifier.fillMaxWidth()) { Text("Mở lại khoản nợ") }
                    }
                    OutlinedButton(onClick = onIou, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Description, null); Spacer(Modifier.width(8.dp)); Text("Giấy xác nhận nợ (PDF, có chữ ký)")
                    }
                }
            }
            s.plan?.let { plan ->
                item { SectionTitle("Lịch trả (${plan.paidCount}/${d.term} kỳ đã đóng)") }
                item {
                    Card {
                        plan.items.forEach { item ->
                            InstallmentRow(
                                item, today, color, enabled = !d.closed,
                                onToggle = { full ->
                                    val missing = item.left
                                    app.scope.launch {
                                        app.repo.markInstallment(d, item.installment.number, full)
                                        if (full && chiTieu && missing > 0) {
                                            app.message("Đã đánh dấu kỳ ${item.installment.number}", "Ghi vào Chi Tiêu") {
                                                toChiTieu(missing, " kỳ ${item.installment.number}")
                                            }
                                        }
                                    }
                                },
                                onPartial = { dialog = DebtDialog.PayInstallment(item.installment.number) },
                                onPostpone = { dialog = DebtDialog.Postpone(item.installment.number) },
                            )
                        }
                    }
                }
            }
            if (s.payments.isNotEmpty()) {
                item { SectionTitle("Lịch sử (${s.payments.size}) · chạm để sửa") }
                item {
                    Card {
                        s.payments.asReversed().forEach { p ->
                            Row(
                                Modifier.fillMaxWidth().clickable { dialog = DebtDialog.EditPayment(p) }.padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        if (p.isTopUp) (if (lent) "Cho mượn thêm" else "Vay thêm") else if (lent) "Đã thu" else "Đã trả",
                                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        listOfNotNull(Dates.format(p.day), p.installment?.let { "kỳ $it" }, p.note.ifBlank { null }).joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(
                                    (if (p.isTopUp) "+ " else "") + Money.format(p.amount),
                                    style = MaterialTheme.typography.titleSmall, color = if (p.isTopUp) MaterialTheme.colorScheme.onSurface else color,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    when (val dl = dialog) {
        DebtDialog.Pay, is DebtDialog.PayInstallment -> {
            val item = (dl as? DebtDialog.PayInstallment)?.let { s.plan?.items?.getOrNull(it.number - 1) }
            PaymentDialog(
                title = if (item != null) "Đóng kỳ ${item.installment.number}" else if (lent) "Ghi nhận đã thu" else "Ghi nhận đã trả",
                suggested = item?.left ?: (if (s.plan != null) s.nextDueAmount else s.left.coerceAtLeast(0)),
                chiTieu = chiTieu,
                onDismiss = { dialog = null },
            ) { amount, day, note, toCt ->
                app.scope.launch { app.repo.addPayment(Payment(debtId = d.id, amount = amount, day = day, note = note, installment = item?.installment?.number)) }
                if (toCt) toChiTieu(amount, item?.let { " kỳ ${it.installment.number}" }.orEmpty())
            }
        }
        is DebtDialog.EditPayment -> {
            val p = dl.payment
            PaymentDialog(
                title = if (p.isTopUp) "Sửa khoản mượn thêm" else "Sửa lần trả",
                suggested = 0, initial = p,
                onDismiss = { dialog = null },
                onDelete = { app.scope.launch { app.repo.deletePayment(p) } },
            ) { amount, day, note, _ ->
                app.scope.launch { app.repo.updatePayment(p.copy(amount = amount, day = day, note = note)) }
            }
        }
        DebtDialog.TopUp -> PaymentDialog(
            title = if (lent) "Cho $name mượn thêm" else "Mượn $name thêm",
            suggested = 0, chiTieu = chiTieu,
            onDismiss = { dialog = null },
        ) { amount, day, note, toCt ->
            app.scope.launch { app.repo.topUp(d, amount, day, note) }
            // Lending more = money out (expense); borrowing more = money in (income).
            if (toCt) ChiTieu.add(context, income = !lent, amount = amount, note = if (lent) "Cho $name mượn thêm" else "Mượn $name thêm")
        }
        DebtDialog.Extend -> DayDialog("Gia hạn đến ngày", d.dueDay ?: today, onDismiss = { dialog = null }) { day, _ ->
            app.scope.launch { app.repo.extend(d, day) }
        }
        is DebtDialog.Postpone -> {
            val item = s.plan?.items?.getOrNull(dl.number - 1)
            if (item != null) {
                DayDialog(
                    "Dời hạn kỳ ${dl.number}", item.installment.due.toEpochDay(), onDismiss = { dialog = null },
                    option = "Dời luôn các kỳ sau một khoảng như vậy",
                ) { day, later -> app.scope.launch { app.repo.postpone(d, dl.number, day, later) } }
            }
        }
        null -> Unit
    }
    if (settling) {
        ConfirmDialog(
            "Tất toán khoản nợ?",
            if (s.left > 0) "Vẫn còn ${Money.format(s.left)} chưa trả. Tất toán nghĩa là xem như đã xong (ví dụ xóa nợ phần còn lại). Bạn có thể mở lại sau."
            else "Đánh dấu khoản này đã xong.",
            "Tất toán", danger = false,
            onConfirm = { app.scope.launch { app.repo.settle(d) } }, onDismiss = { settling = false },
        )
    }
    if (deleting) {
        ConfirmDialog(
            "Xóa khoản nợ?", "Xóa khoản ${Money.format(d.amount)} và ${s.payments.size} lần trả. Không hoàn tác được.", "Xóa",
            onConfirm = { app.scope.launch { app.repo.deleteDebt(d) }; onBack() }, onDismiss = { deleting = false },
        )
    }
    if (asking) {
        val amount = if (s.plan != null && s.overdueAmount == 0L) s.nextDueAmount else if (s.plan != null) s.overdueAmount else s.left
        val what = when {
            s.plan != null && s.overdueAmount > 0 -> "các kỳ đã quá hạn"
            s.plan != null -> "kỳ ${s.plan.next?.installment?.number}/${d.term}" + (s.nextDueDay?.let { " (hạn ${Dates.format(it)})" }.orEmpty())
            else -> "khoản mượn ngày ${Dates.format(d.startDay)}" + (d.dueDay?.let { " (hạn ${Dates.format(it)})" }.orEmpty())
        }
        QrDialog(
            "Nhắc $name trả", amount, "Tra no",
            { account -> "Chào $name, mình nhắc nhẹ $what: ${Money.format(amount)}. Tổng còn lại ${Money.format(s.left)}.${account?.let { "\n$it" }.orEmpty()}\nCảm ơn nhé!" },
            onDismiss = { asking = false },
        )
    }
    if (bigPhoto && d.photo != null) {
        val bitmap by produceState<android.graphics.Bitmap?>(null, d.photo) { value = Photos.load(context, d.photo, 2000) }
        AlertDialog(
            onDismissRequest = { bigPhoto = false },
            confirmButton = { TextButton(onClick = { bigPhoto = false }) { Text("Đóng") } },
            text = { bitmap?.let { Image(it.asImageBitmap(), null, modifier = Modifier.fillMaxWidth()) } },
        )
    }
}

fun penaltyText(d: Debt): String = when (d.penaltyMode) {
    PenaltyMode.PER_DAY -> "${Money.format(d.penaltyValue.toLong())} mỗi ngày trễ"
    PenaltyMode.PERCENT_PER_DAY -> "${percent(d.penaltyValue, 3)} số tiền trễ mỗi ngày"
    PenaltyMode.ONCE -> "${Money.format(d.penaltyValue.toLong())} mỗi lần trễ"
    else -> "Không"
}

@Composable
private fun InstallmentRow(
    item: InstallmentStatus, today: Long, color: Color, enabled: Boolean,
    onToggle: (Boolean) -> Unit, onPartial: () -> Unit, onPostpone: () -> Unit,
) {
    val money = LocalMoneyColors.current
    val i = item.installment
    val due = i.due.toEpochDay()
    val late = !item.full && due < today
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = item.full, onCheckedChange = onToggle, enabled = enabled)
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(
                "Kỳ ${i.number} · ${Dates.format(due)}",
                style = MaterialTheme.typography.bodyMedium,
                textDecoration = if (item.full) TextDecoration.LineThrough else null,
                color = if (item.full) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                when {
                    item.full -> "Đã đóng đủ" + (item.fullDay?.let { " ngày ${Dates.format(it, short = true)}" }.orEmpty())
                    item.paid > 0 -> "Đã đóng ${Money.format(item.paid)}, thiếu ${Money.format(item.left)}"
                    late -> "Quá hạn ${today - due} ngày"
                    else -> Dates.relative(due, today).replaceFirstChar { it.uppercase() }
                } + if (i.interest > 0) " · gốc ${Money.compact(i.principal)} + lãi ${Money.compact(i.interest)}" else "",
                style = MaterialTheme.typography.bodySmall,
                color = if (late) money.late else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(Money.format(i.total), style = MaterialTheme.typography.titleSmall, color = if (item.full) MaterialTheme.colorScheme.onSurfaceVariant else color)
        if (enabled && !item.full) {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Thêm") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Đóng một phần") }, onClick = { menu = false; onPartial() })
                    DropdownMenuItem(text = { Text("Dời hạn kỳ này") }, onClick = { menu = false; onPostpone() })
                }
            }
        } else Spacer(Modifier.width(12.dp))
    }
}

/** Amount + date + note for one payment; [initial] = editing one (with delete). */
@Composable
fun PaymentDialog(
    title: String,
    suggested: Long,
    onDismiss: () -> Unit,
    initial: Payment? = null,
    chiTieu: Boolean = false,
    onDelete: (() -> Unit)? = null,
    onSave: (Long, Long, String, Boolean) -> Unit,
) {
    var amount by rememberSaveable { mutableStateOf(initial?.amount?.toString() ?: if (suggested > 0) suggested.toString() else "") }
    var day by rememberSaveable { mutableStateOf(initial?.day ?: Dates.todayDay()) }
    var note by rememberSaveable { mutableStateOf(initial?.note.orEmpty()) }
    var toChiTieu by rememberSaveable { mutableStateOf(true) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val value = Money.parse(amount)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AmountField("Số tiền", amount, { amount = it })
                if (suggested > 0) {
                    Text(
                        "Gợi ý: ${Money.format(suggested)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { amount = suggested.toString() },
                    )
                }
                DateField("Ngày", day, { it?.let { d -> day = d } })
                TextInput("Ghi chú (không bắt buộc)", note, { note = it })
                if (chiTieu) {
                    Row(Modifier.clickable { toChiTieu = !toChiTieu }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = toChiTieu, onCheckedChange = { toChiTieu = it })
                        Text("Ghi vào app Chi Tiêu", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (onDelete != null) {
                    TextButton(onClick = { confirmDelete = true }) { Text("Xóa lần này", color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = value != null && value > 0, onClick = { onSave(value!!, day, note.trim(), chiTieu && toChiTieu); onDismiss() }) { Text("Lưu") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Hủy") } },
    )
    if (confirmDelete && onDelete != null) {
        ConfirmDialog(
            "Xóa lần này?", "Số tiền sẽ được tính lại vào khoản nợ.", "Xóa",
            onConfirm = { onDelete(); onDismiss() }, onDismiss = { confirmDelete = false },
        )
    }
}

/** Pick a day, with an optional checkbox [option]. */
@Composable
fun DayDialog(title: String, initial: Long, onDismiss: () -> Unit, option: String? = null, onSave: (Long, Boolean) -> Unit) {
    var day by rememberSaveable { mutableStateOf(initial) }
    var checked by rememberSaveable { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DateField("Ngày mới", day, { it?.let { d -> day = d } })
                if (option != null) {
                    Row(Modifier.clickable { checked = !checked }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = checked, onCheckedChange = { checked = it })
                        Text(option, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(day, option != null && checked); onDismiss() }) { Text("Lưu") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Hủy") } },
    )
}

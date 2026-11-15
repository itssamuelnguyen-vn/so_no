package com.samuelcruz.sono.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.toMutableStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.samuelcruz.sono.core.Dates
import com.samuelcruz.sono.core.Hui
import com.samuelcruz.sono.core.HuiBids
import com.samuelcruz.sono.core.HuiRoundCalc
import com.samuelcruz.sono.core.Money
import com.samuelcruz.sono.core.percent
import com.samuelcruz.sono.data.HuiRole
import com.samuelcruz.sono.data.HuiRound
import kotlinx.coroutines.launch

/**
 * Hold the next round (pick the winner, type the bid or every bid) or look at a held one (who paid, hand over the
 * pot). [edit] reopens a held round's form to fix it.
 */
@Composable
fun RoundScreen(huiId: Long, number: Int, edit: Boolean, onBack: () -> Unit, onEdit: () -> Unit, onDebt: (Long) -> Unit) {
    val v = rememberHuiViews().firstOrNull { it.group.id == huiId } ?: return
    val round = v.rounds.firstOrNull { it.number == number }
    when {
        round != null && !edit -> HeldRound(v, round, onBack, onEdit, onDebt)
        round != null -> RoundForm(v, number, round, onBack)
        number == v.held + 1 -> RoundForm(v, number, null, onBack)
    }
}

private fun bidsSaver() = listSaver<SnapshotStateMap<Int, String>, String>(
    save = { m -> m.entries.map { "${it.key}=${it.value}" } },
    restore = { list -> list.map { it.substringBefore('=').toInt() to it.substringAfter('=') }.toMutableStateMap() },
)

@Composable
private fun RoundForm(v: HuiView, number: Int, existing: HuiRound?, onBack: () -> Unit) {
    val app = app()
    val g = v.group
    val rules = v.rules
    val money = LocalMoneyColors.current
    // Rounds before this one decide who is still live; when editing, later winners cannot win here either.
    val past = v.inputs.filter { it.number < number }
    val takenLater = v.inputs.filter { it.number > number }.map { it.winner }.toSet()
    val candidates = Hui.live(rules, past, number).filter { it !in takenLater }
    val forced = when {
        rules.ownerFirst && number == 1 && v.ownerSlot != null -> v.ownerSlot
        candidates.size == 1 -> candidates.first()
        else -> null
    }
    var winner by rememberSaveable { mutableStateOf(existing?.winner ?: forced ?: -1) }
    var bidText by rememberSaveable { mutableStateOf(existing?.bid?.takeIf { it > 0 }?.toString() ?: "") }
    var day by rememberSaveable { mutableStateOf(existing?.day ?: v.dates[number - 1].toEpochDay()) }
    var note by rememberSaveable { mutableStateOf(existing?.note.orEmpty()) }
    var allBids by rememberSaveable { mutableStateOf(existing?.bids?.isNotEmpty() == true) }
    val bids = rememberSaveable(saver = bidsSaver()) {
        HuiBids.parse(existing?.bids.orEmpty()).map { it.key to it.value.toString() }.toMutableStateMap()
    }
    val hasBid = rules.hasBid(number) && forced == null
    val maxBid = Hui.maxBid(rules, past, number)
    val parsedBids = bids.mapValues { Money.parse(it.value) ?: 0L }.filterValues { it > 0 }
    val top = HuiBids.top(parsedBids)
    // With every bid typed, the highest wins (a tie is settled by drawing lots below).
    LaunchedEffect(allBids, top) { if (allBids && hasBid && top.size == 1) winner = top.first() }
    val bid = when {
        !hasBid -> 0L
        allBids -> parsedBids[winner] ?: 0L
        else -> Money.parse(bidText) ?: 0L
    }
    val bidOk = !hasBid || bid <= maxBid
    val calc = if (winner > 0) Hui.round(rules, past, number, winner, bid) else null
    val rate = if (winner > 0 && !rules.fixedRound(number)) Hui.winnerMonthlyRate(rules, past, number, winner, bid, g.periodDays) else null
    val valid = winner > 0 && (winner in candidates || winner == forced) && bidOk && (!allBids || !hasBid || top.size <= 1 || winner in top)

    fun save() {
        if (!valid) return
        val r = HuiRound(
            g.id, number, winner, calc!!.bid, day, paidOut = existing?.paidOut ?: false, note = note.trim(),
            bids = if (allBids && hasBid) HuiBids.format(parsedBids) else "",
        )
        app.scope.launch {
            if (existing == null) app.repo.addRound(g, r) else app.repo.updateRound(r)
            app.message(if (existing == null) "Đã lưu kỳ $number" else "Đã sửa kỳ $number")
        }
        onBack()
    }

    SimpleScaffold(
        (if (existing == null) "Khui kỳ" else "Sửa kỳ") + " $number/${g.slots}", onBack,
        actions = { TextButton(onClick = ::save, enabled = valid) { Text("Lưu") } },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (existing != null) {
                Text(
                    "Sửa kỳ đã khui: số tiền các kỳ sau được tính lại theo người hốt mới. Đánh dấu đóng tiền vẫn giữ.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DateField("Ngày khui", day, { it?.let { d -> day = d } })
            if (hasBid) {
                PillToggle(listOf("Chỉ ghi người hốt", "Ghi tất cả các thăm"), if (allBids) 1 else 0, listOf(Brand, Brand), { allBids = it == 1 })
            }
            SectionHeader(if (allBids && hasBid) "Thăm của từng chân" else "Ai hốt kỳ này?")
            if (forced != null) {
                Card {
                    Text(
                        v.slot(forced).displayName + when {
                            rules.ownerFirst && number == 1 -> " (chủ hụi hốt kỳ đầu, không bỏ thăm)"
                            else -> " (chân cuối cùng còn sống)"
                        },
                        style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(16.dp),
                    )
                }
            } else if (allBids && hasBid) {
                Card {
                    candidates.forEach { n ->
                        val s = v.slot(n)
                        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = winner == n, onClick = { winner = n }, enabled = top.size != 1)
                            Column(Modifier.weight(1f)) {
                                Text("Chân $n · ${s.displayName}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (n in top && top.size > 1) Text("Bằng thăm", style = MaterialTheme.typography.labelSmall, color = money.late)
                                else if (n in top) Text("Thăm cao nhất", style = MaterialTheme.typography.labelSmall, color = money.collect)
                            }
                            AmountField("Kêu", bids[n].orEmpty(), { bids[n] = it }, Modifier.width(150.dp), supporting = "")
                        }
                    }
                }
                if (top.size > 1) {
                    OutlinedButton(onClick = { winner = top.random() }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Casino, null); Spacer(Modifier.width(8.dp))
                        Text("Bốc thăm giữa ${top.size} chân bằng thăm" + if (winner in top) " (đang chọn chân $winner)" else "")
                    }
                }
                if (!bidOk) Text("Thăm cao nhất vượt mức tối đa ${Money.format(maxBid)}", style = MaterialTheme.typography.bodySmall, color = money.late)
            } else {
                Card {
                    candidates.forEach { n ->
                        val s = v.slot(n)
                        Row(
                            Modifier.fillMaxWidth().clickable { winner = n }.padding(horizontal = 8.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = winner == n, onClick = { winner = n })
                            Text("Chân $n · ${s.displayName}", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (s.mine) Tag("Của tôi", MaterialTheme.colorScheme.primary)
                            if (s.droppedFrom != null) Tag("Bỏ ngang", money.late)
                            Spacer(Modifier.width(8.dp))
                        }
                    }
                }
                if (hasBid) {
                    AmountField(
                        "Tiền kêu (thăm)", bidText, { bidText = it }, isError = !bidOk,
                        supporting = if (!bidOk) "Tối đa ${Money.format(maxBid)}" else "Mỗi chân hụi sống được bớt số này. Tối đa ${Money.format(maxBid)}",
                    )
                }
            }
            when {
                !rules.bidding -> Text("Hụi không lãi: không bỏ thăm, mọi chân đóng đủ.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                rules.fixedBid > 0 && !rules.fixedRound(number) -> Text(
                    "Hụi lãi cố định: người hốt chịu ${Money.format(rules.fixedBid)} cho mỗi chân hụi sống.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            calc?.let { c ->
                SectionHeader("Tiền kỳ này")
                Breakdown(v, c)
                rate?.let { r ->
                    val over = r < 0 && Hui.exceedsLegal(r)
                    Text(
                        (if (r < 0) "Người hốt chịu lãi ≈ " else "Người hốt được lời ≈ ") + percent(kotlin.math.abs(r) * 100) + "/tháng (" +
                            percent(kotlin.math.abs(Hui.yearly(r)) * 100, 1) + "/năm)" +
                            if (over) ". Mức này vượt 20%/năm, giới hạn lãi của Bộ luật Dân sự áp cho hụi có lãi." else "",
                        style = MaterialTheme.typography.bodySmall, color = if (over) money.late else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextInput("Ghi chú", note, { note = it })
            Button(onClick = ::save, enabled = valid, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(if (existing == null) "Lưu kỳ $number" else "Lưu thay đổi")
            }
        }
    }
}

/** Who pays how much and what the winner takes. */
@Composable
private fun Breakdown(v: HuiView, c: HuiRoundCalc) {
    val money = LocalMoneyColors.current
    Card {
        Spacer(Modifier.height(6.dp))
        if (c.liveCount > 0) InfoRow("${c.liveCount} chân hụi sống, mỗi chân đóng", Money.format(c.livePays))
        if (c.deadCount > 0) InfoRow("${c.deadCount} chân hụi chết, mỗi chân đóng", Money.format(c.deadPays))
        InfoRow("Tổng thu", Money.format(c.gross))
        if (c.thao > 0) InfoRow("Tiền thảo cho chủ hụi", "− " + Money.format(c.thao))
        InfoRow("${v.slot(c.winner).displayName} nhận", Money.format(c.net), money.collect, bold = true)
        val mine = v.mine.filter { it.number != c.winner }
        if (mine.isNotEmpty()) InfoRow("Bạn đóng kỳ này", Money.format(mine.sumOf { c.dues[it.number] ?: 0 }), money.pay, bold = true)
        if (v.mine.any { it.number == c.winner }) InfoRow("Bạn hốt kỳ này", Money.format(c.net), money.collect, bold = true)
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun HeldRound(v: HuiView, round: HuiRound, onBack: () -> Unit, onEdit: () -> Unit, onDebt: (Long) -> Unit) {
    val app = app()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val g = v.group
    val calc = v.calcs.first { it.number == round.number }
    val owner = g.role == HuiRole.OWNER
    val money = LocalMoneyColors.current
    var deleting by rememberSaveable { mutableStateOf(false) }
    var qrFor by remember { mutableStateOf<Int?>(null) }
    var debtFor by remember { mutableStateOf<Int?>(null) }
    val payers = if (owner) calc.dues.keys.toList() else v.mine.map { it.number }.filter { it != calc.winner }
    val isLast = round.number == v.held
    val chiTieu = remember { app.prefs.chiTieuLink && ChiTieu.installed(context) }
    val myDue = v.mine.filter { it.number != calc.winner }.sumOf { calc.dues[it.number] ?: 0 }
    val iWon = v.mine.any { it.number == calc.winner }
    val bids = HuiBids.parse(round.bids)

    SimpleScaffold(
        "Kỳ ${round.number}/${g.slots} · ${Dates.format(round.day, short = true)}", onBack,
        actions = {
            IconButton(onClick = { scope.launch { QrShare.share(context, announcement(v, calc, round.day, if (owner) QrShare.accountLine(app.prefs) else null), null) } }) {
                Icon(Icons.Default.Share, "Gửi thông báo kỳ")
            }
            IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Sửa kỳ") }
            if (isLast) IconButton(onClick = { deleting = true }) { Icon(Icons.Default.Delete, "Xóa kỳ") }
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("${v.slot(calc.winner).displayName} hốt", style = MaterialTheme.typography.titleLarge)
                    Text(
                        listOfNotNull(
                            Dates.long(Dates.of(round.day)),
                            if (calc.bid > 0) "kêu ${Money.format(calc.bid)}" else "không bỏ thăm",
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (round.note.isNotBlank()) Text(round.note, style = MaterialTheme.typography.bodyMedium)
                }
                if (bids.size > 1) {
                    Text("Các thăm", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 16.dp))
                    bids.entries.sortedByDescending { it.value }.forEach { (slot, amount) ->
                        InfoRow("Chân $slot · ${v.slot(slot).displayName}", Money.format(amount), if (slot == calc.winner) money.collect else Color.Unspecified)
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
            Breakdown(v, calc)
            if (chiTieu && (myDue > 0 || iWon)) {
                OutlinedButton(
                    onClick = {
                        if (iWon) ChiTieu.add(context, income = true, amount = calc.net, note = "Hốt hụi ${g.name} kỳ ${round.number}")
                        else ChiTieu.add(context, income = false, amount = myDue, note = "Đóng hụi ${g.name} kỳ ${round.number}")
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (iWon) "Ghi tiền hốt vào Chi Tiêu" else "Ghi tiền đóng hụi vào Chi Tiêu") }
            }
            if (owner || iWon) {
                Card {
                    SwitchRow(
                        if (owner) "Đã giao tiền cho người hốt" else "Đã nhận tiền hốt",
                        round.paidOut, { app.scope.launch { app.repo.updateRound(round.copy(paidOut = it)) } },
                        subtitle = Money.format(calc.net),
                    )
                }
            }
            if (payers.isNotEmpty()) {
                val unpaid = payers.filter { !v.isPaid(round.number, it) }
                SectionTitle(
                    if (owner) "Đóng tiền (${payers.size - unpaid.size}/${payers.size})" else "Tôi đã đóng chưa?",
                    action = if (unpaid.isNotEmpty() && payers.size > 1) "Đánh dấu tất cả" else null,
                    onAction = { app.scope.launch { app.repo.setPaid(g.id, round.number, unpaid, true) } },
                )
                Card {
                    payers.forEach { n ->
                        val s = v.slot(n)
                        val paid = v.isPaid(round.number, n)
                        val covered = v.paid.any { it.number == round.number && it.slot == n && it.covered }
                        val due = calc.dues[n] ?: 0
                        var menu by remember { mutableStateOf(false) }
                        Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = paid, onCheckedChange = { app.scope.launch { app.repo.setPaid(g.id, round.number, listOf(n), it) } })
                            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                                Text("Chân $n · ${s.displayName}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    (if (n in v.inputs.filter { it.number < round.number }.map { it.winner }) "hụi chết" else "hụi sống") +
                                        when {
                                            covered -> " · chủ hụi đóng thay"
                                            paid -> " · đã đóng"
                                            else -> " · chưa đóng"
                                        },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (paid && !covered) MaterialTheme.colorScheme.onSurfaceVariant else money.late,
                                )
                            }
                            Text(Money.format(due), style = MaterialTheme.typography.titleSmall)
                            if (owner && !paid && !s.mine) {
                                Box {
                                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Thêm") }
                                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                        DropdownMenuItem(text = { Text("Gửi mã QR đóng hụi") }, onClick = { menu = false; qrFor = n })
                                        DropdownMenuItem(text = { Text("Đóng thay, ghi thành khoản nợ") }, onClick = { menu = false; debtFor = n })
                                    }
                                }
                            } else Spacer(Modifier.width(12.dp))
                        }
                    }
                }
            }
            OutlinedButton(
                onClick = { scope.launch { QrShare.share(context, announcement(v, calc, round.day, if (owner) QrShare.accountLine(app.prefs) else null), null) } },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Share, null); Spacer(Modifier.width(8.dp)); Text("Gửi thông báo kỳ vào nhóm")
            }
        }
    }
    qrFor?.let { n ->
        val s = v.slot(n)
        val due = calc.dues[n] ?: 0
        QrDialog(
            "${s.displayName} đóng kỳ ${round.number}", due, "Hui ${g.name} ky ${round.number}",
            { account ->
                "Chào ${s.displayName}, hụi ${g.name} kỳ ${round.number} (${Dates.format(round.day)}) chân $n đóng ${Money.format(due)}." +
                    account?.let { "\n$it" }.orEmpty()
            },
            onDismiss = { qrFor = null },
        )
    }
    debtFor?.let { n ->
        val s = v.slot(n)
        val due = calc.dues[n] ?: 0
        ConfirmDialog(
            "Đóng thay cho ${s.displayName}?",
            "Chân $n được đánh dấu đã đóng kỳ ${round.number} (chủ hụi đóng thay ${Money.format(due)}), và Sổ nợ ghi một khoản \"${s.displayName} nợ bạn ${Money.format(due)}\".",
            "Ghi nợ", danger = false,
            onConfirm = {
                app.scope.launch {
                    val id = app.repo.dueToDebt(g, s, round.number, due)
                    app.message("Đã ghi khoản nợ của ${s.displayName}", "Xem") { onDebt(id) }
                }
            },
            onDismiss = { debtFor = null },
        )
    }
    if (deleting) {
        ConfirmDialog(
            "Xóa kỳ ${round.number}?", "Xóa kết quả khui và các đánh dấu đóng tiền của kỳ này để khui lại.", "Xóa",
            onConfirm = { app.scope.launch { app.repo.deleteRound(round) }; onBack() }, onDismiss = { deleting = false },
        )
    }
}

/** The round's message for the group chat. */
private fun announcement(v: HuiView, c: HuiRoundCalc, day: Long, account: String?): String {
    val g = v.group
    return buildString {
        append("📢 Hụi ${g.name} kỳ ${c.number}/${g.slots} (${Dates.format(day)}): ${v.slot(c.winner).displayName} hốt")
        if (c.bid > 0) append(", kêu ${Money.format(c.bid)}")
        append(".\n")
        if (c.liveCount > 0) append("Hụi sống đóng ${Money.format(c.livePays)}.\n")
        if (c.deadCount > 0) append("Hụi chết đóng ${Money.format(c.deadPays)}.\n")
        append("Người hốt nhận ${Money.format(c.net)}.")
        if (account != null) append("\n$account")
    }
}

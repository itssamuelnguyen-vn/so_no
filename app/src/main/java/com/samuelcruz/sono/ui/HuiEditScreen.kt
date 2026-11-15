package com.samuelcruz.sono.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.samuelcruz.sono.core.Dates
import com.samuelcruz.sono.core.Hui
import com.samuelcruz.sono.core.HuiCycle
import com.samuelcruz.sono.core.HuiRoundInput
import com.samuelcruz.sono.core.HuiRules
import com.samuelcruz.sono.core.Money
import com.samuelcruz.sono.core.ThaoMode
import com.samuelcruz.sono.core.percent
import com.samuelcruz.sono.data.HuiGroup
import com.samuelcruz.sono.data.HuiRole
import com.samuelcruz.sono.data.HuiSlot
import kotlinx.coroutines.launch

private const val C_DAY = 0
private const val C_WEEK = 1
private const val C_HALF = 2
private const val C_MONTH = 3
private const val C_LUNAR = 4
private const val C_CUSTOM = 5

private const val P_COMMON = 0
private const val P_OWNER_NO_THAO = 1
private const val P_NO_OWNER = 2
private const val P_NO_BID = 3
private const val P_FIXED = 4

private const val M_BID = 0
private const val M_FIXED = 1
private const val M_NONE = 2

private fun stringListSaver() = listSaver<SnapshotStateList<String>, String>({ it.toList() }, { it.toMutableStateList() })
private fun boolListSaver() = listSaver<SnapshotStateList<Boolean>, Boolean>({ it.toList() }, { it.toMutableStateList() })

/** Create or edit a dây hụi. The rules are chosen here, before the first round, with a preview of the money. */
@Composable
fun HuiEditScreen(huiId: Long, onDone: (Long?) -> Unit) {
    val app = app()
    val scope = rememberCoroutineScope()
    var loaded by rememberSaveable { mutableStateOf(huiId == 0L) }
    var existing by remember { mutableStateOf<HuiGroup?>(null) }
    var roundsHeld by rememberSaveable { mutableStateOf(0) }

    var name by rememberSaveable { mutableStateOf("") }
    var role by rememberSaveable { mutableStateOf(HuiRole.OWNER) }
    var ownerName by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    var count by rememberSaveable { mutableStateOf("10") }
    var cycleChoice by rememberSaveable { mutableStateOf(C_MONTH) }
    var everyDays by rememberSaveable { mutableStateOf("10") }
    var startDay by rememberSaveable { mutableStateOf(Dates.todayDay()) }
    var bidding by rememberSaveable { mutableStateOf(true) }
    var fixedMode by rememberSaveable { mutableStateOf(false) }
    var fixedBid by rememberSaveable { mutableStateOf("") }
    var ownerFirst by rememberSaveable { mutableStateOf(true) }
    var thaoMode by rememberSaveable { mutableStateOf(ThaoMode.FIXED) }
    var thaoValue by rememberSaveable { mutableStateOf("") }
    var thaoLast by rememberSaveable { mutableStateOf(true) }
    var remind by rememberSaveable { mutableStateOf(true) }
    var remindDays by rememberSaveable { mutableStateOf("1") }
    var note by rememberSaveable { mutableStateOf("") }
    var exampleBid by rememberSaveable { mutableStateOf("") }
    var showAllPreview by rememberSaveable { mutableStateOf(false) }
    val names = rememberSaveable(saver = stringListSaver()) { mutableListOf<String>().toMutableStateList() }
    val mine = rememberSaveable(saver = boolListSaver()) { mutableListOf<Boolean>().toMutableStateList() }

    LaunchedEffect(huiId) {
        if (huiId == 0L) return@LaunchedEffect
        val g = app.db.hui().group(huiId) ?: return@LaunchedEffect
        existing = g
        roundsHeld = app.db.hui().rounds(huiId).size
        if (loaded) return@LaunchedEffect
        name = g.name; role = g.role; ownerName = g.ownerName
        amount = g.amount.toString(); count = g.slots.toString()
        cycleChoice = when {
            g.cycle == HuiCycle.MONTHLY -> C_MONTH
            g.cycle == HuiCycle.LUNAR_MONTHLY -> C_LUNAR
            g.everyDays == 1 -> C_DAY
            g.everyDays == 7 -> C_WEEK
            g.everyDays == 15 -> C_HALF
            else -> C_CUSTOM
        }
        everyDays = g.everyDays.toString()
        startDay = g.startDay
        bidding = g.bidding; ownerFirst = g.ownerFirst
        fixedMode = g.bidding && g.fixedBid > 0
        fixedBid = if (g.fixedBid > 0) g.fixedBid.toString() else ""
        thaoMode = g.thaoMode
        thaoValue = if (g.thaoMode == ThaoMode.PERCENT) percent(g.thaoValue / 10.0, 1).removeSuffix("%") else if (g.thaoValue > 0) g.thaoValue.toString() else ""
        thaoLast = g.thaoLast
        remind = g.remindDays >= 0
        remindDays = (if (g.remindDays >= 0) g.remindDays else 1).toString()
        note = g.note
        val slots = app.db.hui().slots(huiId).associateBy { it.number }
        names.clear(); mine.clear()
        for (n in 1..g.slots) {
            names += slots[n]?.name.orEmpty()
            mine += slots[n]?.mine == true
        }
        loaded = true
    }

    val slotCount = count.toIntOrNull()?.coerceIn(2, 200)
    // Keep one name / "mine" flag per share as the number of shares changes.
    LaunchedEffect(slotCount, loaded) {
        if (!loaded || slotCount == null) return@LaunchedEffect
        while (names.size < slotCount) {
            val n = names.size + 1
            names += if (n == 1 && role == HuiRole.OWNER) "Tôi" else ""
            mine += n == 1 && role == HuiRole.OWNER
        }
        while (names.size > slotCount) { names.removeAt(names.lastIndex); mine.removeAt(mine.lastIndex) }
    }

    val face = Money.parse(amount)
    val locked = roundsHeld > 0
    val cycle = when (cycleChoice) {
        C_MONTH -> HuiCycle.MONTHLY
        C_LUNAR -> HuiCycle.LUNAR_MONTHLY
        else -> HuiCycle.DAYS
    }
    val days = when (cycleChoice) {
        C_DAY -> 1
        C_WEEK -> 7
        C_HALF -> 15
        C_CUSTOM -> everyDays.toIntOrNull()?.coerceIn(1, 365) ?: 10
        else -> 30
    }
    val thao: Long = when (thaoMode) {
        ThaoMode.FIXED -> Money.parse(thaoValue) ?: 0
        ThaoMode.PERCENT -> ((parseDecimal(thaoValue) ?: 0.0) * 10).toLong()
        else -> 0
    }
    val fixed = if (bidding && fixedMode) Money.parse(fixedBid) ?: 0 else 0
    val rules = if (face != null && slotCount != null) HuiRules(face, slotCount, ownerFirst, bidding, thaoMode, thao, thaoLast, fixed) else null
    val valid = name.isNotBlank() && rules != null && face!! > 0 && Hui.thao(rules, slotCount!!) < face * (slotCount - 1) &&
        (!(bidding && fixedMode) || fixed in 1 until face)
    val mode = when {
        !bidding -> M_NONE
        fixedMode -> M_FIXED
        else -> M_BID
    }

    fun applyPreset(p: Int) {
        when (p) {
            P_COMMON -> { bidding = true; fixedMode = false; ownerFirst = true; if (thaoMode == ThaoMode.NONE) thaoMode = ThaoMode.FIXED; thaoLast = true }
            P_OWNER_NO_THAO -> { bidding = true; fixedMode = false; ownerFirst = true; thaoMode = ThaoMode.NONE }
            P_NO_OWNER -> { bidding = true; fixedMode = false; ownerFirst = false; if (thaoMode == ThaoMode.NONE) thaoMode = ThaoMode.FIXED }
            P_NO_BID -> { bidding = false; fixedMode = false; ownerFirst = true; thaoMode = ThaoMode.NONE }
            P_FIXED -> { bidding = true; fixedMode = true; ownerFirst = true }
        }
    }
    val preset = when {
        !bidding -> P_NO_BID
        fixedMode -> P_FIXED
        !ownerFirst -> P_NO_OWNER
        thaoMode == ThaoMode.NONE -> P_OWNER_NO_THAO
        else -> P_COMMON
    }

    fun save() {
        if (!valid) return
        scope.launch {
            val g = (existing ?: HuiGroup(name = "", role = role, amount = 0, slots = 0, startDay = startDay)).copy(
                name = name.trim(), role = role, ownerName = ownerName.trim(),
                amount = face!!, slots = slotCount!!, cycle = cycle, everyDays = days, startDay = startDay,
                ownerFirst = ownerFirst, bidding = bidding, thaoMode = thaoMode, thaoValue = thao, thaoLast = thaoLast,
                fixedBid = fixed,
                remindDays = if (remind) (remindDays.toIntOrNull() ?: 0).coerceIn(0, 30) else -1,
                note = note.trim(),
            )
            val slots = (1..slotCount).map { n ->
                HuiSlot(g.id, n, names.getOrElse(n - 1) { "" }.trim(), mine = mine.getOrElse(n - 1) { false })
            }
            val id = app.repo.saveHui(g, slots)
            app.message(if (existing == null) "Đã tạo dây hụi" else "Đã lưu")
            onDone(id)
        }
    }

    SimpleScaffold(
        if (huiId == 0L) "Tạo dây hụi" else "Sửa dây hụi", onBack = { onDone(null) },
        actions = { TextButton(onClick = ::save, enabled = valid) { Text("Lưu") } },
    ) { pad ->
        if (!loaded) return@SimpleScaffold
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextInput("Tên dây hụi", name, { name = it })
            PillToggle(listOf("Tôi là chủ hụi", "Tôi là hụi viên"), if (role == HuiRole.OWNER) 0 else 1, listOf(Brand, Brand), {
                role = if (it == 0) HuiRole.OWNER else HuiRole.MEMBER
            })
            Text(
                if (role == HuiRole.OWNER) "Bạn đứng ra thu tiền của các chân và giao cho người hốt. App giúp đánh dấu ai đã đóng, ai còn thiếu."
                else "Bạn chơi một hoặc vài chân trong dây của người khác. App tính kỳ này bạn phải đóng bao nhiêu và khi hốt được nhận bao nhiêu.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (role == HuiRole.MEMBER) TextInput("Tên chủ hụi", ownerName, { ownerName = it })
            if (locked) {
                Card {
                    Text(
                        "Dây này đã khui $roundsHeld kỳ nên không đổi được mệnh giá, số chân và luật chơi (để số tiền các kỳ cũ không bị sai).",
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp),
                    )
                }
            }
            if (!locked) {
                AmountField("Mệnh giá mỗi chân (tiền hụi)", amount, { amount = it })
                NumberField("Số chân", count, { count = it }, suffix = "chân", supporting = "Mỗi chân hốt một kỳ, nên dây có bấy nhiêu kỳ")
            }

            SectionHeader("Lịch khui hụi")
            ChoiceChips(
                listOf(C_DAY to "Hụi ngày", C_WEEK to "Hụi tuần", C_HALF to "Nửa tháng", C_MONTH to "Tháng (dương lịch)", C_LUNAR to "Tháng (âm lịch)", C_CUSTOM to "Mỗi N ngày"),
                cycleChoice, { cycleChoice = it },
            )
            if (cycleChoice == C_CUSTOM) NumberField("Số ngày mỗi kỳ", everyDays, { everyDays = it }, suffix = "ngày")
            val start = Dates.of(startDay)
            DateField(
                "Ngày khui kỳ 1", startDay, { it?.let { d -> startDay = d } },
                supporting = when (cycleChoice) {
                    C_LUNAR -> "Ngày ${Dates.lunarShort(start)}; các kỳ sau khui ngày ${com.samuelcruz.sono.core.HuiSchedule.lunar(start).day} âm lịch hằng tháng"
                    C_MONTH -> "Các kỳ sau khui ngày ${start.dayOfMonth} hằng tháng"
                    else -> null
                },
            )
            if (slotCount != null) {
                val last = com.samuelcruz.sono.core.HuiSchedule.date(cycle, days, start, slotCount)
                Text("Kỳ cuối (kỳ $slotCount): ${Dates.long(last)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SwitchRow("Nhắc trước ngày khui", remind, { remind = it })
            if (remind) NumberField("Nhắc trước", remindDays, { remindDays = it }, suffix = "ngày", max = 2, supporting = "0 = chỉ nhắc đúng ngày khui")

            SectionHeader("Luật chơi")
            if (!locked) {
                Text("Chọn nhanh kiểu hay gặp, rồi chỉnh từng mục bên dưới nếu dây của bạn khác.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ChoiceChips(
                    listOf(
                        P_COMMON to "Chủ hốt đầu, có thảo",
                        P_OWNER_NO_THAO to "Chủ hốt đầu thay thảo",
                        P_NO_OWNER to "Bỏ thăm từ kỳ 1",
                        P_NO_BID to "Hụi không lãi",
                        P_FIXED to "Hụi lãi cố định",
                    ),
                    preset, ::applyPreset,
                )
            }
            Card {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Hình thức", style = MaterialTheme.typography.bodyLarge)
                    if (!locked) {
                        ChoiceChips(listOf(M_BID to "Bỏ thăm", M_FIXED to "Lãi cố định", M_NONE to "Không lãi"), mode, { m ->
                            bidding = m != M_NONE
                            fixedMode = m == M_FIXED
                        })
                    }
                    Text(
                        when (mode) {
                            M_BID -> "Mỗi kỳ ai kêu thăm cao nhất thì hốt. Hụi sống đóng mệnh giá trừ tiền thăm, hụi chết đóng đủ."
                            M_FIXED -> "Không bỏ thăm: hốt theo thứ tự thỏa thuận, người hốt chịu một khoản lãi cố định. Hụi sống đóng mệnh giá trừ khoản đó, hụi chết đóng đủ."
                            else -> "Không bỏ thăm: hốt theo thứ tự thỏa thuận, kỳ nào mọi chân cũng đóng đủ mệnh giá."
                        },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (mode == M_FIXED && !locked) {
                        AmountField("Lãi cố định mỗi kỳ (trừ cho mỗi chân hụi sống)", fixedBid, { fixedBid = it })
                    }
                }
                SwitchRow(
                    "Chủ hụi hốt kỳ đầu", ownerFirst, { ownerFirst = it }, enabled = !locked,
                    subtitle = if (ownerFirst) "Kỳ 1 chân số 1 (chủ hụi) hốt, không bỏ thăm, không thảo; mọi người đóng đủ." else "Kỳ 1 cũng bỏ thăm như các kỳ khác.",
                )
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Tiền thảo (người hốt trả cho chủ hụi)", style = MaterialTheme.typography.bodyLarge)
                    if (!locked) {
                        ChoiceChips(listOf(ThaoMode.NONE to "Không có", ThaoMode.FIXED to "Số tiền cố định", ThaoMode.PERCENT to "% mệnh giá"), thaoMode, { thaoMode = it })
                        when (thaoMode) {
                            ThaoMode.FIXED -> AmountField("Tiền thảo mỗi kỳ", thaoValue, { thaoValue = it })
                            ThaoMode.PERCENT -> DecimalField("Phần trăm mệnh giá", thaoValue, { thaoValue = it }, suffix = "%")
                        }
                    } else {
                        Text(existing?.let { thaoText(it) }.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (thaoMode != ThaoMode.NONE) {
                    SwitchRow("Kỳ cuối cũng đóng thảo", thaoLast, { thaoLast = it }, enabled = !locked)
                }
            }

            if (rules != null && face!! > 0) {
                SectionHeader("Xem trước số tiền")
                Preview(rules, exampleBid, { exampleBid = it }, if (cycle == HuiCycle.DAYS) days.toDouble() else Hui.periodDays(cycle, days), showAllPreview) { showAllPreview = it }
            }

            if (slotCount != null) {
                SectionHeader("Các chân hụi")
                Text(
                    "Ghi tên từng chân (để trống cũng được). Đánh dấu \"Của tôi\" cho chân bạn giữ. Một người giữ nhiều chân thì ghi tên giống nhau.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Card {
                    for (i in 0 until minOf(slotCount, names.size)) {
                        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextInput(
                                "Chân ${i + 1}" + if (i == 0 && ownerFirst) " (chủ hụi)" else "",
                                names[i], { names[i] = it }, Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(4.dp))
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Checkbox(checked = mine[i], onCheckedChange = { mine[i] = it })
                                Text("Của tôi", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                if (role == HuiRole.MEMBER && mine.none { it }) {
                    Text("Nhớ đánh dấu chân của bạn để app tính tiền bạn phải đóng và được nhận.", style = MaterialTheme.typography.bodySmall, color = LocalMoneyColors.current.late)
                }
            }
            TextInput("Ghi chú", note, { note = it }, singleLine = false)
            Button(onClick = ::save, enabled = valid, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(if (huiId == 0L) "Tạo dây hụi" else "Lưu") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** What each round would look like if every bid were [bidText]: live/dead dues and the pot. */
@Composable
private fun Preview(rules: HuiRules, bidText: String, onBid: (String) -> Unit, periodDays: Double, showAll: Boolean, onShowAll: (Boolean) -> Unit) {
    val defaultBid = (rules.amount / 10 / 1000 * 1000).coerceAtLeast(0)
    val bid = if (rules.bidding) (Money.parse(bidText) ?: defaultBid).coerceIn(0, (rules.amount - 1).coerceAtLeast(0)) else 0L
    val rows = remember(rules, bid) {
        val past = ArrayList<HuiRoundInput>()
        (1..rules.slots).map { n ->
            val live = Hui.live(rules, past, n)
            val winner = if (rules.ownerFirst && n == 1) 1 else live.first { !(rules.ownerFirst && it == 1) || live.size == 1 }
            val calc = Hui.round(rules, past, n, winner, bid)
            val rate = Hui.winnerMonthlyRate(rules, past, n, winner, bid, periodDays)
            past += HuiRoundInput(n, winner, calc.bid)
            calc to rate
        }
    }
    val money = LocalMoneyColors.current
    Card {
        if (rules.bidding && rules.fixedBid <= 0) {
            Column(Modifier.padding(16.dp)) {
                AmountField("Giả sử mỗi kỳ kêu thăm", bidText.ifEmpty { "" }, onBid, supporting = "Để trống: lấy ví dụ ${Money.format(defaultBid)} (10% mệnh giá)")
            }
        }
        val shown = if (showAll || rows.size <= 5) rows else rows.take(3) + rows.takeLast(1)
        shown.forEachIndexed { idx, (c, rate) ->
            if (!showAll && rows.size > 5 && idx == 3) {
                Text("…", modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                Row {
                    Text(
                        "Kỳ ${c.number}" + when {
                            rules.ownerFirst && c.number == 1 -> " · chủ hụi hốt"
                            c.number == rules.slots -> " · chân cuối hốt"
                            c.bid > 0 -> " · kêu ${Money.compact(c.bid)}"
                            else -> ""
                        },
                        style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f),
                    )
                    Text("nhận ${Money.format(c.net)}", style = MaterialTheme.typography.titleSmall, color = money.collect)
                }
                Text(
                    listOfNotNull(
                        "${c.liveCount} hụi sống đóng ${Money.format(c.livePays)}".takeIf { c.liveCount > 0 },
                        "${c.deadCount} hụi chết đóng ${Money.format(c.deadPays)}".takeIf { c.deadCount > 0 },
                        "thảo ${Money.format(c.thao)}".takeIf { c.thao > 0 },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (rate != null && rules.bidding && !(rules.ownerFirst && c.number == 1)) {
                    val y = Hui.yearly(rate) * 100
                    Text(
                        (if (rate < 0) "Người hốt chịu lãi ≈ " else "Người hốt được lời ≈ ") + percent(kotlin.math.abs(rate) * 100) + "/tháng (" + percent(kotlin.math.abs(y), 1) + "/năm)" +
                            if (rate < 0 && Hui.exceedsLegal(rate)) " · vượt 20%/năm" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (rate < 0 && Hui.exceedsLegal(rate)) money.late else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (rows.size > 5) {
            TextButton(onClick = { onShowAll(!showAll) }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(if (showAll) "Thu gọn" else "Xem đủ ${rows.size} kỳ") }
        }
        val totalThao = rows.sumOf { it.first.thao }
        if (totalThao > 0) InfoRow("Chủ hụi được tiền thảo cả dây", Money.format(totalThao), bold = true)
        Text(
            "Ví dụ để dễ hình dung: tiền kêu thật mỗi kỳ do người hốt quyết định lúc khui.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        )
    }
}

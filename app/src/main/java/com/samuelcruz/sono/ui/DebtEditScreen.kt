package com.samuelcruz.sono.ui

import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.samuelcruz.sono.core.Dates
import com.samuelcruz.sono.core.DebtPlan
import com.samuelcruz.sono.core.Direction
import com.samuelcruz.sono.core.HuiCycle
import com.samuelcruz.sono.core.Installments
import com.samuelcruz.sono.core.InterestMethod
import com.samuelcruz.sono.core.Money
import com.samuelcruz.sono.core.PenaltyMode
import com.samuelcruz.sono.core.plain
import com.samuelcruz.sono.data.Debt
import com.samuelcruz.sono.data.Photos
import kotlinx.coroutines.launch
import java.io.File

private const val CYCLE_MONTH = 0
private const val CYCLE_WEEK = 1
private const val CYCLE_DAY = 2
private const val CYCLE_CUSTOM = 3

/** New or edit debt. [debtId] 0 = new (for [personId] if > 0, in [startDirection]). */
@Composable
fun DebtEditScreen(debtId: Long, personId: Long, startDirection: Int, onDone: (Long?) -> Unit) {
    val app = app()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val people by app.db.people().observeAll().collectAsState(emptyList())

    var loaded by rememberSaveable { mutableStateOf(debtId == 0L) }
    var name by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var direction by rememberSaveable { mutableStateOf(if (startDirection == Direction.BORROWED) Direction.BORROWED else Direction.LENT) }
    var amount by rememberSaveable { mutableStateOf("") }
    var startDay by rememberSaveable { mutableStateOf(Dates.todayDay()) }
    var plan by rememberSaveable { mutableStateOf(DebtPlan.ONCE) }
    var dueDay by rememberSaveable { mutableStateOf<Long?>(null) }
    var term by rememberSaveable { mutableStateOf("12") }
    var cycleChoice by rememberSaveable { mutableStateOf(CYCLE_MONTH) }
    var everyDays by rememberSaveable { mutableStateOf("10") }
    var firstDue by rememberSaveable { mutableStateOf(Dates.today().plusMonths(1).toEpochDay()) }
    var method by rememberSaveable { mutableStateOf(InterestMethod.NONE) }
    var rate by rememberSaveable { mutableStateOf("") }
    var yearly by rememberSaveable { mutableStateOf(false) }
    var remind by rememberSaveable { mutableStateOf(true) }
    var remindDays by rememberSaveable { mutableStateOf("1") }
    var note by rememberSaveable { mutableStateOf("") }
    var photo by rememberSaveable { mutableStateOf<String?>(null) }
    var showSchedule by rememberSaveable { mutableStateOf(false) }
    var penaltyMode by rememberSaveable { mutableStateOf(PenaltyMode.NONE) }
    var penaltyValue by rememberSaveable { mutableStateOf("") }
    var toChiTieu by rememberSaveable { mutableStateOf(true) }
    var confirmPlanChange by rememberSaveable { mutableStateOf(false) }
    var paidCount by rememberSaveable { mutableStateOf(0) }
    var existing by remember { mutableStateOf<Debt?>(null) }
    val chiTieu = remember { app.prefs.chiTieuLink && ChiTieu.installed(context) }

    LaunchedEffect(debtId, personId) {
        if (personId > 0 && name.isEmpty()) app.db.people().get(personId)?.let { name = it.name; phone = it.phone }
        if (debtId != 0L) paidCount = app.db.payments().forDebt(debtId).count { !it.isTopUp }
        if (debtId == 0L || loaded) {
            if (debtId != 0L) existing = app.db.debts().get(debtId)
            return@LaunchedEffect
        }
        val d = app.db.debts().get(debtId) ?: return@LaunchedEffect
        existing = d
        penaltyMode = d.penaltyMode
        penaltyValue = when {
            d.penaltyValue <= 0 -> ""
            d.penaltyMode == PenaltyMode.PERCENT_PER_DAY -> com.samuelcruz.sono.core.percent(d.penaltyValue, 3).removeSuffix("%")
            else -> d.penaltyValue.toLong().toString()
        }
        app.db.people().get(d.personId)?.let { name = it.name; phone = it.phone }
        direction = d.direction
        amount = d.amount.toString()
        startDay = d.startDay
        plan = d.plan
        if (d.plan == DebtPlan.INSTALLMENTS) {
            term = d.term.toString()
            firstDue = d.dueDay ?: d.startDay
            cycleChoice = when {
                d.cycle == HuiCycle.MONTHLY -> CYCLE_MONTH
                d.everyDays == 7 -> CYCLE_WEEK
                d.everyDays == 1 -> CYCLE_DAY
                else -> CYCLE_CUSTOM
            }
            everyDays = d.everyDays.toString()
        } else dueDay = d.dueDay
        method = d.interestMethod
        rate = if (d.ratePercent > 0) com.samuelcruz.sono.core.percent(d.ratePercent).removeSuffix("%") else ""
        yearly = d.rateYearly
        remind = d.remindDays >= 0
        remindDays = (if (d.remindDays >= 0) d.remindDays else 1).toString()
        note = d.note
        photo = d.photo
        loaded = true
    }

    // Contact picker on phone numbers: returns one number and its name, readable without the contacts permission.
    val pickContact = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.query(
                uri, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0).orEmpty()
                    phone = c.getString(1).orEmpty().replace(" ", "")
                }
            }
        }
    }
    val cameraFile = remember { File(context.cacheDir, "camera").apply { mkdirs() }.let { File(it, "shot.jpg") } }
    val cameraUri = remember { FileProvider.getUriForFile(context, "${context.packageName}.files", cameraFile) }
    fun savePhoto(uri: Uri) = scope.launch { runCatching { Photos.save(context, uri) }.onSuccess { photo = it } }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) savePhoto(cameraUri) }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) savePhoto(uri) }

    val money = LocalMoneyColors.current
    val principal = Money.parse(amount)
    val terms = term.toIntOrNull()?.coerceIn(1, 600)
    val cycle = if (cycleChoice == CYCLE_MONTH) HuiCycle.MONTHLY else HuiCycle.DAYS
    val days = when (cycleChoice) {
        CYCLE_WEEK -> 7
        CYCLE_DAY -> 1
        CYCLE_CUSTOM -> everyDays.toIntOrNull()?.coerceIn(1, 365) ?: 10
        else -> 30
    }
    val ratePct = parseDecimal(rate) ?: 0.0
    val effectiveMethod = when {
        plan == DebtPlan.ONCE && method != InterestMethod.NONE -> InterestMethod.SIMPLE
        plan == DebtPlan.INSTALLMENTS && method == InterestMethod.SIMPLE -> InterestMethod.DECLINING
        else -> method
    }
    val schedule = remember(principal, terms, effectiveMethod, ratePct, yearly, cycle, days, firstDue, plan) {
        if (plan != DebtPlan.INSTALLMENTS || principal == null || terms == null) emptyList()
        else Installments.schedule(principal, terms, effectiveMethod, if (effectiveMethod == InterestMethod.NONE) 0.0 else Installments.monthlyRate(ratePct, yearly), cycle, days, Dates.of(firstDue))
    }
    val valid = name.isNotBlank() && principal != null && principal > 0 && (plan == DebtPlan.ONCE || terms != null) &&
        (effectiveMethod == InterestMethod.NONE || ratePct > 0)

    val penalty = when (penaltyMode) {
        PenaltyMode.PERCENT_PER_DAY -> parseDecimal(penaltyValue) ?: 0.0
        PenaltyMode.NONE -> 0.0
        else -> Money.parse(penaltyValue)?.toDouble() ?: 0.0
    }

    /** The installment schedule of the saved debt changes (payments stay on their installment numbers). */
    fun scheduleChanged(): Boolean {
        val e = existing ?: return false
        if (e.plan != DebtPlan.INSTALLMENTS || paidCount == 0) return false
        return plan != e.plan || terms != e.term || cycle != e.cycle || days != e.everyDays || firstDue != e.dueDay ||
            principal != e.amount || effectiveMethod != e.interestMethod || ratePct != e.ratePercent || yearly != e.rateYearly
    }

    fun save(confirmed: Boolean = false) {
        if (!valid) return
        if (!confirmed && scheduleChanged()) { confirmPlanChange = true; return }
        scope.launch {
            val pid = app.repo.personFor(name, phone)
            val d = (existing ?: Debt(personId = pid, direction = direction, amount = 0, startDay = startDay)).copy(
                personId = pid, direction = direction, amount = principal!!, startDay = startDay,
                plan = plan,
                dueDay = if (plan == DebtPlan.INSTALLMENTS) firstDue else dueDay,
                term = if (plan == DebtPlan.INSTALLMENTS) terms!! else 1,
                cycle = cycle, everyDays = days,
                interestMethod = effectiveMethod,
                ratePercent = if (effectiveMethod == InterestMethod.NONE) 0.0 else ratePct,
                rateYearly = yearly,
                remindDays = if (remind) (remindDays.toIntOrNull() ?: 0).coerceIn(0, 60) else -1,
                note = note.trim(), photo = photo,
                penaltyMode = if (penalty > 0) penaltyMode else PenaltyMode.NONE, penaltyValue = penalty,
            )
            val id = app.repo.saveDebt(d)
            // Lending = money out of my wallet (expense); borrowing = money in (income).
            if (existing == null && chiTieu && toChiTieu) {
                ChiTieu.add(context, income = direction == Direction.BORROWED, amount = principal, note = if (direction == Direction.LENT) "Cho ${name.trim()} mượn" else "Mượn ${name.trim()}")
            }
            app.message(if (existing == null) "Đã ghi khoản nợ" else "Đã lưu")
            onDone(id)
        }
    }

    SimpleScaffold(
        if (debtId == 0L) "Ghi nợ" else "Sửa khoản nợ", onBack = { onDone(null) },
        actions = { TextButton(onClick = { save() }, enabled = valid) { Text("Lưu") } },
    ) { pad ->
        if (!loaded) return@SimpleScaffold
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PillToggle(listOf("Tôi cho mượn", "Tôi đi mượn"), if (direction == Direction.LENT) 0 else 1, listOf(money.collect, money.pay), {
                direction = if (it == 0) Direction.LENT else Direction.BORROWED
            })
            Text(
                if (direction == Direction.LENT) "Người kia nợ bạn, bạn là người thu tiền." else "Bạn nợ người kia, bạn là người trả tiền.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextInput(
                if (direction == Direction.LENT) "Người mượn" else "Người cho mượn", name, { name = it },
                trailing = {
                    IconButton(onClick = {
                        runCatching { pickContact.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)) }
                    }) { Icon(Icons.Default.Contacts, "Chọn từ danh bạ") }
                },
            )
            val suggestions = remember(name, people) {
                val q = name.trim().plain()
                if (q.isEmpty()) emptyList() else people.filter { it.name.plain().contains(q) && it.name.plain() != q }.take(4)
            }
            if (suggestions.isNotEmpty()) {
                ChoiceChips(suggestions.map { it.id to it.name }, -1L, { id ->
                    people.firstOrNull { it.id == id }?.let { name = it.name; phone = it.phone }
                })
            }
            TextInput("Số điện thoại (không bắt buộc)", phone, { phone = it }, keyboard = KeyboardType.Phone)
            AmountField("Số tiền", amount, { amount = it })
            DateField(if (direction == Direction.LENT) "Ngày cho mượn" else "Ngày mượn", startDay, { it?.let { d -> startDay = d } })

            SectionHeader("Cách trả")
            PillToggle(listOf("Trả một lần", "Trả góp theo kỳ"), plan, listOf(Brand, Brand), { plan = it })
            if (plan == DebtPlan.ONCE) {
                DateField("Hạn trả", dueDay, { dueDay = it }, clearable = true, supporting = if (dueDay == null) "Không có hạn: trả lúc nào cũng được" else null)
                ChoiceChips(listOf(InterestMethod.NONE to "Không lãi", InterestMethod.SIMPLE to "Có lãi"), if (method == InterestMethod.NONE) InterestMethod.NONE else InterestMethod.SIMPLE, { method = it })
                if (method != InterestMethod.NONE) {
                    RateFields(rate, { rate = it }, yearly, { yearly = it })
                    Text(
                        "Lãi tính theo ngày trên số tiền gốc còn nợ (30 ngày = 1 tháng). Mỗi lần trả sẽ trừ vào lãi trước, rồi tới gốc.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                NumberField("Số kỳ", term, { term = it }, suffix = "kỳ")
                ChoiceChips(
                    listOf(CYCLE_MONTH to "Hằng tháng", CYCLE_WEEK to "Hằng tuần", CYCLE_DAY to "Hằng ngày", CYCLE_CUSTOM to "Mỗi N ngày"),
                    cycleChoice, { cycleChoice = it },
                )
                if (cycleChoice == CYCLE_CUSTOM) NumberField("Số ngày mỗi kỳ", everyDays, { everyDays = it }, suffix = "ngày")
                DateField(
                    "Ngày trả kỳ đầu", firstDue, { it?.let { d -> firstDue = d } },
                    supporting = if (cycleChoice == CYCLE_MONTH) "Các kỳ sau trả ngày ${Dates.of(firstDue).dayOfMonth} hằng tháng (tháng ngắn thì trả ngày cuối tháng)" else null,
                )
                ChoiceChips(
                    listOf(InterestMethod.NONE to "Không lãi", InterestMethod.FLAT to "Lãi phẳng", InterestMethod.DECLINING to "Dư nợ giảm dần", InterestMethod.ANNUITY to "Góp đều"),
                    effectiveMethod, { method = it },
                )
                Text(
                    when (effectiveMethod) {
                        InterestMethod.FLAT -> "Lãi phẳng: mỗi kỳ trả gốc chia đều + lãi tính trên toàn bộ số tiền ban đầu."
                        InterestMethod.DECLINING -> "Dư nợ giảm dần: mỗi kỳ trả gốc chia đều + lãi tính trên số gốc còn nợ, nên kỳ sau trả ít hơn kỳ trước."
                        InterestMethod.ANNUITY -> "Góp đều: kỳ nào cũng trả một số tiền như nhau, lãi tính trên số gốc còn nợ."
                        else -> "Không lãi: số tiền chia đều cho các kỳ."
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (effectiveMethod != InterestMethod.NONE) RateFields(rate, { rate = it }, yearly, { yearly = it })
                if (schedule.isNotEmpty()) {
                    Card {
                        val total = schedule.sumOf { it.total }
                        InfoRow("Kỳ đầu", Money.format(schedule.first().total), bold = true)
                        if (schedule.size > 1 && schedule.last().total != schedule.first().total) InfoRow("Kỳ cuối", Money.format(schedule.last().total))
                        InfoRow("Tổng tiền lãi", Money.format(total - principal!!))
                        InfoRow("Tổng phải trả", Money.format(total), bold = true)
                        TextButton(onClick = { showSchedule = !showSchedule }, modifier = Modifier.padding(horizontal = 8.dp)) {
                            Text(if (showSchedule) "Ẩn lịch trả" else "Xem lịch trả ${schedule.size} kỳ")
                        }
                        if (showSchedule) {
                            schedule.forEach { i ->
                                InfoRow("Kỳ ${i.number} · ${Dates.format(i.due.toEpochDay())}", Money.format(i.total) + if (i.interest > 0) "\n(lãi ${Money.format(i.interest)})" else "")
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }

            SectionHeader("Nhắc nhở")
            SwitchRow(
                "Nhắc trước hạn", remind, { remind = it },
                subtitle = if (direction == Direction.LENT) "Thông báo để bạn nhắc người kia trả" else "Thông báo để bạn chuẩn bị tiền trả",
            )
            if (remind) {
                NumberField(
                    "Nhắc trước", remindDays, { remindDays = it }, suffix = "ngày", max = 2,
                    supporting = "0 = chỉ nhắc đúng ngày. Ngày đến hạn và khi quá hạn luôn có nhắc.",
                )
            }

            SectionHeader("Phạt trễ hạn")
            ChoiceChips(
                listOf(PenaltyMode.NONE to "Không phạt", PenaltyMode.PER_DAY to "Số tiền mỗi ngày", PenaltyMode.PERCENT_PER_DAY to "% mỗi ngày", PenaltyMode.ONCE to "Một lần mỗi kỳ trễ"),
                penaltyMode, { penaltyMode = it },
            )
            when (penaltyMode) {
                PenaltyMode.PER_DAY -> AmountField("Phạt mỗi ngày trễ", penaltyValue, { penaltyValue = it })
                PenaltyMode.ONCE -> AmountField("Phạt mỗi lần trễ hạn", penaltyValue, { penaltyValue = it })
                PenaltyMode.PERCENT_PER_DAY -> DecimalField("% số tiền trễ, mỗi ngày", penaltyValue, { penaltyValue = it }, suffix = "%")
            }
            if (penaltyMode != PenaltyMode.NONE) {
                Text(
                    "Tính cho mỗi kỳ (hoặc hạn trả) bị trễ, từ ngày đến hạn tới ngày trả đủ, rồi cộng vào số còn nợ.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionHeader("Ghi chú & chứng từ")
            TextInput("Ghi chú", note, { note = it }, singleLine = false)
            photo?.let { name ->
                val bitmap by produceState<android.graphics.Bitmap?>(null, name) { value = Photos.load(context, name, 1200) }
                bitmap?.let {
                    Image(
                        it.asImageBitmap(), "Ảnh chứng từ", contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).clip(RoundedCornerShape(16.dp)),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { runCatching { takePhoto.launch(cameraUri) } }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.CameraAlt, null); Spacer(Modifier.width(6.dp)); Text("Chụp")
                }
                OutlinedButton(onClick = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Image, null); Spacer(Modifier.width(6.dp)); Text("Chọn ảnh")
                }
                if (photo != null) IconButton(onClick = { photo = null }) { Icon(Icons.Default.Delete, "Bỏ ảnh") }
            }
            Text(
                "Ví dụ: giấy mượn tiền, ảnh chụp tin nhắn hay biên nhận chuyển khoản.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (existing == null && chiTieu) {
                SwitchRow(
                    "Ghi vào app Chi Tiêu", toChiTieu, { toChiTieu = it },
                    subtitle = if (direction == Direction.LENT) "Khoản chi: tiền cho mượn ra khỏi ví" else "Khoản thu: tiền mượn vào ví",
                )
            }
            Button(onClick = { save() }, enabled = valid, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Lưu") }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (confirmPlanChange) {
        ConfirmDialog(
            "Đổi lịch trả góp?",
            "Khoản này đã có $paidCount lần trả gắn với từng kỳ. Sau khi đổi, các lần trả vẫn giữ theo số kỳ cũ (kỳ 1, kỳ 2…) nhưng số tiền mỗi kỳ được tính lại, nên có kỳ có thể thành thiếu hoặc dư. Kiểm tra lại lịch trả sau khi lưu.",
            "Vẫn lưu", danger = false,
            onConfirm = { save(confirmed = true) }, onDismiss = { confirmPlanChange = false },
        )
    }
}

@Composable
fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun RateFields(rate: String, onRate: (String) -> Unit, yearly: Boolean, onYearly: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DecimalField("Lãi suất", rate, onRate, Modifier.weight(1f), suffix = "%")
        ChoiceChips(listOf(false to "/tháng", true to "/năm"), yearly, onYearly)
    }
}

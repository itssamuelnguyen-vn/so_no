package com.samuelcruz.sono.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.samuelcruz.sono.BuildConfig
import com.samuelcruz.sono.Reminders
import com.samuelcruz.sono.core.AppLock
import com.samuelcruz.sono.core.BankAccount
import com.samuelcruz.sono.core.Prefs
import com.samuelcruz.sono.data.AutoBackup
import com.samuelcruz.sono.data.Backup
import com.samuelcruz.sono.qr.Banks
import com.samuelcruz.sono.qr.VietQr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun SettingsScreen(pad: PaddingValues) {
    val app = app()
    val context = LocalContext.current
    val prefs = app.prefs
    var accounts by remember { mutableStateOf(prefs.accounts) }
    var editingAccount by remember { mutableStateOf<Int?>(null) } // index, or -1 = new
    var showQr by rememberSaveable { mutableStateOf(false) }
    var chiTieuLink by remember { mutableStateOf(prefs.chiTieuLink) }
    var myName by rememberSaveable { mutableStateOf(prefs.myName) }
    var myPhone by rememberSaveable { mutableStateOf(prefs.myPhone) }
    var lock by remember { mutableStateOf(prefs.lockEnabled) }
    var reminders by remember { mutableStateOf(prefs.reminders) }
    var hour by remember { mutableIntStateOf(prefs.reminderHour) }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    var autoDir by remember { mutableStateOf(prefs.autoBackupDir) }
    var autoLast by remember { mutableLongStateOf(prefs.autoBackupLast) }
    var testLines by remember { mutableStateOf<List<String>?>(null) }

    fun saveAccounts(list: List<BankAccount>) {
        prefs.accounts = list
        accounts = list
    }

    val exportZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        app.scope.launch {
            val r = runCatching { context.contentResolver.openOutputStream(uri)!!.use { Backup.write(context, app.db, it) } }
            app.message(if (r.isSuccess) "Đã sao lưu" else "Sao lưu thất bại: ${r.exceptionOrNull()?.message}")
        }
    }
    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        app.scope.launch {
            val r = runCatching {
                val text = Backup.csv(app.db)
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)!!.use { it.write(text.toByteArray()) } }
            }
            app.message(if (r.isSuccess) "Đã xuất file Excel (CSV)" else "Xuất thất bại")
        }
    }
    val openZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> restoreUri = uri }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        AutoBackup.enable(context, uri)
        autoDir = prefs.autoBackupDir
        app.scope.launch {
            val r = runCatching { AutoBackup.run(context) }
            autoLast = prefs.autoBackupLast
            app.message(if (r.isSuccess) "Đã bật sao lưu tự động và sao lưu lần đầu" else "Không ghi được vào thư mục này")
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = pad.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Cài đặt", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))

        SectionHeader("Tài khoản nhận tiền")
        Card {
            Text(
                "Dùng để tạo mã VietQR khi nhắc trả nợ hoặc đóng hụi. Tài khoản đầu tiên là mặc định; khi tạo mã có thể chọn tài khoản khác.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp),
            )
            accounts.forEachIndexed { i, a ->
                val bank = Banks.byBin(a.bin)
                Row(Modifier.fillMaxWidth().clickable { editingAccount = i }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (bank != null) BankBadge(bank, 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.label.ifBlank { bank?.shortName ?: "?" }, style = MaterialTheme.typography.bodyLarge)
                        Text("${a.number} · ${a.name}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (i == 0) Tag("Mặc định", MaterialTheme.colorScheme.primary)
                }
            }
            Row(Modifier.padding(horizontal = 8.dp)) {
                TextButton(onClick = { editingAccount = -1 }) { Text("Thêm tài khoản") }
                if (QrShare.hasAccount(prefs)) TextButton(onClick = { showQr = true }) { Text("Xem mã QR mẫu") }
            }
        }

        SectionHeader("Thông tin của bạn")
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("In trên giấy xác nhận nợ.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextInput("Họ tên", myName, { myName = it; prefs.myName = it.trim() })
                TextInput("Số điện thoại", myPhone, { myPhone = it; prefs.myPhone = it.trim() }, keyboard = KeyboardType.Phone)
            }
        }

        SectionHeader("Đồng bộ & kết nối")
        Card {
            SyncSection()
            HorizontalDivider()
            val installed = remember { ChiTieu.installed(context) }
            SwitchRow(
                "Ghi sang app Chi Tiêu", chiTieuLink && installed, { chiTieuLink = it; prefs.chiTieuLink = it },
                subtitle = if (installed) "Khi ghi nợ, trả nợ, đóng hay hốt hụi: mở Chi Tiêu với số tiền điền sẵn để bạn chọn ví"
                else "Chưa cài app Chi Tiêu (cần bản 0.5.1 trở lên)",
                enabled = installed,
            )
        }

        SectionHeader("Nhắc nhở")
        Card {
            SwitchRow("Thông báo mỗi ngày", reminders, {
                reminders = it
                prefs.reminders = it
                Reminders.schedule(context, reset = true)
            }, subtitle = "Khoản nợ sắp đến hạn, đến hạn, quá hạn và ngày khui hụi")
            if (reminders) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Giờ thông báo", style = MaterialTheme.typography.bodyMedium)
                    ChoiceChips(listOf(6, 7, 8, 9, 12, 18, 20).map { it to "${it}h" }, hour, {
                        hour = it
                        prefs.reminderHour = it
                        Reminders.schedule(context, reset = true)
                    })
                    TextButton(onClick = { app.scope.launch { testLines = Reminders.lines(app.db) } }) { Text("Xem hôm nay sẽ nhắc gì") }
                }
            }
        }

        SectionHeader("Bảo mật & giao diện")
        Card {
            val canLock = remember { AppLock.canLock(context) }
            SwitchRow(
                "Khóa ứng dụng", lock, {
                    lock = it
                    prefs.lockEnabled = it
                },
                subtitle = if (canLock) "Vân tay, khuôn mặt hoặc mã khóa màn hình khi mở app" else "Điện thoại chưa đặt khóa màn hình",
                enabled = canLock,
            )
            HorizontalDivider()
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Giao diện", style = MaterialTheme.typography.bodyMedium)
                ChoiceChips(
                    listOf(Prefs.THEME_SYSTEM to "Theo máy", Prefs.THEME_LIGHT to "Sáng", Prefs.THEME_DARK to "Tối"),
                    app.themeMode, { app.changeTheme(it) },
                )
            }
        }

        SectionHeader("Dữ liệu")
        Card {
            SettingRow("Sao lưu ra file", "Tất cả khoản nợ, dây hụi và ảnh vào một file .zip") {
                exportZip.launch("sono-" + LocalDate.now() + ".zip")
            }
            HorizontalDivider()
            SettingRow("Khôi phục từ file", "Thay toàn bộ dữ liệu hiện tại bằng bản sao lưu") {
                openZip.launch(arrayOf("application/zip", "application/octet-stream"))
            }
            HorizontalDivider()
            SettingRow(
                "Sao lưu tự động hằng tuần",
                if (autoDir.isEmpty()) "Tắt. Chọn một thư mục (ví dụ thư mục Google Drive) để bật"
                else "Thư mục ${AutoBackup.folderName(context, autoDir)}" +
                    (if (autoLast > 0) " · lần cuối ${Instant.ofEpochMilli(autoLast).atZone(ZoneId.systemDefault()).toLocalDate().let { com.samuelcruz.sono.core.Dates.format(it) }}" else "") +
                    (if (prefs.autoBackupFailed) " · lần gần nhất bị lỗi" else ""),
            ) { pickFolder.launch(null) }
            if (autoDir.isNotEmpty()) {
                Row(Modifier.padding(horizontal = 8.dp)) {
                    TextButton(onClick = {
                        app.scope.launch {
                            val r = runCatching { AutoBackup.run(context) }
                            autoLast = prefs.autoBackupLast
                            app.message(if (r.isSuccess) "Đã sao lưu" else "Không ghi được vào thư mục")
                        }
                    }) { Text("Sao lưu ngay") }
                    TextButton(onClick = { AutoBackup.disable(context); autoDir = "" }) { Text("Tắt") }
                }
            }
            HorizontalDivider()
            SettingRow("Xuất Excel (CSV)", "Danh sách khoản nợ và các lần trả") {
                exportCsv.launch("so-no-" + LocalDate.now() + ".csv")
            }
        }

        Text(
            "Sổ Nợ & Hụi ${BuildConfig.VERSION_NAME} · dữ liệu chỉ nằm trên máy này",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 8.dp),
        )
    }

    editingAccount?.let { i ->
        AccountDialog(
            accounts.getOrNull(i), isDefault = i == 0,
            onDismiss = { editingAccount = null },
            onSave = { a, makeDefault ->
                val list = accounts.toMutableList()
                if (i in list.indices) list[i] = a else list += a
                if (makeDefault) { list.remove(a); list.add(0, a) }
                saveAccounts(list)
            },
            onDelete = if (i >= 0) ({ saveAccounts(accounts.filterIndexed { k, _ -> k != i }) }) else null,
        )
    }
    if (showQr) {
        QrDialog("Mã QR mẫu", null, "", { "Đây là mã QR nhận tiền của bạn (không kèm số tiền)." }, onDismiss = { showQr = false })
    }
    restoreUri?.let { uri ->
        ConfirmDialog(
            "Khôi phục dữ liệu?", "Toàn bộ dữ liệu hiện tại sẽ bị thay bằng bản sao lưu. Nên sao lưu dữ liệu hiện tại trước.", "Khôi phục",
            onConfirm = {
                app.scope.launch {
                    val r = runCatching { context.contentResolver.openInputStream(uri)!!.use { Backup.restore(context, app.db, it) } }
                    if (r.isSuccess) app.changed() // a restore is an edit to send to the other phones
                    app.message(if (r.isSuccess) "Đã khôi phục" else (r.exceptionOrNull() as? IllegalArgumentException)?.message ?: "Khôi phục thất bại")
                }
            },
            onDismiss = { restoreUri = null },
        )
    }
    testLines?.let { lines ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { testLines = null },
            title = { Text("Thông báo hôm nay") },
            text = { Text(if (lines.isEmpty()) "Hôm nay không có gì cần nhắc." else lines.joinToString("\n")) },
            confirmButton = { TextButton(onClick = { testLines = null }) { Text("Đóng") } },
        )
    }
}

@Composable
private fun SettingRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
    }
}

/** Add or edit one receiving account. */
@Composable
private fun AccountDialog(
    initial: BankAccount?, isDefault: Boolean, onDismiss: () -> Unit,
    onSave: (BankAccount, Boolean) -> Unit, onDelete: (() -> Unit)?,
) {
    var bin by rememberSaveable { mutableStateOf(initial?.bin.orEmpty()) }
    var number by rememberSaveable { mutableStateOf(initial?.number.orEmpty()) }
    var holder by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    var label by rememberSaveable { mutableStateOf(initial?.label.orEmpty()) }
    var makeDefault by rememberSaveable { mutableStateOf(isDefault) }
    var picking by rememberSaveable { mutableStateOf(false) }
    val bank = Banks.byBin(bin)
    val valid = bank != null && VietQr.isValidAccountNumber(number)
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Thêm tài khoản" else "Sửa tài khoản") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth().clickable { picking = true }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (bank != null) BankBadge(bank, 36.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(bank?.shortName ?: "Chọn ngân hàng", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                }
                TextInput("Số tài khoản", number, { number = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(VietQr.MAX_ACCOUNT_LENGTH) }, keyboard = KeyboardType.Number)
                TextInput("Tên chủ tài khoản", holder, { holder = it })
                TextInput("Tên gợi nhớ (không bắt buộc)", label, { label = it })
                if (!isDefault) {
                    Row(Modifier.clickable { makeDefault = !makeDefault }, verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(checked = makeDefault, onCheckedChange = { makeDefault = it })
                        Text("Đặt làm mặc định")
                    }
                }
                if (onDelete != null) TextButton(onClick = { onDelete(); onDismiss() }) { Text("Xóa tài khoản", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                onSave(BankAccount(bin, number.trim().uppercase(), holder.trim(), label.trim()), makeDefault && !isDefault)
                onDismiss()
            }) { Text("Lưu") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Hủy") } },
    )
    if (picking) BankPickerSheet(bank, onPick = { bin = it.bin; picking = false }, onDismiss = { picking = false })
}

package com.samuelcruz.sono.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.samuelcruz.sono.data.Backup
import com.samuelcruz.sono.data.SnapshotFiles
import com.samuelcruz.sono.sync.Drive
import com.samuelcruz.sono.sync.GoogleAuth
import com.samuelcruz.sono.sync.SnapshotSync
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val whenFormat = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy")

/** Settings → Google Drive: connect, sync now, answer a conflict, disconnect, restore a safety copy. */
@Composable
fun SyncSection() {
    val app = app()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = app.prefs
    var email by remember { mutableStateOf(prefs.syncEmail) }
    var busy by remember { mutableStateOf(false) }
    var showCopies by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf<File?>(null) }
    val state = app.syncState

    suspend fun finish(token: String) {
        val e = Drive(token).email() ?: error("Không đọc được tài khoản Google")
        prefs.syncEmail = e
        prefs.syncFileId = ""
        prefs.syncVersion = 0
        email = e
        SnapshotSync.schedule(context)
        app.syncSoon()
    }

    val signIn = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        scope.launch {
            val r = runCatching { finish(GoogleAuth.tokenFromIntent(context, result.data)) }
            busy = false
            if (r.isFailure) app.message("Không kết nối được: ${r.exceptionOrNull()?.message}")
        }
    }

    fun connect() {
        busy = true
        scope.launch {
            val r = runCatching {
                when (val a = GoogleAuth.authorize(context, null)) {
                    is GoogleAuth.Result.NeedsUi -> signIn.launch(IntentSenderRequest.Builder(a.intent.intentSender).build())
                    is GoogleAuth.Result.Token -> { finish(a.value); busy = false }
                }
            }
            if (r.isFailure) {
                busy = false
                app.message("Không kết nối được Google Drive: ${r.exceptionOrNull()?.message}. Xem hướng dẫn cài đặt trong README.")
            }
        }
    }

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Google Drive", style = MaterialTheme.typography.bodyLarge)
        if (email.isEmpty()) {
            Text(
                "Lưu cả sổ vào thư mục ẩn của app trên Google Drive và giữ nhiều máy giống nhau. Máy nào sửa thì gửi lên; nếu hai máy cùng sửa, app hỏi bạn giữ bản nào.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = ::connect, enabled = !busy) { Text(if (busy) "Đang kết nối…" else "Kết nối Google Drive") }
        } else {
            Text(email, style = MaterialTheme.typography.bodyMedium)
            Text(
                listOfNotNull(
                    prefs.lastSyncAt.takeIf { it > 0 }?.let { "Đồng bộ lần cuối " + Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(whenFormat) },
                    "Có thay đổi chưa gửi".takeIf { prefs.changedAt > prefs.lastSyncAt },
                    prefs.syncError.ifEmpty { null },
                ).joinToString(" · ").ifEmpty { "Chưa đồng bộ" },
                style = MaterialTheme.typography.bodySmall,
                color = if (prefs.syncError.isNotEmpty()) LocalMoneyColors.current.late else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { app.syncSoon() }) { Text("Đồng bộ ngay") }
                OutlinedButton(onClick = {
                    scope.launch {
                        GoogleAuth.revoke(context, prefs.syncEmail)
                        SnapshotSync.cancel(context)
                        prefs.syncEmail = ""; prefs.syncFileId = ""; prefs.syncVersion = 0; prefs.syncError = ""
                        email = ""
                    }
                }) { Text("Ngắt kết nối") }
            }
            if (state == SnapshotSync.Result.NeedsSignIn) TextButton(onClick = ::connect) { Text("Đăng nhập lại") }
        }
        val copies = remember(showCopies, state) { SnapshotFiles.list(context) }
        if (copies.isNotEmpty()) {
            TextButton(onClick = { showCopies = true }) { Text("Bản lưu an toàn trước khi đồng bộ (${copies.size})") }
        }
    }

    if (state == SnapshotSync.Result.Conflict) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Hai nơi cùng có thay đổi") },
            text = {
                Text(
                    "Máy này và Google Drive (từ máy khác) đều đã sửa sổ kể từ lần đồng bộ trước. Chọn bản muốn giữ; bản còn lại được cất vào \"Bản lưu an toàn\" trên máy này để lấy lại nếu cần.",
                )
            },
            confirmButton = { TextButton(onClick = { app.syncSoon(keep = SnapshotSync.Keep.THIS_PHONE) }) { Text("Giữ bản trên máy này") } },
            dismissButton = { TextButton(onClick = { app.syncSoon(keep = SnapshotSync.Keep.DRIVE) }) { Text("Lấy bản trên Drive") } },
        )
    }
    if (showCopies) {
        val copies = SnapshotFiles.list(context)
        AlertDialog(
            onDismissRequest = { showCopies = false },
            title = { Text("Bản lưu an toàn") },
            text = {
                Column {
                    copies.forEach { f ->
                        TextButton(onClick = { restoring = f; showCopies = false }) { Text(f.nameWithoutExtension) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showCopies = false }) { Text("Đóng") } },
        )
    }
    restoring?.let { f ->
        ConfirmDialog(
            "Khôi phục bản này?", "Toàn bộ dữ liệu hiện tại được thay bằng \"${f.nameWithoutExtension}\", rồi gửi lên Drive.", "Khôi phục",
            onConfirm = {
                app.scope.launch {
                    val r = runCatching { f.inputStream().use { Backup.restore(context, app.db, it) } }
                    if (r.isSuccess) app.changed()
                    app.message(if (r.isSuccess) "Đã khôi phục" else "Khôi phục thất bại")
                }
            },
            onDismiss = { restoring = null },
        )
    }
}

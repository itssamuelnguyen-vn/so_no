package com.samuelcruz.sono.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.samuelcruz.sono.core.BankAccount
import com.samuelcruz.sono.core.Prefs
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.samuelcruz.sono.qr.Banks
import com.samuelcruz.sono.qr.CardData
import com.samuelcruz.sono.qr.QrCard
import com.samuelcruz.sono.qr.VietQr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** VietQR codes for collecting money into my own account (set once in Settings). */
object QrShare {
    /** Accounts that can make a QR code, default first. */
    fun accounts(prefs: Prefs): List<BankAccount> =
        prefs.accounts.filter { Banks.byBin(it.bin) != null && VietQr.isValidAccountNumber(it.number) }

    fun hasAccount(prefs: Prefs): Boolean = accounts(prefs).isNotEmpty()

    /** The card for [amount] with transfer note [note] into [account]; null without one. */
    fun card(context: Context, account: BankAccount?, amount: Long?, note: String): Bitmap? {
        account ?: return null
        val bank = Banks.byBin(account.bin) ?: return null
        if (!VietQr.isValidAccountNumber(account.number)) return null
        val data = CardData(
            bank, account.number, VietQr.cleanAccountName(account.name),
            amount?.takeIf { it in 1..VietQr.MAX_AMOUNT }, VietQr.cleanDescription(note).ifBlank { null },
        )
        return runCatching { QrCard.render(context, data) }.getOrNull()
    }

    /** Opens the share sheet with [text], plus the QR picture when there is one. */
    suspend fun share(context: Context, text: String, bitmap: Bitmap?) {
        val send = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, text)
        if (bitmap != null) {
            val file = withContext(Dispatchers.IO) {
                val dir = File(context.cacheDir, "shared").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                File(dir, "ma-qr.png").also { f -> f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            send.type = "image/png"
            send.putExtra(Intent.EXTRA_STREAM, uri)
            send.clipData = ClipData.newRawUri(null, uri)
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            send.type = "text/plain"
        }
        context.startActivity(Intent.createChooser(send, "Gửi qua").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** "STK 0123 – Vietcombank – NGUYEN VAN A" for messages. */
    fun accountLine(account: BankAccount?): String? {
        account ?: return null
        val bank = Banks.byBin(account.bin) ?: return null
        if (account.number.isBlank()) return null
        return "STK ${account.number} – ${bank.shortName}" + account.name.takeIf { it.isNotBlank() }?.let { " – ${VietQr.cleanAccountName(it)}" }.orEmpty()
    }

    /** Account line of the default account. */
    fun accountLine(prefs: Prefs): String? = accountLine(accounts(prefs).firstOrNull())
}

/**
 * Shows a QR card (for the other person to scan right here) with a button to send it with the message
 * [message] builds (it gets the chosen account's line, or null). With several accounts, chips pick one.
 * Without a bank account in Settings, only the message is sent.
 */
@Composable
fun QrDialog(title: String, amount: Long?, qrNote: String, message: (String?) -> String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = app()
    val scope = rememberCoroutineScope()
    val accounts = remember { QrShare.accounts(app.prefs) }
    var chosen by rememberSaveable { mutableIntStateOf(0) }
    val account = accounts.getOrNull(chosen)
    val bitmap = remember(amount, qrNote, chosen) { QrShare.card(context, account, amount, qrNote) }
    val text = message(QrShare.accountLine(account))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (accounts.size > 1) {
                    ChoiceChips(accounts.mapIndexed { i, a -> i to (a.label.ifBlank { Banks.byBin(a.bin)?.shortName ?: a.number }) }, chosen, { chosen = it })
                }
                if (bitmap != null) {
                    Image(
                        bitmap.asImageBitmap(), "Mã QR chuyển khoản",
                        modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp).clip(RoundedCornerShape(16.dp)),
                    )
                } else {
                    Text(
                        "Chưa có tài khoản nhận tiền nên không tạo được mã QR. Thêm trong Cài đặt → Tài khoản nhận tiền.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(text, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            Button(onClick = { scope.launch { QrShare.share(context, text, bitmap) } }) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Share, null)
                    Text("Gửi")
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Đóng") } },
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

package com.samuelcruz.sono.ui

import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import com.samuelcruz.sono.core.Dates
import com.samuelcruz.sono.core.Direction
import com.samuelcruz.sono.data.DebtState
import com.samuelcruz.sono.data.IouParty
import com.samuelcruz.sono.data.IouPdf
import com.samuelcruz.sono.data.Photos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Strokes drawn with a finger; each stroke is a list of points in the pad's pixels. */
class Strokes {
    val lines = mutableStateListOf<List<Offset>>()
    var size = IntSize.Zero
    val empty get() = lines.isEmpty()

    /** Black ink on a transparent picture of the pad's size, or null when nothing was drawn. */
    fun toBitmap(): Bitmap? {
        if (empty || size.width == 0) return null
        val b = createBitmap(size.width, size.height)
        val c = android.graphics.Canvas(b)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 6f
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
        }
        for (line in lines) {
            val path = android.graphics.Path()
            line.forEachIndexed { i, o -> if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
            if (line.size == 1) path.lineTo(line[0].x + 0.1f, line[0].y)
            c.drawPath(path, p)
        }
        return b
    }
}

/** A white box to sign in with a finger. */
@Composable
fun SignaturePad(strokes: Strokes, modifier: Modifier = Modifier) {
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
    Box(
        modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(14.dp)).background(Color.White)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
            .onSizeChanged { strokes.size = it }
            .pointerInput(strokes) {
                detectDragGestures(
                    onDragStart = { current = listOf(it) },
                    onDrag = { change, _ -> current = current + change.position },
                    onDragEnd = { strokes.lines += current; current = emptyList() },
                    onDragCancel = { strokes.lines += current; current = emptyList() },
                )
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            (strokes.lines + listOf(current)).forEach { line ->
                if (line.isEmpty()) return@forEach
                val path = Path().apply { line.forEachIndexed { i, o -> if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) } }
                drawPath(path, Color.Black, style = Stroke(width = 6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        if (strokes.empty && current.isEmpty()) {
            Text("Ký vào đây", color = Color(0xFF9CA3AF), modifier = Modifier.padding(12.dp))
        }
    }
}

/** The IOU (giấy xác nhận nợ) for a debt: who is who, both signatures, then a PDF to send or save. */
@Composable
fun IouScreen(debtId: Long, onBack: () -> Unit) {
    val app = app()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val debt by app.db.debts().observe(debtId).collectAsState(null)
    val payments by app.db.payments().observeFor(debtId).collectAsState(emptyList())
    val people by app.db.people().observeAll().collectAsState(emptyList())
    val today = rememberToday()
    var myName by rememberSaveable { mutableStateOf(app.prefs.myName) }
    var myPhone by rememberSaveable { mutableStateOf(app.prefs.myPhone) }
    var myId by rememberSaveable { mutableStateOf(app.prefs.myIdNumber) }
    var myAddress by rememberSaveable { mutableStateOf(app.prefs.myAddress) }
    var otherId by rememberSaveable { mutableStateOf("") }
    var otherAddress by rememberSaveable { mutableStateOf("") }
    var place by rememberSaveable { mutableStateOf("") }
    val mine = remember { Strokes() }
    val theirs = remember { Strokes() }
    var resignMine by rememberSaveable { mutableStateOf(false) }
    var resignTheirs by rememberSaveable { mutableStateOf(false) }
    val d = debt ?: return
    val person = people.firstOrNull { it.id == d.personId }
    val lent = d.direction == Direction.LENT
    // Saved signatures, by role: lender / borrower.
    val mySaved = if (lent) d.signLender else d.signBorrower
    val theirSaved = if (lent) d.signBorrower else d.signLender
    val mySavedBitmap by produceState<Bitmap?>(null, mySaved) { value = mySaved?.let { Photos.load(context, it, 800) } }
    val theirSavedBitmap by produceState<Bitmap?>(null, theirSaved) { value = theirSaved?.let { Photos.load(context, it, 800) } }

    /** Saves new signatures, then writes the PDF to [out]. */
    suspend fun build(out: java.io.OutputStream) {
        app.prefs.myName = myName.trim(); app.prefs.myPhone = myPhone.trim()
        app.prefs.myIdNumber = myId.trim(); app.prefs.myAddress = myAddress.trim()
        suspend fun store(s: Strokes): String? = s.toBitmap()?.let { b ->
            withContext(Dispatchers.IO) {
                val name = UUID.randomUUID().toString() + ".png"
                Photos.file(context, name).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                name
            }
        }
        val newMine = store(mine) ?: mySaved.takeIf { !resignMine }
        val newTheirs = store(theirs) ?: theirSaved.takeIf { !resignTheirs }
        val updated = if (lent) d.copy(signLender = newMine, signBorrower = newTheirs) else d.copy(signLender = newTheirs, signBorrower = newMine)
        if (updated != d) {
            listOfNotNull(d.signLender, d.signBorrower).filter { it != updated.signLender && it != updated.signBorrower }
                .forEach { Photos.file(context, it).delete() }
            app.repo.saveDebt(updated)
        }
        val myBitmap = newMine?.let { Photos.load(context, it, 800) }
        val theirBitmap = newTheirs?.let { Photos.load(context, it, 800) }
        val me = IouParty("", myName.trim(), myPhone.trim(), myId.trim(), myAddress.trim(), myBitmap)
        val other = IouParty("", person?.name.orEmpty(), person?.phone.orEmpty(), otherId.trim(), otherAddress.trim(), theirBitmap)
        val lender = (if (lent) me else other).copy(role = "Bên cho mượn")
        val borrower = (if (lent) other else me).copy(role = "Bên mượn")
        val state = DebtState(updated, payments, today)
        withContext(Dispatchers.IO) {
            IouPdf.write(
                context,
                IouPdf.Content(lender, borrower, state, planText(updated), interestText(updated), if (updated.penaltyMode != 0) penaltyText(updated) else null, place.trim()),
                out,
            )
        }
    }

    val fileName = "giay-xac-nhan-no-${person?.name?.let { com.samuelcruz.sono.qr.VietQr.removeAccents(it).lowercase().replace(' ', '-') } ?: "no"}-${Dates.of(today)}.pdf"
    val savePdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val r = runCatching { context.contentResolver.openOutputStream(uri)!!.use { build(it) } }
            app.message(if (r.isSuccess) "Đã lưu file PDF" else "Không tạo được PDF: ${r.exceptionOrNull()?.message}")
        }
    }

    fun share() = scope.launch {
        val r = runCatching {
            val file = withContext(Dispatchers.IO) {
                val dir = File(context.cacheDir, "shared").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                File(dir, fileName)
            }
            file.outputStream().use { build(it) }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            val send = Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            send.clipData = ClipData.newRawUri(null, uri)
            context.startActivity(Intent.createChooser(send, "Gửi giấy xác nhận nợ"))
        }
        if (r.isFailure) app.message("Không tạo được PDF: ${r.exceptionOrNull()?.message}")
    }

    SimpleScaffold("Giấy xác nhận nợ", onBack) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Điền thông tin, hai bên ký trên màn hình, rồi tạo file PDF để gửi qua Zalo / email hoặc in ra. Chữ ký được lưu lại cho lần sau.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SectionHeader(if (lent) "Bạn (bên cho mượn)" else "Bạn (bên mượn)")
            TextInput("Họ tên của bạn", myName, { myName = it })
            TextInput("Số điện thoại", myPhone, { myPhone = it }, keyboard = KeyboardType.Phone)
            TextInput("Số CCCD (không bắt buộc)", myId, { myId = it }, keyboard = KeyboardType.Number)
            TextInput("Địa chỉ (không bắt buộc)", myAddress, { myAddress = it })
            SignatureBlock("Chữ ký của bạn", mine, mySavedBitmap.takeIf { !resignMine }) { resignMine = true; mine.lines.clear() }

            SectionHeader((person?.name ?: "") + if (lent) " (bên mượn)" else " (bên cho mượn)")
            TextInput("Số CCCD (không bắt buộc)", otherId, { otherId = it }, keyboard = KeyboardType.Number)
            TextInput("Địa chỉ (không bắt buộc)", otherAddress, { otherAddress = it })
            SignatureBlock("Chữ ký của ${person?.name ?: "người kia"}", theirs, theirSavedBitmap.takeIf { !resignTheirs }) { resignTheirs = true; theirs.lines.clear() }

            TextInput("Nơi lập giấy (ví dụ: TP. Hồ Chí Minh)", place, { place = it })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { share() }, modifier = Modifier.weight(1f).height(52.dp)) {
                    Icon(Icons.Default.Share, null); Spacer(Modifier.width(8.dp)); Text("Gửi PDF")
                }
                OutlinedButton(onClick = { savePdf.launch(fileName) }, modifier = Modifier.weight(1f).height(52.dp)) {
                    Icon(Icons.Default.Save, null); Spacer(Modifier.width(8.dp)); Text("Lưu PDF")
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SignatureBlock(title: String, strokes: Strokes, saved: Bitmap?, onResign: () -> Unit) {
    Row {
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(top = 12.dp))
        if (saved != null) TextButton(onClick = onResign) { Text("Ký lại") }
        else if (!strokes.empty) TextButton(onClick = { strokes.lines.clear() }) { Text("Xóa") }
    }
    if (saved != null) {
        Image(
            saved.asImageBitmap(), title,
            modifier = Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(14.dp)).background(Color.White),
        )
    } else {
        SignaturePad(strokes)
    }
}

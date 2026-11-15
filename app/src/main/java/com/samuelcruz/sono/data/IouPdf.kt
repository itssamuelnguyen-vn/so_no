package com.samuelcruz.sono.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.res.ResourcesCompat
import com.samuelcruz.sono.R
import com.samuelcruz.sono.core.Dates
import com.samuelcruz.sono.core.DebtPlan
import com.samuelcruz.sono.core.Money
import com.samuelcruz.sono.core.VnNumber
import java.io.OutputStream

/** One side of the IOU. */
data class IouParty(val role: String, val name: String, val phone: String, val idNumber: String, val address: String, val signature: Bitmap?)

/**
 * "Giấy xác nhận nợ" as an A4 PDF drawn with Android's PdfDocument (no library): the two parties, the amount in
 * figures and words, dates, how it is paid back (with the schedule for installments), interest, late fees, and
 * both signatures. Text is Be Vietnam Pro so Vietnamese accents print correctly.
 */
object IouPdf {
    private const val W = 595 // A4 in points
    private const val H = 842
    private const val M = 56

    class Content(
        val lender: IouParty,
        val borrower: IouParty,
        val state: DebtState,
        val planText: String,
        val interestText: String,
        val penaltyText: String?,
        val place: String,
    )

    fun write(context: Context, c: Content, out: OutputStream) {
        val regular = ResourcesCompat.getFont(context, R.font.be_vietnam_pro_regular) ?: Typeface.DEFAULT
        val bold = ResourcesCompat.getFont(context, R.font.be_vietnam_pro_bold) ?: Typeface.DEFAULT_BOLD
        val doc = PdfDocument()
        var pageNo = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
        var canvas = page.canvas
        var y = M.toFloat()

        fun newPageIfNeeded(need: Float) {
            if (y + need <= H - M) return
            doc.finishPage(page)
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
            canvas = page.canvas
            y = M.toFloat()
        }

        fun text(t: String, size: Float, face: Typeface = regular, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL, gap: Float = 6f, left: Int = M, width: Int = W - 2 * M) {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face; textSize = size; color = Color.BLACK }
            val layout = StaticLayout.Builder.obtain(t, 0, t.length, paint, width).setAlignment(align).setLineSpacing(2f, 1f).build()
            newPageIfNeeded(layout.height.toFloat())
            canvas.save(); canvas.translate(left.toFloat(), y); layout.draw(canvas); canvas.restore()
            y += layout.height + gap
        }

        val d = c.state.debt
        text("CỘNG HÒA XÃ HỘI CHỦ NGHĨA VIỆT NAM", 11f, bold, Layout.Alignment.ALIGN_CENTER, 0f)
        text("Độc lập - Tự do - Hạnh phúc", 11f, bold, Layout.Alignment.ALIGN_CENTER, 18f)
        text("GIẤY XÁC NHẬN NỢ", 18f, bold, Layout.Alignment.ALIGN_CENTER, 4f)
        val made = Dates.of(Dates.todayDay())
        text("${c.place.ifBlank { "" }}${if (c.place.isNotBlank()) ", " else ""}ngày ${made.dayOfMonth} tháng ${made.monthValue} năm ${made.year}", 10.5f, regular, Layout.Alignment.ALIGN_CENTER, 16f)

        fun party(p: IouParty) {
            text("${p.role}: ${p.name.ifBlank { "................................................" }}", 11.5f, bold, gap = 2f)
            listOfNotNull(
                p.idNumber.takeIf { it.isNotBlank() }?.let { "Số CCCD: $it" },
                p.phone.takeIf { it.isNotBlank() }?.let { "Điện thoại: $it" },
                p.address.takeIf { it.isNotBlank() }?.let { "Địa chỉ: $it" },
            ).forEach { text(it, 10.5f, gap = 2f) }
            y += 8f
        }
        party(c.lender)
        party(c.borrower)

        val principal = c.state.principal
        text("Hai bên xác nhận:", 11.5f, bold)
        text("1. Bên mượn đã nhận của bên cho mượn số tiền ${Money.format(principal)} (bằng chữ: ${VnNumber.words(principal)}) vào ngày ${Dates.format(d.startDay)}.", 11f)
        if (c.state.topUps.isNotEmpty()) {
            text("   Trong đó mượn thêm: " + c.state.topUps.joinToString("; ") { "${Money.format(it.amount)} ngày ${Dates.format(it.day)}" } + ".", 10.5f)
        }
        text("2. Cách trả: ${c.planText}.", 11f)
        text("3. Lãi suất: ${c.interestText}.", 11f)
        c.penaltyText?.let { text("4. Trả chậm: $it.", 11f) }
        if (c.state.paid > 0) {
            text("${if (c.penaltyText != null) 5 else 4}. Đến ngày ${Dates.format(Dates.todayDay())} bên mượn đã trả ${Money.format(c.state.paid)}, còn nợ ${Money.format(c.state.left.coerceAtLeast(0))}.", 11f)
        }
        if (d.note.isNotBlank()) text("Ghi chú: ${d.note}", 10.5f)

        val plan = c.state.plan
        if (d.plan == DebtPlan.INSTALLMENTS && plan != null) {
            y += 6f
            text("Lịch trả:", 11.5f, bold, gap = 4f)
            val cols = floatArrayOf(M.toFloat(), M + 50f, M + 170f, M + 290f, M + 400f)
            fun row(cells: List<String>, face: Typeface) {
                newPageIfNeeded(18f)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face; textSize = 9.5f; color = Color.BLACK }
                cells.forEachIndexed { i, s -> canvas.drawText(s, cols[i], y + 11f, paint) }
                y += 16f
            }
            row(listOf("Kỳ", "Ngày trả", "Gốc", "Lãi", "Tổng"), bold)
            plan.items.forEach { s ->
                val i = s.installment
                row(listOf(i.number.toString(), Dates.format(i.due.toEpochDay()), Money.format(i.principal), Money.format(i.interest), Money.format(i.total)), regular)
            }
            row(listOf("", "Cộng", Money.format(plan.items.sumOf { it.installment.principal }), Money.format(plan.items.sumOf { it.installment.interest }), Money.format(plan.totalDue)), bold)
        }

        y += 10f
        text("Hai bên cam kết thông tin trên là đúng sự thật. Giấy này được lập thành 02 bản, mỗi bên giữ 01 bản.", 10.5f, gap = 18f)

        // Signatures side by side.
        newPageIfNeeded(150f)
        val colW = (W - 2 * M) / 2
        val titles = listOf(c.lender, c.borrower)
        val top = y
        titles.forEachIndexed { i, p ->
            y = top
            val left = M + i * colW
            text(p.role.uppercase(), 11f, bold, Layout.Alignment.ALIGN_CENTER, 2f, left, colW)
            text("(Ký, ghi rõ họ tên)", 9.5f, regular, Layout.Alignment.ALIGN_CENTER, 4f, left, colW)
            p.signature?.let { b ->
                val boxH = 80f
                val scale = minOf((colW - 20f) / b.width, boxH / b.height)
                val w = b.width * scale
                val h = b.height * scale
                val x = left + (colW - w) / 2
                canvas.drawBitmap(b, null, RectF(x, y, x + w, y + h), Paint(Paint.FILTER_BITMAP_FLAG))
            }
            y += 86f
            text(p.name, 11f, bold, Layout.Alignment.ALIGN_CENTER, 2f, left, colW)
        }
        doc.finishPage(page)
        doc.writeTo(out)
        doc.close()
    }
}

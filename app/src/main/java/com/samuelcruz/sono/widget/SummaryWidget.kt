package com.samuelcruz.sono.widget

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.samuelcruz.sono.MainActivity
import com.samuelcruz.sono.SoNoApp
import com.samuelcruz.sono.core.Dates
import com.samuelcruz.sono.core.Direction
import com.samuelcruz.sono.core.Money
import com.samuelcruz.sono.data.DebtState
import com.samuelcruz.sono.data.totals

/** Home-screen summary: what others owe me, what I owe, and the next thing due (a debt or a hụi round). */
class SummaryWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as SoNoApp
        val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val bg = if (dark) Color(0xFF16201F) else Color(0xFFFFFFFF)
        val fg = if (dark) Color(0xFFE6EFED) else Color(0xFF14201E)
        val muted = if (dark) Color(0xFF9DB0AC) else Color(0xFF5F6F6C)
        val collect = if (dark) Color(0xFF34D399) else Color(0xFF059669)
        val pay = if (dark) Color(0xFFFB923C) else Color(0xFFEA580C)
        val open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        provideContent {
            // Read inside provideContent so update() always shows fresh numbers.
            val debts by app.db.debts().observeAll().collectAsState(emptyList())
            val payments by app.db.payments().observeAll().collectAsState(emptyList())
            val people by app.db.people().observeAll().collectAsState(emptyList())
            val groups by app.db.hui().observeGroups().collectAsState(emptyList())
            val rounds by app.db.hui().observeAllRounds().collectAsState(emptyList())
            val today = Dates.todayDay()
            val byDebt = payments.groupBy { it.debtId }
            val states = debts.map { DebtState(it, byDebt[it.id].orEmpty(), today) }
            val t = states.totals()
            val names = people.associate { it.id to it.name }
            val held = rounds.groupBy { it.huiId }
            val nextDebt = states.flatMap { s -> s.dueDates().take(1).map { (day, amount) -> Triple(day, s, amount) } }.minByOrNull { it.first }
            val nextHui = groups.mapNotNull { g ->
                val n = held[g.id].orEmpty().size
                if (n >= g.slots) null else g.dates()[n].toEpochDay() to "Hụi ${g.name} kỳ ${n + 1}"
            }.minByOrNull { it.first }
            val nextLine = listOfNotNull(
                nextDebt?.let { (day, s, amount) ->
                    day to (if (s.debt.direction == Direction.LENT) "${names[s.debt.personId]} trả ${Money.compact(amount)}" else "Trả ${names[s.debt.personId]} ${Money.compact(amount)}")
                },
                nextHui,
            ).minByOrNull { it.first }?.let { (day, text) -> "${Dates.format(day, short = true)} (${Dates.relative(day, today)}): $text" }

            Column(
                GlanceModifier.fillMaxSize().background(ColorProvider(bg)).cornerRadius(24.dp).padding(14.dp).clickable(actionStartActivity(open)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(GlanceModifier.fillMaxWidth()) {
                    Column(GlanceModifier.defaultWeight()) {
                        Text("Người khác nợ tôi", style = TextStyle(color = ColorProvider(muted), fontSize = 12.sp))
                        Text(Money.format(t.toCollect), maxLines = 1, style = TextStyle(color = ColorProvider(collect), fontSize = 17.sp, fontWeight = FontWeight.Bold))
                    }
                    Column(GlanceModifier.defaultWeight()) {
                        Text("Tôi đang nợ", style = TextStyle(color = ColorProvider(muted), fontSize = 12.sp))
                        Text(Money.format(t.toPay), maxLines = 1, style = TextStyle(color = ColorProvider(pay), fontSize = 17.sp, fontWeight = FontWeight.Bold))
                    }
                }
                Spacer(GlanceModifier.height(8.dp))
                Text(
                    nextLine ?: "Không có gì sắp đến hạn",
                    maxLines = 2,
                    style = TextStyle(color = ColorProvider(fg), fontSize = 13.sp),
                )
            }
        }
    }

    companion object {
        suspend fun refresh(context: Context) = SummaryWidget().updateAll(context)
    }
}

class SummaryWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SummaryWidget()
}

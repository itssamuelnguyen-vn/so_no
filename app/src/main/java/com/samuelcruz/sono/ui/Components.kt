package com.samuelcruz.sono.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.samuelcruz.sono.SoNoApp
import com.samuelcruz.sono.core.Dates
import com.samuelcruz.sono.core.Money
import com.samuelcruz.sono.core.plain
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/** The application object (db, prefs, repo), like a global in a Rails initializer. */
@Composable
fun app(): SoNoApp = LocalContext.current.applicationContext as SoNoApp

/** A white card with generous rounding, the building block of every screen. */
@Composable
fun Card(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.then(if (onClick != null) Modifier.clip(RoundedCornerShape(22.dp)).clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) { Column(content = content) }
}

@Composable
fun SectionTitle(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: () -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (action != null) {
            Text(
                action, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onAction).padding(6.dp),
            )
        }
    }
}

/** Big friendly emoji + sentence for empty lists. */
@Composable
fun EmptyState(emoji: String, title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(emoji, fontSize = 48.sp)
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** First letter of a name on a soft coloured circle; the colour is picked from the name, so it never changes. */
@Composable
fun Avatar(name: String, size: Dp = 44.dp, emoji: String? = null) {
    val palette = listOf(0xFF0D9488, 0xFF2563EB, 0xFF7C3AED, 0xFFDB2777, 0xFFEA580C, 0xFF65A30D, 0xFF0891B2, 0xFFCA8A04)
    val color = Color(palette[(name.plain().hashCode() and 0x7fffffff) % palette.size])
    Box(Modifier.size(size).clip(CircleShape).background(color.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
        Text(
            emoji ?: name.trim().split(' ').lastOrNull()?.firstOrNull()?.uppercase() ?: "?",
            color = color, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.42f).sp,
        )
    }
}

/** A pill toggle with a sliding coloured selection. */
@Composable
fun PillToggle(options: List<String>, selected: Int, colors: List<Color>, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            val bg by animateColorAsState(if (on) colors[i] else Color.Transparent, tween(250), label = "pill")
            val fg by animateColorAsState(if (on) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, tween(250), label = "pillText")
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(50)).background(bg).clickable { onSelect(i) }.padding(vertical = 11.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1) }
        }
    }
}

/** Chips for picking one of a few options. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChoiceChips(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(label) })
        }
    }
}

/** Groups digits with dots while typing ("1500000" shows "1.500.000"); the field keeps only digits. */
object ThousandsTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val digits = text.text
        val out = digits.toLongOrNull()?.let { Money.group(it) } ?: digits
        // Map each digit position to its position with the dots in between.
        val toOut = IntArray(digits.length + 1)
        var o = 0
        var d = 0
        for (i in out.indices) {
            if (out[i] != '.') { toOut[d] = i; d++ }
            o = i + 1
        }
        toOut[digits.length] = o
        val toIn = IntArray(out.length + 1)
        var k = 0
        for (i in 0..out.length) {
            while (k < digits.length && toOut[k] < i) k++
            toIn[i] = k
        }
        return TransformedText(AnnotatedString(out), object : OffsetMapping {
            override fun originalToTransformed(offset: Int) = toOut[offset.coerceIn(0, digits.length)]
            override fun transformedToOriginal(offset: Int) = toIn[offset.coerceIn(0, out.length)]
        })
    }
}

/** Money input: digits only, dots shown, the amount in words below so a missing zero is obvious. */
@Composable
fun AmountField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, supporting: String? = null, isError: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = { t -> onChange(t.filter(Char::isDigit).trimStart('0').take(13)) },
        label = { Text(label) },
        suffix = { Text("đ") },
        singleLine = true,
        isError = isError,
        visualTransformation = ThousandsTransformation,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        supportingText = (supporting ?: Money.parse(value)?.let { Money.words(it) })?.let { s -> { Text(s) } },
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.fillMaxWidth(),
    )
}

/** A whole number field (number of shares, months…). */
@Composable
fun NumberField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, suffix: String? = null, max: Int = 3, supporting: String? = null) {
    OutlinedTextField(
        value = value,
        onValueChange = { t -> onChange(t.filter(Char::isDigit).take(max)) },
        label = { Text(label) },
        suffix = suffix?.let { s -> { Text(s) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        supportingText = supporting?.let { s -> { Text(s) } },
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.fillMaxWidth(),
    )
}

/** A decimal field with a comma or a dot ("1,5"). */
@Composable
fun DecimalField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, suffix: String? = null) {
    OutlinedTextField(
        value = value,
        onValueChange = { t ->
            val clean = t.replace('.', ',').filter { it.isDigit() || it == ',' }
            if (clean.count { it == ',' } <= 1 && clean.length <= 7) onChange(clean)
        },
        label = { Text(label) },
        suffix = suffix?.let { s -> { Text(s) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.fillMaxWidth(),
    )
}

fun parseDecimal(text: String): Double? = text.replace(',', '.').toDoubleOrNull()

@Composable
fun TextInput(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, singleLine: Boolean = true, keyboard: KeyboardType = KeyboardType.Text, trailing: (@Composable () -> Unit)? = null) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = singleLine,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        trailingIcon = trailing,
        shape = RoundedCornerShape(14.dp), modifier = modifier.fillMaxWidth(),
        minLines = if (singleLine) 1 else 2,
    )
}

/** A date shown as a field; tapping opens the calendar. [day] is an epoch day, null = none (when [clearable]). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(label: String, day: Long?, onChange: (Long?) -> Unit, modifier: Modifier = Modifier, clearable: Boolean = false, supporting: String? = null) {
    var open by rememberSaveable { mutableStateOf(false) }
    Surface(
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { open = true },
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.CalendarMonth, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    day?.let { Dates.long(Dates.of(it)) } ?: "Chưa chọn",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (day == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
                if (supporting != null) Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (clearable && day != null) IconButton(onClick = { onChange(null) }) { Icon(Icons.Default.Close, "Bỏ ngày") }
        }
    }
    if (open) {
        val state = rememberDatePickerState(initialSelectedDateMillis = (day ?: Dates.todayDay()) * DAY_MS)
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onChange(it / DAY_MS) }
                    open = false
                }) { Text("Chọn") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Hủy") } },
        ) { DatePicker(state) }
    }
}

private const val DAY_MS = 86_400_000L

/** A row with a title, an optional subtitle and a switch. */
@Composable
fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, subtitle: String? = null, enabled: Boolean = true) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** "Label ........ value" line inside a card. */
@Composable
fun InfoRow(label: String, value: String, valueColor: Color = Color.Unspecified, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(
            value, style = if (bold) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
            color = valueColor, textAlign = TextAlign.End,
        )
    }
}

/** Standard screen with a back arrow, a title and optional actions / floating button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimpleScaffold(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    floating: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Quay lại") } },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = bottomBar,
        floatingActionButton = floating,
        containerColor = MaterialTheme.colorScheme.background,
        content = content,
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit, danger: Boolean = true) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text(confirm, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Hủy") } },
    )
}

/** A small rounded label ("Quá hạn", "Đã xong"). */
@Composable
fun Tag(text: String, color: Color) {
    Text(
        text, style = MaterialTheme.typography.labelSmall, color = color,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** A thin progress bar. */
@Composable
fun Progress(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(color.copy(alpha = 0.15f))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(6.dp).clip(RoundedCornerShape(3.dp)).background(color))
    }
}

/** Title + big amount, used in headers. */
@Composable
fun AmountBlock(label: String, amount: Long, color: Color, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.headlineSmall) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = color.copy(alpha = 0.85f))
        Text(Money.format(amount), style = style, color = color, maxLines = 1)
    }
}

val ScreenPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp)

/**
 * Today's epoch day, kept current: re-read every minute while the screen is shown, so "hôm nay" / "còn 2 ngày"
 * move on after midnight even if the app stays open.
 */
@Composable
fun rememberToday(): Long {
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    var today by remember { mutableLongStateOf(Dates.todayDay()) }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            while (true) {
                today = Dates.todayDay()
                delay(60_000)
            }
        }
    }
    return today
}

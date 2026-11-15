package com.samuelcruz.sono.core

import java.text.Normalizer
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * Money is always a whole number of đồng in a [Long] (never Double: 0.1 + 0.2 is not 0.3 in floating point).
 * Vietnamese style: dots group thousands, a comma is the decimal separator.
 */
object Money {
    /** 1250000 → "1.250.000đ". */
    fun format(amount: Long, symbol: Boolean = true): String {
        val text = (if (amount < 0) "-" else "") + group(abs(amount))
        return if (symbol) "${text}đ" else text
    }

    /** Thousands grouped: 1234567 → "1.234.567". */
    fun group(value: Long): String {
        val digits = value.toString()
        val out = StringBuilder()
        digits.forEachIndexed { i, c ->
            if (i > 0 && (digits.length - i) % 3 == 0) out.append('.')
            out.append(c)
        }
        return out.toString()
    }

    /** Short form for tight spots: 950 → "950đ", 45500 → "45,5k", 1250000 → "1,25tr", 2e9 → "2 tỷ". */
    fun compact(amount: Long): String {
        val sign = if (amount < 0) "-" else ""
        val a = abs(amount)
        val text = when {
            a < 1_000 -> "${a}đ"
            a < 1_000_000 -> scaled(a, 1_000, 1, "k") ?: scaled(a, 1_000_000, 2, "tr")!!
            a < 1_000_000_000 -> scaled(a, 1_000_000, 2, "tr") ?: scaled(a, 1_000_000_000, 2, " tỷ")!!
            else -> scaled(a, 1_000_000_000, 2, " tỷ", force = true)!!
        }
        return sign + text
    }

    /** Amount read out under a field so a missing zero shows: 1500000 → "1,5 triệu đồng". */
    fun words(amount: Long): String = when {
        amount <= 0 -> ""
        amount < 1_000 -> "$amount đồng"
        amount < 1_000_000 -> (scaled(amount, 1_000, 3, " nghìn đồng") ?: scaled(amount, 1_000_000, 3, " triệu đồng")!!)
        amount < 1_000_000_000 -> (scaled(amount, 1_000_000, 3, " triệu đồng") ?: scaled(amount, 1_000_000_000, 3, " tỷ đồng")!!)
        else -> scaled(amount, 1_000_000_000, 3, " tỷ đồng", force = true)!!
    }

    /** Digits typed in an amount field → đồng; null when empty or too long. */
    fun parse(digits: String): Long? = digits.filter(Char::isDigit).takeIf { it.isNotEmpty() && it.length <= 13 }?.toLong()

    /** [value] in [unit]s rounded to [decimals]; null when rounding reaches 1000 units (use the next unit). */
    private fun scaled(value: Long, unit: Long, decimals: Int, suffix: String, force: Boolean = false): String? {
        var p = 1L
        repeat(decimals) { p *= 10 }
        val n = (value * p + unit / 2) / unit
        val whole = n / p
        if (whole >= 1000 && !force) return null
        val frac = (n % p).toString().padStart(decimals, '0').trimEnd('0')
        return group(whole) + (if (frac.isEmpty()) "" else "," + frac) + suffix
    }
}

/** Percent with a comma and no useless zeros: 1.5 → "1,5%", 2.0 → "2%". */
fun percent(value: Double, decimals: Int = 2): String {
    val s = String.format(java.util.Locale.US, "%.${decimals}f", value).trimEnd('0').trimEnd('.')
    return s.replace('.', ',') + "%"
}

/** Dates are stored as epoch days (days since 1970-01-01), so there is no time zone to get wrong. */
object Dates {
    private val dmy = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    private val dm = DateTimeFormatter.ofPattern("dd/MM")

    fun today(): LocalDate = LocalDate.now()
    fun todayDay(): Long = LocalDate.now().toEpochDay()
    fun of(day: Long): LocalDate = LocalDate.ofEpochDay(day)

    /** "14/11/2026", or "14/11" within the current year when [short]. */
    fun format(day: Long, short: Boolean = false): String = format(of(day), short)

    fun format(d: LocalDate, short: Boolean = false): String =
        if (short && d.year == LocalDate.now().year) d.format(dm) else d.format(dmy)

    /** "Thứ Bảy, 14/11/2026". */
    fun long(d: LocalDate): String = weekday(d) + ", " + d.format(dmy)

    fun weekday(d: LocalDate): String = LunarCalendar.WEEKDAYS[d.dayOfWeek.value - 1]

    /** "mùng 10 tháng 9 âm lịch" style, short: "10/9 ÂL". */
    fun lunarShort(d: LocalDate): String {
        val l = HuiSchedule.lunar(d)
        return "${l.day}/${l.month}${if (l.leap) "n" else ""} ÂL"
    }

    /** "hôm nay", "ngày mai", "còn 5 ngày", "quá 3 ngày". */
    fun relative(day: Long, today: Long = todayDay()): String {
        val diff = day - today
        return when {
            diff == 0L -> "hôm nay"
            diff == 1L -> "ngày mai"
            diff == -1L -> "hôm qua"
            diff > 0 -> "còn $diff ngày"
            else -> "quá ${-diff} ngày"
        }
    }

    fun between(a: LocalDate, b: LocalDate): Long = ChronoUnit.DAYS.between(a, b)
}

/** "Cà phê Đen" → "ca phe den", so searching without accents finds accented text. */
fun String.plain(): String =
    Normalizer.normalize(this, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace('đ', 'd').replace('Đ', 'D')
        .lowercase()

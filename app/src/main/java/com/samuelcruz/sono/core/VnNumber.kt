package com.samuelcruz.sono.core

/**
 * Amounts in Vietnamese words, as written on an IOU ("Bằng chữ: Một triệu năm trăm nghìn đồng"). Formal style:
 * "linh" for a missing tens digit, "không trăm" inside a number, "mốt" / "tư" / "lăm" after tens.
 */
object VnNumber {
    private val digits = arrayOf("không", "một", "hai", "ba", "bốn", "năm", "sáu", "bảy", "tám", "chín")
    private val units = arrayOf("", "nghìn", "triệu", "tỷ", "nghìn tỷ", "triệu tỷ")

    /** 1500000 → "Một triệu năm trăm nghìn đồng". */
    fun words(amount: Long): String {
        val text = read(amount)
        return text.replaceFirstChar { it.uppercase() } + " đồng"
    }

    /** The number alone, lower case: 21 → "hai mươi mốt". */
    fun read(n: Long): String {
        if (n == 0L) return "không"
        if (n < 0) return "âm " + read(-n)
        val groups = ArrayList<Int>()
        var x = n
        while (x > 0) { groups += (x % 1000).toInt(); x /= 1000 }
        val parts = ArrayList<String>()
        for (i in groups.indices.reversed()) {
            val g = groups[i]
            if (g == 0) continue
            val leading = i == groups.lastIndex
            parts += group(g, full = !leading) + (if (units[i].isEmpty()) "" else " " + units[i])
        }
        return parts.joinToString(" ")
    }

    /** 0..999; [full] = not the first group, so "không trăm" / "linh" are spelled out. */
    private fun group(g: Int, full: Boolean): String {
        val h = g / 100
        val t = g / 10 % 10
        val u = g % 10
        val out = ArrayList<String>()
        if (h > 0 || full) out += digits[h] + " trăm"
        when {
            t == 0 && u > 0 && (h > 0 || full) -> out += "linh"
            t == 1 -> out += "mười"
            t > 1 -> out += digits[t] + " mươi"
        }
        if (u > 0) {
            out += when {
                u == 1 && t > 1 -> "mốt"
                u == 4 && t > 1 -> "tư"
                u == 5 && t > 0 -> "lăm"
                else -> digits[u]
            }
        }
        return out.joinToString(" ")
    }
}

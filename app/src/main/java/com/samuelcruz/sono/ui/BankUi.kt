package com.samuelcruz.sono.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.samuelcruz.sono.qr.Bank
import com.samuelcruz.sono.qr.BankStyle

// Copied from qr_chuyen_khoan: how a bank is drawn without its logo.

fun bankColor(bank: Bank): Color = Color(BankStyle.color(bank))

/** A rounded square in the bank's colour with its letters, standing in for a logo. */
@Composable
fun BankBadge(bank: Bank, size: Dp = 44.dp) {
    val letters = BankStyle.monogram(bank)
    val fontSize = when (letters.length) {
        1, 2 -> size.value * 0.36f
        3 -> size.value * 0.30f
        else -> size.value * 0.24f
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .background(
                Brush.linearGradient(listOf(bankColor(bank), bankColor(bank).copy(alpha = 0.78f))),
                RoundedCornerShape(size * 0.28f),
            ),
    ) {
        Text(letters, color = Color.White, fontWeight = FontWeight.Bold, fontSize = fontSize.sp, maxLines = 1)
    }
}

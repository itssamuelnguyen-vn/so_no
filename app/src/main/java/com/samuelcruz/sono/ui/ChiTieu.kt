package com.samuelcruz.sono.ui

import android.content.Context
import android.content.Intent

/**
 * Hands a transaction to the owner's Chi Tiêu app: its "add" screen opens pre-filled (amount, note, income or
 * expense) and the user picks the wallet there. Contract in chi_tieu's MainActivity (ACTION_ADD + extras).
 */
object ChiTieu {
    private const val PACKAGE = "com.samuelcruz.chitieu"
    private const val ACTION_ADD = "com.samuelcruz.chitieu.ADD"
    private const val KIND_EXPENSE = 0
    private const val KIND_INCOME = 1

    fun installed(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(PACKAGE, 0) }.isSuccess

    /** False when Chi Tiêu is not installed. */
    fun add(context: Context, income: Boolean, amount: Long, note: String): Boolean {
        if (amount <= 0) return false
        val intent = Intent(ACTION_ADD).setClassName(PACKAGE, "$PACKAGE.MainActivity")
            .putExtra("kind", if (income) KIND_INCOME else KIND_EXPENSE)
            .putExtra("amount", amount)
            .putExtra("note", note)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}

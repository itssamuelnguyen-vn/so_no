package com.samuelcruz.sono.core

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A bank account to receive money into. [label] tells several apart ("Vietcombank lương"). */
@Serializable
data class BankAccount(val bin: String, val number: String, val name: String, val label: String = "")

/** Small settings kept in SharedPreferences (a key/value file, like a tiny settings table). */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun bool(key: String, default: Boolean = false) = sp.getBoolean(key, default)
    private fun setBool(key: String, value: Boolean) = sp.edit().putBoolean(key, value).apply()
    private fun str(key: String) = sp.getString(key, "") ?: ""
    private fun setStr(key: String, value: String) = sp.edit().putString(key, value).apply()

    var lockEnabled: Boolean
        get() = bool("lock_enabled")
        set(value) = setBool("lock_enabled", value)

    /** [THEME_SYSTEM], [THEME_LIGHT] or [THEME_DARK]. */
    var theme: Int
        get() = sp.getInt("theme", THEME_SYSTEM)
        set(value) = sp.edit().putInt("theme", value).apply()

    /** Morning notification about debts due and hụi rounds coming. */
    var reminders: Boolean
        get() = bool("reminders", true)
        set(value) = setBool("reminders", value)

    /** Hour of the morning notification (0..23). */
    var reminderHour: Int
        get() = sp.getInt("reminder_hour", 8)
        set(value) = sp.edit().putInt("reminder_hour", value).apply()

    /** My bank account, put into the VietQR codes for collecting money. */
    var bankBin: String
        get() = str("bank_bin")
        set(value) = setStr("bank_bin", value)
    var bankAccount: String
        get() = str("bank_account")
        set(value) = setStr("bank_account", value)
    var bankName: String
        get() = str("bank_name")
        set(value) = setStr("bank_name", value)

    /** Weekly automatic backup folder (a SAF tree URI), "" = off. */
    var autoBackupDir: String
        get() = str("auto_backup_dir")
        set(value) = setStr("auto_backup_dir", value)
    var autoBackupLast: Long
        get() = sp.getLong("auto_backup_last", 0)
        set(value) = sp.edit().putLong("auto_backup_last", value).apply()
    var autoBackupFailed: Boolean
        get() = bool("auto_backup_failed")
        set(value) = setBool("auto_backup_failed", value)

    /** Bank accounts for VietQR codes; the first one is the default. Older versions kept one account in bank_*. */
    var accounts: List<BankAccount>
        get() {
            val text = str("accounts")
            if (text.isNotEmpty()) return runCatching { json.decodeFromString(ListSerializer(BankAccount.serializer()), text) }.getOrDefault(emptyList())
            return if (bankBin.isNotEmpty() && bankAccount.isNotEmpty()) listOf(BankAccount(bankBin, bankAccount, bankName)) else emptyList()
        }
        set(value) = setStr("accounts", json.encodeToString(ListSerializer(BankAccount.serializer()), value))

    /** My name and phone, printed on the IOU (giấy xác nhận nợ). */
    var myName: String
        get() = str("my_name").ifEmpty { bankName }
        set(value) = setStr("my_name", value)
    var myPhone: String
        get() = str("my_phone")
        set(value) = setStr("my_phone", value)

    /** My ID card number and address, optional lines on the IOU. */
    var myIdNumber: String
        get() = str("my_id_number")
        set(value) = setStr("my_id_number", value)
    var myAddress: String
        get() = str("my_address")
        set(value) = setStr("my_address", value)

    /** Offer to record payments in the Chi Tiêu app too. */
    var chiTieuLink: Boolean
        get() = bool("chitieu_link", true)
        set(value) = setBool("chitieu_link", value)

    // Google Drive sync (one snapshot file in the app's hidden Drive folder, see sync/SnapshotSync).
    var syncEmail: String
        get() = str("sync_email")
        set(value) = setStr("sync_email", value)
    var syncFileId: String
        get() = str("sync_file_id")
        set(value) = setStr("sync_file_id", value)
    var syncVersion: Long
        get() = sp.getLong("sync_version", 0)
        set(value) = sp.edit().putLong("sync_version", value).apply()
    var lastSyncAt: Long
        get() = sp.getLong("last_sync_at", 0)
        set(value) = sp.edit().putLong("last_sync_at", value).apply()
    /** Last local change (ms); newer than [lastSyncAt] = this phone has something to upload. */
    var changedAt: Long
        get() = sp.getLong("changed_at", 0)
        set(value) = sp.edit().putLong("changed_at", value).apply()
    var syncError: String
        get() = str("sync_error")
        set(value) = setStr("sync_error", value)

    /** Epoch day the morning notification last ran, so it never repeats on the same day. */
    var lastReminderDay: Long
        get() = sp.getLong("last_reminder_day", 0)
        set(value) = sp.edit().putLong("last_reminder_day", value).apply()

    /** The notification permission was asked once (Android 13+). */
    var askedNotifications: Boolean
        get() = bool("asked_notifications")
        set(value) = setBool("asked_notifications", value)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    companion object {
        const val THEME_SYSTEM = 0
        const val THEME_LIGHT = 1
        const val THEME_DARK = 2
    }
}

/**
 * Fingerprint / face / screen-lock PIN using Android's own prompt (no library). The phone's screen lock is
 * always accepted as a fallback, so the user can never be locked out by a broken sensor.
 */
object AppLock {
    /** Seconds in the background before the app asks again. */
    const val GRACE_MILLIS = 30_000L

    /** The phone must have a screen lock, otherwise there is nothing to check against. */
    fun canLock(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.getSystemService(BiometricManager::class.java)
                .canAuthenticate(BIOMETRIC_WEAK or DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS
        } else {
            context.getSystemService(KeyguardManager::class.java).isDeviceSecure
        }

    fun authenticate(activity: Activity, onSuccess: () -> Unit, onError: (String) -> Unit = {}) {
        val builder = BiometricPrompt.Builder(activity)
            .setTitle("Mở Sổ Nợ & Hụi")
            .setSubtitle("Dùng vân tay, khuôn mặt hoặc mã khóa màn hình")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
        } else {
            // Android 10 only has this older switch for "also accept the screen lock".
            @Suppress("DEPRECATION")
            builder.setDeviceCredentialAllowed(true)
        }
        builder.build().authenticate(
            CancellationSignal(),
            activity.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onError(errString.toString())
            },
        )
    }
}

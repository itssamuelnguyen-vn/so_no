package com.samuelcruz.sono.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.samuelcruz.sono.core.DebtPlan
import com.samuelcruz.sono.core.Hui
import com.samuelcruz.sono.core.HuiCycle
import com.samuelcruz.sono.core.HuiRoundInput
import com.samuelcruz.sono.core.HuiRules
import com.samuelcruz.sono.core.HuiSchedule
import com.samuelcruz.sono.core.Installment
import com.samuelcruz.sono.core.Installments
import com.samuelcruz.sono.core.InterestMethod
import com.samuelcruz.sono.core.Repayment
import kotlinx.serialization.Serializable
import java.time.LocalDate

// Tables (like ActiveRecord models). Days are epoch days (see core/Dates), money is whole đồng.

/** Someone I lend to or borrow from. */
@Serializable
@Entity(tableName = "person")
data class Person(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val phone: String = "",
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * One loan. [direction] is a [com.samuelcruz.sono.core.Direction], [plan] a [DebtPlan], [interestMethod] an
 * [InterestMethod]. One-time debts use [dueDay] as the due date; installment debts use it as the first
 * installment's date, then [cycle]/[everyDays] (monthly on that day of the month, or every N days).
 */
@Serializable
@Entity(
    tableName = "debt",
    foreignKeys = [ForeignKey(entity = Person::class, parentColumns = ["id"], childColumns = ["personId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("personId")],
)
data class Debt(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val personId: Long,
    val direction: Int,
    val amount: Long,
    val startDay: Long,
    val dueDay: Long? = null,
    val plan: Int = DebtPlan.ONCE,
    val term: Int = 1,
    val cycle: Int = HuiCycle.MONTHLY,
    val everyDays: Int = 30,
    val interestMethod: Int = InterestMethod.NONE,
    /** The rate as typed, per month or per year ([rateYearly]). */
    val ratePercent: Double = 0.0,
    val rateYearly: Boolean = false,
    /** Notify this many days before each due date; −1 = no reminder. */
    val remindDays: Int = 1,
    val note: String = "",
    /** File name in filesDir/photos (the IOU or a screenshot of the chat). */
    val photo: String? = null,
    /** Set when settled ("tất toán"); interest stops on that day. */
    val closedDay: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    /** Installments moved to another day ("dời hạn"), as "number=epochDay;…" (see [DueOverrides]). */
    @ColumnInfo(defaultValue = "''") val dueOverrides: String = "",
    /** Late fee rule, a [com.samuelcruz.sono.core.PenaltyMode]. */
    @ColumnInfo(defaultValue = "0") val penaltyMode: Int = 0,
    /** Đồng per day / once, or percent per day of the late amount, depending on [penaltyMode]. */
    @ColumnInfo(defaultValue = "0") val penaltyValue: Double = 0.0,
    /** Signature pictures (file names in filesDir/photos) for the IOU PDF. */
    val signLender: String? = null,
    val signBorrower: String? = null,
) {
    val closed: Boolean get() = closedDay != null
    val hasInterest: Boolean get() = interestMethod != InterestMethod.NONE && ratePercent > 0
    val monthlyRate: Double get() = if (hasInterest) Installments.monthlyRate(ratePercent, rateYearly) else 0.0

    fun schedule(): List<Installment> =
        if (plan != DebtPlan.INSTALLMENTS) emptyList()
        else Installments.schedule(
            amount, term, interestMethod, monthlyRate, cycle, everyDays,
            LocalDate.ofEpochDay(dueDay ?: startDay), DueOverrides.parse(dueOverrides),
        )
}

/** A repayment of a [Debt]. */
@Serializable
@Entity(
    tableName = "payment",
    foreignKeys = [ForeignKey(entity = Debt::class, parentColumns = ["id"], childColumns = ["debtId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("debtId")],
)
data class Payment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val debtId: Long,
    val amount: Long,
    val day: Long,
    val note: String = "",
    /** Installment number this pays (installment debts), null = paying ahead / a one-time debt. */
    val installment: Int? = null,
    val createdAt: Long = System.currentTimeMillis(),
    /** [PaymentKind.PAY] (money back) or [PaymentKind.TOP_UP] (more money lent / borrowed on the same debt). */
    @ColumnInfo(defaultValue = "0") val kind: Int = PaymentKind.PAY,
) {
    val isTopUp: Boolean get() = kind == PaymentKind.TOP_UP
    fun toRepayment() = Repayment(day, amount, isTopUp)
}

object PaymentKind {
    const val PAY = 0
    const val TOP_UP = 1
}

/** "3=20511;5=20600" ↔ {3: 2026-02-28, 5: …}: installments whose due day was moved. */
object DueOverrides {
    fun parse(text: String): Map<Int, LocalDate> = text.split(';').mapNotNull { part ->
        val (n, d) = part.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
        val num = n.toIntOrNull() ?: return@mapNotNull null
        val day = d.toLongOrNull() ?: return@mapNotNull null
        num to LocalDate.ofEpochDay(day)
    }.toMap()

    fun format(map: Map<Int, LocalDate>): String =
        map.entries.sortedBy { it.key }.joinToString(";") { "${it.key}=${it.value.toEpochDay()}" }
}

object HuiRole {
    /** I am the organizer (chủ hụi): I collect from everyone and hand the pot to the winner. */
    const val OWNER = 1
    /** I hold one or more shares in someone else's dây. */
    const val MEMBER = 2
}

/** One dây hụi and its rules (fixed once the first round is held). */
@Serializable
@Entity(tableName = "hui")
data class HuiGroup(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val role: Int,
    /** The organizer's name when I am only a member. */
    val ownerName: String = "",
    val amount: Long,
    val slots: Int,
    val cycle: Int = HuiCycle.MONTHLY,
    val everyDays: Int = 7,
    val startDay: Long,
    val ownerFirst: Boolean = true,
    val bidding: Boolean = true,
    val thaoMode: Int = 0,
    val thaoValue: Long = 0,
    val thaoLast: Boolean = true,
    /** Notify this many days before each round; −1 = no reminder. */
    val remindDays: Int = 1,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    /** Hụi lãi cố định: every winner "bids" this fixed amount (no bidding); 0 = normal bidding. */
    @ColumnInfo(defaultValue = "0") val fixedBid: Long = 0,
) {
    val rules: HuiRules
        get() = HuiRules(amount, slots, ownerFirst, bidding, thaoMode, thaoValue, thaoLast, fixedBid)
    val periodDays: Double get() = Hui.periodDays(cycle, everyDays)
    val start: LocalDate get() = LocalDate.ofEpochDay(startDay)
    fun dates(): List<LocalDate> = HuiSchedule.dates(cycle, everyDays, start, slots)
}

/** One share (chân) of a dây, numbered 1..N. One person may hold several. */
@Serializable
@Entity(
    tableName = "hui_slot",
    primaryKeys = ["huiId", "number"],
    foreignKeys = [ForeignKey(entity = HuiGroup::class, parentColumns = ["id"], childColumns = ["huiId"], onDelete = ForeignKey.CASCADE)],
)
data class HuiSlot(
    val huiId: Long,
    val number: Int,
    val name: String = "",
    val phone: String = "",
    /** My own share. */
    val mine: Boolean = false,
    /** Stopped paying from this round on (bỏ ngang / giật hụi): the organizer pays its dues instead. */
    val droppedFrom: Int? = null,
) {
    val displayName: String get() = name.ifBlank { "Chân $number" }
}

/** A round that was held ("khui"): who collected, with what bid, on which day. */
@Serializable
@Entity(
    tableName = "hui_round",
    primaryKeys = ["huiId", "number"],
    foreignKeys = [ForeignKey(entity = HuiGroup::class, parentColumns = ["id"], childColumns = ["huiId"], onDelete = ForeignKey.CASCADE)],
)
data class HuiRound(
    val huiId: Long,
    val number: Int,
    /** Slot number of the winner. */
    val winner: Int,
    val bid: Long,
    val day: Long,
    /** The pot was handed over (organizer) / received (member who won). */
    val paidOut: Boolean = false,
    val note: String = "",
    /** Every bid of the round, "slot=amount;…" (see [com.samuelcruz.sono.core.HuiBids]); "" = only the winner's. */
    @ColumnInfo(defaultValue = "''") val bids: String = "",
) {
    fun toInput() = HuiRoundInput(number, winner, bid)
}

/** A share paid its due for a round. */
@Serializable
@Entity(
    tableName = "hui_paid",
    primaryKeys = ["huiId", "number", "slot"],
    foreignKeys = [
        ForeignKey(
            entity = HuiRound::class, parentColumns = ["huiId", "number"], childColumns = ["huiId", "number"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("huiId", "number")],
)
data class HuiPaid(
    val huiId: Long,
    val number: Int,
    val slot: Int,
    val paidAt: Long = System.currentTimeMillis(),
    /** Paid by the organizer for a share that dropped out. */
    @ColumnInfo(defaultValue = "0") val covered: Boolean = false,
)

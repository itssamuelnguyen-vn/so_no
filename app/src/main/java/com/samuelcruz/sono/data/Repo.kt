package com.samuelcruz.sono.data

import android.content.Context
import androidx.room.withTransaction
import com.samuelcruz.sono.core.Dates
import com.samuelcruz.sono.core.Direction
import com.samuelcruz.sono.core.plain

/**
 * Every write goes through here (like service objects in Rails), so derived rules live in one place: a debt paid
 * off is marked settled, unpaying reopens it, removing a debt removes its photo. [onChange] refreshes the widget.
 */
class Repo(private val context: Context, private val db: AppDatabase, private val onChange: () -> Unit) {

    // People

    suspend fun savePerson(p: Person): Long {
        val id = if (p.id == 0L) db.people().insert(p) else p.id.also { db.people().update(p) }
        onChange()
        return id
    }

    /** The person with this name (accents and case ignored), created if new. A phone fills in a missing one. */
    suspend fun personFor(name: String, phone: String): Long {
        val key = name.trim().plain()
        val existing = db.people().all().firstOrNull { it.name.trim().plain() == key }
        if (existing != null) {
            if (existing.phone.isBlank() && phone.isNotBlank()) db.people().update(existing.copy(phone = phone))
            return existing.id
        }
        return db.people().insert(Person(name = name.trim(), phone = phone.trim()))
    }

    suspend fun deletePerson(p: Person) {
        val photos = db.debts().all().filter { it.personId == p.id }.mapNotNull { it.photo }
        db.people().delete(p)
        photos.forEach { Photos.file(context, it).delete() }
        onChange()
    }

    // Debts

    suspend fun saveDebt(d: Debt): Long {
        val old = if (d.id == 0L) null else db.debts().get(d.id)
        val id = if (d.id == 0L) db.debts().insert(d) else d.id.also { db.debts().update(d) }
        if (old?.photo != null && old.photo != d.photo) Photos.file(context, old.photo).delete()
        settleIfPaid(id)
        onChange()
        return id
    }

    suspend fun deleteDebt(d: Debt) {
        db.debts().delete(d)
        d.photo?.let { Photos.file(context, it).delete() }
        onChange()
    }

    suspend fun addPayment(p: Payment) {
        db.payments().insert(p)
        settleIfPaid(p.debtId)
        onChange()
    }

    /** Fixes a payment (amount, day, note). */
    suspend fun updatePayment(p: Payment) {
        db.payments().update(p)
        settleIfPaid(p.debtId, reopen = true)
        onChange()
    }

    /** More money lent / borrowed on the same one-time debt ("vay thêm"). */
    suspend fun topUp(debt: Debt, amount: Long, day: Long, note: String) {
        db.payments().insert(Payment(debtId = debt.id, amount = amount, day = day, note = note, kind = PaymentKind.TOP_UP))
        if (debt.closed) db.debts().update(debt.copy(closedDay = null))
        onChange()
    }

    /** New due day for a one-time debt ("gia hạn"). */
    suspend fun extend(debt: Debt, day: Long) {
        db.debts().update(debt.copy(dueDay = day))
        onChange()
    }

    /** Moves installment [number] to [day]; with [shiftLater], every later unpaid installment moves by as much. */
    suspend fun postpone(debt: Debt, number: Int, day: Long, shiftLater: Boolean) {
        val state = DebtState(debt, db.payments().forDebt(debt.id), Dates.todayDay())
        val items = state.plan?.items ?: return
        val old = items.getOrNull(number - 1)?.installment?.due ?: return
        val delta = day - old.toEpochDay()
        val map = DueOverrides.parse(debt.dueOverrides).toMutableMap()
        for (s in items) {
            val n = s.installment.number
            if (n == number || (shiftLater && n > number && !s.full)) map[n] = s.installment.due.plusDays(delta)
        }
        db.debts().update(debt.copy(dueOverrides = DueOverrides.format(map)))
        onChange()
    }

    suspend fun deletePayment(p: Payment) {
        db.payments().delete(p)
        settleIfPaid(p.debtId, reopen = true)
        onChange()
    }

    /** Ticks an installment as fully paid (adds what is missing) or unticks it (removes its payments). */
    suspend fun markInstallment(debt: Debt, number: Int, paid: Boolean, day: Long = Dates.todayDay()) {
        if (paid) {
            val state = DebtState(debt, db.payments().forDebt(debt.id), Dates.todayDay())
            val item = state.plan?.items?.getOrNull(number - 1) ?: return
            if (item.left > 0) db.payments().insert(Payment(debtId = debt.id, amount = item.left, day = day, installment = number))
        } else {
            db.payments().deleteInstallment(debt.id, number)
        }
        settleIfPaid(debt.id, reopen = !paid)
        onChange()
    }

    suspend fun settle(debt: Debt, day: Long = Dates.todayDay()) {
        db.debts().update(debt.copy(closedDay = day))
        onChange()
    }

    suspend fun reopen(debt: Debt) {
        db.debts().update(debt.copy(closedDay = null))
        onChange()
    }

    /**
     * Closes a debt that is fully paid (on its last payment day). With [reopen], a closed debt that is owed again
     * (a payment was removed) opens again.
     */
    private suspend fun settleIfPaid(debtId: Long, reopen: Boolean = false) {
        val d = db.debts().get(debtId) ?: return
        val payments = db.payments().forDebt(debtId)
        val open = DebtState(d.copy(closedDay = null), payments, Dates.todayDay())
        val lastDay = payments.maxOfOrNull { it.day } ?: Dates.todayDay()
        if (!d.closed && open.left <= 0 && open.paid > 0) db.debts().update(d.copy(closedDay = lastDay))
        else if (reopen && d.closed && open.left > 0) db.debts().update(d.copy(closedDay = null))
    }

    // Hụi

    suspend fun saveHui(g: HuiGroup, slots: List<HuiSlot>): Long {
        val id = db.withTransaction {
            val id = if (g.id == 0L) db.hui().insertGroup(g) else g.id.also { db.hui().updateGroup(g) }
            db.hui().upsertSlots(slots.map { it.copy(huiId = id) })
            db.hui().trimSlots(id, g.slots)
            id
        }
        onChange()
        return id
    }

    /** A debt "they owe me" for what the organizer paid in place of a share that dropped out. */
    suspend fun coveredToDebt(group: HuiGroup, slot: HuiSlot, amount: Long): Long {
        val pid = personFor(slot.displayName, slot.phone)
        val id = db.debts().insert(
            Debt(
                personId = pid, direction = Direction.LENT, amount = amount, startDay = Dates.todayDay(),
                note = "Hụi ${group.name} (chân ${slot.number}): chủ hụi đóng thay khi bỏ ngang",
            ),
        )
        onChange()
        return id
    }

    suspend fun saveSlot(s: HuiSlot) {
        db.hui().updateSlot(s)
        onChange()
    }

    suspend fun deleteHui(g: HuiGroup) {
        db.hui().deleteGroup(g)
        onChange()
    }

    /**
     * Saves a round. My own shares (other than the winner) are marked paid when I am the organizer, since I hand
     * my own money over myself.
     */
    suspend fun addRound(group: HuiGroup, round: HuiRound) {
        db.withTransaction {
            db.hui().insertRound(round)
            if (group.role == HuiRole.OWNER) {
                val slots = db.hui().slots(group.id).filter { it.number != round.winner }
                db.hui().upsertPaid(slots.filter { it.mine && it.droppedFrom == null }.map { HuiPaid(group.id, round.number, it.number) })
                // Shares that dropped out: the organizer pays for them.
                db.hui().upsertPaid(
                    slots.filter { it.droppedFrom != null && it.droppedFrom <= round.number }
                        .map { HuiPaid(group.id, round.number, it.number, covered = true) },
                )
            }
        }
        onChange()
    }

    /** Saves a held round; a changed winner keeps its paid marks only for shares that still pay. */
    suspend fun updateRound(round: HuiRound) {
        db.withTransaction {
            db.hui().updateRound(round)
            db.hui().deletePaid(round.huiId, round.number, round.winner)
        }
        onChange()
    }

    /**
     * Share [slot] stops paying from round [from] (bỏ ngang / giật hụi): the organizer covers its dues, so held
     * rounds from then on are marked paid by the organizer. null brings it back.
     */
    suspend fun dropSlot(group: HuiGroup, slot: HuiSlot, from: Int?) {
        db.withTransaction {
            db.hui().updateSlot(slot.copy(droppedFrom = from))
            val rounds = db.hui().rounds(group.id)
            val covered = db.hui().allPaid().filter { it.huiId == group.id && it.slot == slot.number && it.covered }
            covered.forEach { db.hui().deletePaid(group.id, it.number, slot.number) }
            if (from != null) {
                db.hui().upsertPaid(
                    rounds.filter { it.number >= from && it.winner != slot.number }
                        .map { HuiPaid(group.id, it.number, slot.number, covered = true) },
                )
            }
        }
        onChange()
    }

    /**
     * The organizer paid share [slot]'s due for round [number] in its place: the share is marked paid and a debt
     * "they owe me" is recorded for that person. Returns the new debt id.
     */
    suspend fun dueToDebt(group: HuiGroup, slot: HuiSlot, number: Int, amount: Long): Long {
        val pid = personFor(slot.displayName, slot.phone)
        val id = db.debts().insert(
            Debt(
                personId = pid, direction = Direction.LENT, amount = amount, startDay = Dates.todayDay(),
                dueDay = Dates.todayDay(), note = "Hụi ${group.name} kỳ $number (chân ${slot.number}): chủ hụi đóng thay",
            ),
        )
        db.hui().upsertPaid(listOf(HuiPaid(group.id, number, slot.number, covered = true)))
        onChange()
        return id
    }

    suspend fun deleteRound(round: HuiRound) {
        db.hui().deleteRound(round)
        onChange()
    }

    suspend fun setPaid(huiId: Long, number: Int, slots: List<Int>, paid: Boolean) {
        if (paid) db.hui().upsertPaid(slots.map { HuiPaid(huiId, number, it) })
        else slots.forEach { db.hui().deletePaid(huiId, number, it) }
        onChange()
    }
}

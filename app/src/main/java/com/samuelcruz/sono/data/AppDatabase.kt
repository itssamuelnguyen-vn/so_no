package com.samuelcruz.sono.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

// DAOs are like ActiveRecord scopes: each method is one SQL query, Flow ones re-run when the table changes.

@Dao
interface PersonDao {
    @Query("SELECT * FROM person ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<Person>>

    @Query("SELECT * FROM person WHERE id = :id")
    fun observe(id: Long): Flow<Person?>

    @Query("SELECT * FROM person")
    suspend fun all(): List<Person>

    @Query("SELECT * FROM person WHERE id = :id")
    suspend fun get(id: Long): Person?

    @Insert
    suspend fun insert(p: Person): Long

    @Update
    suspend fun update(p: Person)

    @Delete
    suspend fun delete(p: Person)
}

@Dao
interface DebtDao {
    @Query("SELECT * FROM debt")
    fun observeAll(): Flow<List<Debt>>

    @Query("SELECT * FROM debt WHERE id = :id")
    fun observe(id: Long): Flow<Debt?>

    @Query("SELECT * FROM debt")
    suspend fun all(): List<Debt>

    @Query("SELECT * FROM debt WHERE id = :id")
    suspend fun get(id: Long): Debt?

    /** Every picture file a debt uses (IOU photo, signatures). */
    @Query("SELECT photo FROM debt WHERE photo IS NOT NULL UNION SELECT signLender FROM debt WHERE signLender IS NOT NULL UNION SELECT signBorrower FROM debt WHERE signBorrower IS NOT NULL")
    suspend fun photos(): List<String>

    @Insert
    suspend fun insert(d: Debt): Long

    @Update
    suspend fun update(d: Debt)

    @Delete
    suspend fun delete(d: Debt)
}

@Dao
interface PaymentDao {
    @Query("SELECT * FROM payment")
    fun observeAll(): Flow<List<Payment>>

    @Query("SELECT * FROM payment WHERE debtId = :debtId")
    fun observeFor(debtId: Long): Flow<List<Payment>>

    @Query("SELECT * FROM payment")
    suspend fun all(): List<Payment>

    @Query("SELECT * FROM payment WHERE debtId = :debtId")
    suspend fun forDebt(debtId: Long): List<Payment>

    @Insert
    suspend fun insert(p: Payment): Long

    @Update
    suspend fun update(p: Payment)

    @Delete
    suspend fun delete(p: Payment)

    @Query("DELETE FROM payment WHERE debtId = :debtId AND installment = :number")
    suspend fun deleteInstallment(debtId: Long, number: Int)
}

@Dao
interface HuiDao {
    @Query("SELECT * FROM hui ORDER BY createdAt DESC")
    fun observeGroups(): Flow<List<HuiGroup>>

    @Query("SELECT * FROM hui WHERE id = :id")
    fun observeGroup(id: Long): Flow<HuiGroup?>

    @Query("SELECT * FROM hui")
    suspend fun groups(): List<HuiGroup>

    @Query("SELECT * FROM hui WHERE id = :id")
    suspend fun group(id: Long): HuiGroup?

    @Insert
    suspend fun insertGroup(g: HuiGroup): Long

    @Update
    suspend fun updateGroup(g: HuiGroup)

    @Delete
    suspend fun deleteGroup(g: HuiGroup)

    @Query("SELECT * FROM hui_slot")
    fun observeAllSlots(): Flow<List<HuiSlot>>

    @Query("SELECT * FROM hui_slot WHERE huiId = :huiId ORDER BY number")
    fun observeSlots(huiId: Long): Flow<List<HuiSlot>>

    @Query("SELECT * FROM hui_slot")
    suspend fun allSlots(): List<HuiSlot>

    @Query("SELECT * FROM hui_slot WHERE huiId = :huiId ORDER BY number")
    suspend fun slots(huiId: Long): List<HuiSlot>

    @Upsert
    suspend fun upsertSlots(s: List<HuiSlot>)

    @Update
    suspend fun updateSlot(s: HuiSlot)

    @Query("DELETE FROM hui_slot WHERE huiId = :huiId AND number > :count")
    suspend fun trimSlots(huiId: Long, count: Int)

    @Query("SELECT * FROM hui_round")
    fun observeAllRounds(): Flow<List<HuiRound>>

    @Query("SELECT * FROM hui_round WHERE huiId = :huiId ORDER BY number")
    fun observeRounds(huiId: Long): Flow<List<HuiRound>>

    @Query("SELECT * FROM hui_round")
    suspend fun allRounds(): List<HuiRound>

    @Query("SELECT * FROM hui_round WHERE huiId = :huiId ORDER BY number")
    suspend fun rounds(huiId: Long): List<HuiRound>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRound(r: HuiRound)

    @Update
    suspend fun updateRound(r: HuiRound)

    @Delete
    suspend fun deleteRound(r: HuiRound)

    @Query("SELECT * FROM hui_paid")
    fun observeAllPaid(): Flow<List<HuiPaid>>

    @Query("SELECT * FROM hui_paid WHERE huiId = :huiId")
    fun observePaid(huiId: Long): Flow<List<HuiPaid>>

    @Query("SELECT * FROM hui_paid")
    suspend fun allPaid(): List<HuiPaid>

    @Upsert
    suspend fun upsertPaid(p: List<HuiPaid>)

    @Query("DELETE FROM hui_paid WHERE huiId = :huiId AND number = :number AND slot = :slot")
    suspend fun deletePaid(huiId: Long, number: Int, slot: Int)
}

@Dao
interface WipeDao {
    @Query("DELETE FROM person") suspend fun people()
    @Query("DELETE FROM hui") suspend fun hui()
    @Insert suspend fun people(p: List<Person>)
    @Insert suspend fun debts(d: List<Debt>)
    @Insert suspend fun payments(p: List<Payment>)
    @Insert suspend fun groups(g: List<HuiGroup>)
    @Insert suspend fun slots(s: List<HuiSlot>)
    @Insert suspend fun rounds(r: List<HuiRound>)
    @Insert suspend fun paid(p: List<HuiPaid>)
}

@Database(
    entities = [Person::class, Debt::class, Payment::class, HuiGroup::class, HuiSlot::class, HuiRound::class, HuiPaid::class],
    version = 2,
    exportSchema = true,
    // 1 → 2 (0.2.0): only new columns with defaults, so Room writes the migration itself.
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun people(): PersonDao
    abstract fun debts(): DebtDao
    abstract fun payments(): PaymentDao
    abstract fun hui(): HuiDao
    abstract fun wipe(): WipeDao

    companion object {
        // Later schema changes: bump the version and add a migration (never destructive: these are real records).
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "sono.db").build()
    }
}

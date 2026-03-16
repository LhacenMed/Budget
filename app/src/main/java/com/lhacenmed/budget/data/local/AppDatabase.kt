package com.lhacenmed.budget.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lhacenmed.budget.data.model.BudgetContribution
import com.lhacenmed.budget.data.model.SpendingItem

@Database(
    entities = [
        PendingSpendingItem::class,
        GroceryItem::class,
        SpendingItem::class,       // cache
        BudgetContribution::class, // cache
    ],
    version = 4
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun pendingItemDao(): PendingItemDao
    abstract fun groceryDao(): GroceryDao
    abstract fun cacheDao(): CacheDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE grocery_items (
                        id          INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        userId      TEXT    NOT NULL,
                        name        TEXT    NOT NULL,
                        checkedDate TEXT,
                        createdAt   TEXT    NOT NULL
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Cache for SpendingItem — column names match Kotlin property names (Room convention)
                // Note: createdAt (not created_at) — @SerialName is for Supabase only, Room ignores it
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS cached_spending_items (
                        id          TEXT NOT NULL PRIMARY KEY,
                        date        TEXT NOT NULL,
                        shopper     TEXT NOT NULL,
                        name        TEXT NOT NULL,
                        quantity    TEXT NOT NULL,
                        price       REAL NOT NULL,
                        description TEXT,
                        createdAt   TEXT NOT NULL
                    )
                """.trimIndent())

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS cached_contributions (
                        id          TEXT NOT NULL PRIMARY KEY,
                        contributor TEXT NOT NULL,
                        amount      REAL NOT NULL,
                        createdAt   TEXT NOT NULL
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // ── cached_spending_items: drop and recreate (ephemeral cache, no data to preserve)
                db.execSQL("DROP TABLE IF EXISTS cached_spending_items")
                db.execSQL("""
                    CREATE TABLE cached_spending_items (
                        id          TEXT NOT NULL PRIMARY KEY,
                        date        TEXT NOT NULL,
                        shopper     TEXT NOT NULL,
                        name        TEXT NOT NULL,
                        quantity    TEXT,
                        price       REAL NOT NULL,
                        description TEXT,
                        createdAt   TEXT NOT NULL
                    )
                """.trimIndent())

                // ── pending_spending_items: recreate with data copy to preserve offline queue
                db.execSQL("""
                    CREATE TABLE pending_spending_items_new (
                        localId     INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        date        TEXT NOT NULL,
                        shopper     TEXT NOT NULL,
                        name        TEXT NOT NULL,
                        quantity    TEXT,
                        price       REAL NOT NULL,
                        description TEXT,
                        createdAt   TEXT NOT NULL
                    )
                """.trimIndent())
                // Copy existing rows — old quantity was NOT NULL so just map it across;
                // empty string maps to empty string (not null), which is fine
                db.execSQL("""
                    INSERT INTO pending_spending_items_new
                        (localId, date, shopper, name, quantity, price, description, createdAt)
                    SELECT localId, date, shopper, name, quantity, price, description, createdAt
                    FROM pending_spending_items
                """.trimIndent())
                db.execSQL("DROP TABLE pending_spending_items")
                db.execSQL("ALTER TABLE pending_spending_items_new RENAME TO pending_spending_items")
            }
        }
    }
}

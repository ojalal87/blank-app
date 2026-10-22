package com.rootguard.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        AppInstallationEntity::class, AppVersionEntity::class, PrincipalEntity::class, PrincipalMemberEntity::class,
        ProfileEntity::class, PolicyRevisionEntity::class, PolicyRuleEntity::class, RuntimeStateEntity::class,
        ApplyTransactionEntity::class, TemporaryOverrideEntity::class, CapabilitySnapshotEntity::class,
        ConsentEntity::class, WizardDraftEntity::class, AuditEventEntity::class, OutboxEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class ControlDatabase : RoomDatabase() {
    abstract fun inventory(): InventoryDao
    abstract fun policy(): PolicyDao

    companion object {
        const val NAME = "control.db"

        /**
         * No fallbackToDestructiveMigration: policy, consent and audit must never be silently dropped.
         * A missing migration fails loudly and the UI enters recovery instead.
         */
        fun build(context: Context, name: String? = NAME): ControlDatabase {
            val builder = if (name == null) Room.inMemoryDatabaseBuilder(context, ControlDatabase::class.java)
            else Room.databaseBuilder(context, ControlDatabase::class.java, name)
            return builder
                .addCallback(object : Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        db.execSQL("PRAGMA foreign_keys=ON")
                        ControlSchema.install(db)
                    }
                })
                .build()
        }
    }
}

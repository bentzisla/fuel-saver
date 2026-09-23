package com.fuelroute.data.backup

/**
 * Runs [block] inside a single database transaction. Abstracted so [DefaultBackupRepository] can be
 * unit-tested with an inline runner instead of a real Room database, while production wraps
 * `RoomDatabase.withTransaction` (see `RoomTransactionRunner` in `di/DatabaseModule.kt`).
 */
interface TransactionRunner {
    suspend fun <R> run(block: suspend () -> R): R
}
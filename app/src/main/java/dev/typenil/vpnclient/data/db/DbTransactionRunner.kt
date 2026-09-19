package dev.typenil.vpnclient.data.db

import androidx.room.withTransaction
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs a block inside a DB transaction. A seam so repository logic that
 * commits multi-table writes stays JVM-testable without a Room runtime.
 */
interface DbTransactionRunner {
    suspend fun <T> run(block: suspend () -> T): T
}

@Singleton
class RoomDbTransactionRunner @Inject constructor(
    private val db: AppDatabase,
) : DbTransactionRunner {
    override suspend fun <T> run(block: suspend () -> T): T =
        db.withTransaction(block)
}

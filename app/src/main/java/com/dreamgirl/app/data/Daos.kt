package com.dreamgirl.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Insert
    suspend fun insert(message: MessageEntity): Long

    @Update
    suspend fun update(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE sessionDate = :date ORDER BY createdAt ASC, id ASC")
    fun observeByDate(date: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE sessionDate = :date ORDER BY createdAt ASC, id ASC")
    suspend fun byDate(date: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE sessionDate = :date ORDER BY createdAt DESC, id DESC LIMIT :limit")
    suspend fun latestByDate(date: String, limit: Int): List<MessageEntity>

    @Query("SELECT * FROM messages ORDER BY createdAt DESC, id DESC LIMIT 1")
    suspend fun lastMessage(): MessageEntity?

    @Query("SELECT * FROM messages WHERE createdAt >= :from AND createdAt <= :to ORDER BY createdAt ASC, id ASC")
    suspend fun between(from: Long, to: Long): List<MessageEntity>

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM messages WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun byId(id: Long): MessageEntity?

    @Query("DELETE FROM messages WHERE sessionDate = :date")
    suspend fun deleteByDate(date: String)

    @Query("SELECT COUNT(*) FROM messages WHERE sessionDate = :date")
    suspend fun countByDate(date: String): Int


    /**
     * 每个会话日的最后一条消息 —— 会话列表用。
     * 用 GROUP BY 兜住「同一毫秒两条消息」的并列情况。
     */
    @Query(
        """
        SELECT m.sessionDate AS sessionDate,
               m.content AS content,
               m.role AS role,
               m.createdAt AS createdAt,
               m.proactive AS proactive,
               (SELECT COUNT(*) FROM messages x WHERE x.sessionDate = m.sessionDate) AS messageCount
        FROM messages m
        WHERE m.createdAt = (
            SELECT MAX(y.createdAt) FROM messages y WHERE y.sessionDate = m.sessionDate
        )
        GROUP BY m.sessionDate
        ORDER BY m.createdAt DESC
        """
    )
    fun observeSessionPreviews(): Flow<List<SessionPreview>>

    @Query("SELECT COUNT(*) FROM messages")
    fun observeTotalCount(): Flow<Int>
}

@Dao
interface DaySessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: DaySessionEntity)

    @Query("SELECT * FROM day_sessions WHERE date = :date")
    suspend fun byDate(date: String): DaySessionEntity?

    @Query("SELECT * FROM day_sessions ORDER BY date DESC")
    fun observeAll(): Flow<List<DaySessionEntity>>

    @Query("SELECT * FROM day_sessions ORDER BY date DESC")
    suspend fun all(): List<DaySessionEntity>

    @Query("SELECT * FROM day_sessions ORDER BY date DESC LIMIT 1")
    suspend fun latest(): DaySessionEntity?
}

@Dao
interface DaySummaryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(summary: DaySummaryEntity)

    @Query("SELECT * FROM day_summaries WHERE date = :date")
    suspend fun byDate(date: String): DaySummaryEntity?

    @Query("SELECT * FROM day_summaries WHERE date = :date")
    fun observeByDate(date: String): Flow<DaySummaryEntity?>

    @Query("SELECT * FROM day_summaries ORDER BY date DESC")
    fun observeAll(): Flow<List<DaySummaryEntity>>

    @Query("SELECT * FROM day_summaries ORDER BY date DESC")
    suspend fun all(): List<DaySummaryEntity>

    @Query("SELECT * FROM day_summaries WHERE date < :before ORDER BY date ASC")
    suspend fun olderThan(before: String): List<DaySummaryEntity>

    /** 还没生成总结、但已经有对话的日子。 */
    @Query(
        """
        SELECT DISTINCT m.sessionDate FROM messages m
        LEFT JOIN day_summaries s ON s.date = m.sessionDate
        WHERE s.date IS NULL AND m.sessionDate < :today
        ORDER BY m.sessionDate ASC
        """
    )
    suspend fun datesMissingSummary(today: String): List<String>

    @Query("DELETE FROM day_summaries WHERE date = :date")
    suspend fun deleteByDate(date: String)
}

@Dao
interface MemoryDao {
    @Insert
    suspend fun insert(item: MemoryItemEntity): Long

    @Insert
    suspend fun insertAll(items: List<MemoryItemEntity>)

    @Update
    suspend fun update(item: MemoryItemEntity)

    @Query("SELECT * FROM memory_items ORDER BY sourceDate DESC, weight DESC, id DESC")
    fun observeAll(): Flow<List<MemoryItemEntity>>

    @Query("SELECT * FROM memory_items ORDER BY sourceDate DESC, weight DESC, id DESC")
    suspend fun all(): List<MemoryItemEntity>

    @Query("SELECT * FROM memory_items WHERE sourceDate = :date ORDER BY weight DESC, id DESC")
    suspend fun byDate(date: String): List<MemoryItemEntity>

    @Query("SELECT * FROM memory_items WHERE id = :id")
    suspend fun byId(id: Long): MemoryItemEntity?

    @Query("SELECT * FROM memory_items ORDER BY lastReferencedAt ASC, weight ASC")
    suspend fun allForRetrieval(): List<MemoryItemEntity>

    @Query("DELETE FROM memory_items WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM memory_items WHERE sourceDate = :date")
    suspend fun deleteByDate(date: String)

    @Query("UPDATE memory_items SET lastReferencedAt = :at WHERE id IN (:ids)")
    suspend fun markReferenced(ids: List<Long>, at: Long)

    @Query("SELECT COUNT(*) FROM memory_items")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM memory_items WHERE content = :content AND sourceDate = :date")
    suspend fun countExact(content: String, date: String): Int
}

@Dao
interface PersonaDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(persona: PersonaEntity)

    @Query("SELECT * FROM persona WHERE id = 1")
    suspend fun get(): PersonaEntity?

    @Query("SELECT * FROM persona WHERE id = 1")
    fun observe(): Flow<PersonaEntity?>
}

@Dao
interface MonthDigestDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(digest: MonthDigestEntity)

    @Query("SELECT * FROM month_digests ORDER BY month DESC")
    suspend fun all(): List<MonthDigestEntity>

    @Query("SELECT * FROM month_digests ORDER BY month DESC")
    fun observeAll(): Flow<List<MonthDigestEntity>>
}

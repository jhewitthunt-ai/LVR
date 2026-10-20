package com.example.locationaudiblereminder.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LocationReminderDao {
    @Query("SELECT * FROM location_reminders")
    fun getAll(): Flow<List<LocationReminder>>

    @Query("SELECT * FROM location_reminders WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): LocationReminder?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(reminder: LocationReminder): Long

    @Delete
    suspend fun delete(reminder: LocationReminder)
}

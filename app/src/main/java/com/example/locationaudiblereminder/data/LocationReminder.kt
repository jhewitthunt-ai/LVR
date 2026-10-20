package com.example.locationaudiblereminder.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "location_reminders")
data class LocationReminder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val audioFilePath: String,
    val radius: Float = 100f // default radius in meters
)

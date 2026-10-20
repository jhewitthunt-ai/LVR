package com.example.locationaudiblereminder.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.locationaudiblereminder.data.AppDatabase
import com.example.locationaudiblereminder.data.LocationReminder
import com.example.locationaudiblereminder.geofence.GeofenceManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.getDatabase(application).locationReminderDao()
    private val geofenceManager = GeofenceManager(application)

    val reminders: StateFlow<List<LocationReminder>> = dao.getAll()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun addReminder(reminder: LocationReminder) {
        viewModelScope.launch {
            val id = dao.insert(reminder)
            val newReminder = reminder.copy(id = id)
            geofenceManager.addGeofence(
                reminder = newReminder,
                onSuccess = { /* Handle success */ },
                onFailure = { /* Handle failure */ }
            )
        }
    }

    fun deleteReminder(reminder: LocationReminder) {
        viewModelScope.launch {
            dao.delete(reminder)
            geofenceManager.removeGeofence(reminder.id)
        }
    }
}

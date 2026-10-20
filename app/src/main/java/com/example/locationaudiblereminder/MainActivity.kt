package com.example.locationaudiblereminder

import android.Manifest
import android.annotation.SuppressLint
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.locationaudiblereminder.audio.AudioRecorderManager
import com.example.locationaudiblereminder.data.LocationReminder
import com.example.locationaudiblereminder.geofence.GeofenceBroadcastReceiver
import com.example.locationaudiblereminder.theme.LocationAudibleReminderTheme
import com.example.locationaudiblereminder.ui.MainViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private lateinit var audioRecorderManager: AudioRecorderManager

    @OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audioRecorderManager = AudioRecorderManager(this)

        setContent {
            LocationAudibleReminderTheme {
                val context = LocalContext.current
                val permissionsState = rememberMultiplePermissionsState(
                    permissions = listOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.RECORD_AUDIO,
                        Manifest.permission.POST_NOTIFICATIONS
                    )
                )

                LaunchedEffect(Unit) {
                    if (!permissionsState.allPermissionsGranted) {
                        permissionsState.launchMultiplePermissionRequest()
                    }
                }

                var showAddDialog by remember { mutableStateOf(false) }

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("Location Voice Reminders") },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        )
                    },
                    floatingActionButton = {
                        ExtendedFloatingActionButton(
                            onClick = { showAddDialog = true },
                            icon = { Icon(Icons.Default.Add, contentDescription = "Add Reminder") },
                            text = { Text("Add Location") }
                        )
                    }
                ) { padding ->
                    val reminders by viewModel.reminders.collectAsState()

                    if (reminders.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(padding)
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "No location reminders yet.\nTap 'Add Location' to pick a spot on the map and record a 3-word voice alert.",
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(padding),
                            contentPadding = PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(reminders, key = { it.id }) { reminder ->
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    ListItem(
                                        headlineContent = {
                                            Text(
                                                reminder.name,
                                                style = MaterialTheme.typography.titleMedium
                                            )
                                        },
                                        supportingContent = {
                                            Text(
                                                String.format(
                                                    Locale.US,
                                                    "Lat: %.5f, Lng: %.5f (Radius: %.0fm)",
                                                    reminder.latitude,
                                                    reminder.longitude,
                                                    reminder.radius
                                                )
                                            )
                                        },
                                        trailingContent = {
                                            Row {
                                                IconButton(
                                                    onClick = {
                                                        GeofenceBroadcastReceiver.triggerNow(
                                                            context,
                                                            reminder
                                                        )
                                                    }
                                                ) {
                                                    Icon(
                                                        Icons.Default.PlayArrow,
                                                        contentDescription = "Test Voice Alert"
                                                    )
                                                }
                                                IconButton(
                                                    onClick = { viewModel.deleteReminder(reminder) }
                                                ) {
                                                    Icon(
                                                        Icons.Default.Delete,
                                                        contentDescription = "Delete"
                                                    )
                                                }
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }

                    if (showAddDialog) {
                        AddReminderDialog(
                            onDismiss = { showAddDialog = false },
                            onSave = { name, latLng, audioPath ->
                                viewModel.addReminder(
                                    LocationReminder(
                                        name = name,
                                        latitude = latLng.latitude,
                                        longitude = latLng.longitude,
                                        audioFilePath = audioPath
                                    )
                                )
                                showAddDialog = false
                            },
                            audioRecorderManager = audioRecorderManager
                        )
                    }
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun AddReminderDialog(
    onDismiss: () -> Unit,
    onSave: (String, LatLng, String) -> Unit,
    audioRecorderManager: AudioRecorderManager
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val defaultLocation = LatLng(51.5074, -0.1278) // Default London / central coordinates
    var name by remember { mutableStateOf("") }
    var selectedLocation by remember { mutableStateOf(defaultLocation) }
    var audioPath by remember { mutableStateOf<String?>(null) }
    var isRecording by remember { mutableStateOf(false) }
    var recordingSecondsLeft by remember { mutableIntStateOf(4) }

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(defaultLocation, 13f)
    }

    // Automatically stop recording after 4 seconds (up to 3 words)
    LaunchedEffect(isRecording) {
        if (isRecording) {
            recordingSecondsLeft = 4
            while (recordingSecondsLeft > 0 && isRecording) {
                delay(1000L)
                recordingSecondsLeft--
            }
            if (isRecording) {
                audioRecorderManager.stopRecording()
                isRecording = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = {
            if (isRecording) {
                audioRecorderManager.stopRecording()
                isRecording = false
            }
            onDismiss()
        },
        title = { Text("New Location Voice Reminder") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Location Name (e.g. Home, Gym)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        String.format(
                            Locale.US,
                            "Pin: %.4f, %.4f",
                            selectedLocation.latitude,
                            selectedLocation.longitude
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                    TextButton(
                        onClick = {
                            val client = LocationServices.getFusedLocationProviderClient(context)
                            client.lastLocation.addOnSuccessListener { loc ->
                                if (loc != null) {
                                    val currentLatLng = LatLng(loc.latitude, loc.longitude)
                                    selectedLocation = currentLatLng
                                    coroutineScope.launch {
                                        cameraPositionState.animate(
                                            CameraUpdateFactory.newLatLngZoom(currentLatLng, 15f)
                                        )
                                    }
                                }
                            }
                        }
                    ) {
                        Text("Use Current GPS")
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(210.dp)
                ) {
                    GoogleMap(
                        modifier = Modifier.fillMaxSize(),
                        cameraPositionState = cameraPositionState,
                        onMapClick = { latLng -> selectedLocation = latLng }
                    ) {
                        Marker(
                            state = MarkerState(position = selectedLocation),
                            title = name.ifBlank { "Selected Location" }
                        )
                    }
                }

                Text(
                    "Tap the map to move the pin, then record your voice message (up to 3 words):",
                    style = MaterialTheme.typography.bodySmall
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        modifier = Modifier.weight(1f),
                        colors = if (isRecording) {
                            ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        } else {
                            ButtonDefaults.buttonColors()
                        },
                        onClick = {
                            if (!isRecording) {
                                val path = audioRecorderManager.startRecording()
                                if (path != null) {
                                    audioPath = path
                                    isRecording = true
                                }
                            } else {
                                audioRecorderManager.stopRecording()
                                isRecording = false
                            }
                        }
                    ) {
                        Text(
                            if (isRecording) {
                                "Stop (${recordingSecondsLeft}s)"
                            } else if (audioPath == null) {
                                "Record (Max 3 Words)"
                            } else {
                                "Re-record"
                            }
                        )
                    }

                    if (audioPath != null && !isRecording) {
                        OutlinedButton(
                            onClick = {
                                GeofenceBroadcastReceiver.playAudioAsync(audioPath!!)
                            }
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Play")
                            Spacer(Modifier.width(4.dp))
                            Text("Play")
                        }
                    }
                }

                if (audioPath != null && !isRecording) {
                    Text(
                        "Voice message ready!",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (isRecording) {
                        audioRecorderManager.stopRecording()
                        isRecording = false
                    }
                    if (name.isNotBlank() && audioPath != null) {
                        onSave(name, selectedLocation, audioPath!!)
                    }
                },
                enabled = name.isNotBlank() && audioPath != null && !isRecording
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    if (isRecording) {
                        audioRecorderManager.stopRecording()
                        isRecording = false
                    }
                    onDismiss()
                }
            ) {
                Text("Cancel")
            }
        }
    )
}

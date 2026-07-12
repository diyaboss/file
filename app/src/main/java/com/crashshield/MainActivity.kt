package com.crashshield

import android.Manifest
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.crashshield.location.CrashShieldLocationProvider
import com.crashshield.location.LocationState
import com.crashshield.location.LocationStatus
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var locationProvider: CrashShieldLocationProvider
    private lateinit var statusTextView: TextView

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false

        if (fineGranted || coarseGranted) {
            Log.d("CrashShieldGPS", "Permission granted. Starting location updates.")
            locationProvider.startLocationUpdates()
        } else {
            Log.e("CrashShieldGPS", "Location permissions denied.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        statusTextView = TextView(this).apply {
            textSize = 18f
            setPadding(32, 32, 32, 32)
            text = "Initializing GPS..."
        }
        setContentView(statusTextView)

        locationProvider = CrashShieldLocationProvider(this)

        lifecycleScope.launch {
            locationProvider.locationState.collect { state ->
                updateUi(state)
                logLocationState(state)
            }
        }

        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    private fun updateUi(state: LocationState) {
        val textBuilder = StringBuilder()
        textBuilder.append("Status: ${state.status}\n\n")
        
        state.location?.let { loc ->
            val speedKmH = loc.speedMps * 3.6f
            val speedMph = loc.speedMps * 2.23694f
            val speedText = String.format(
                java.util.Locale.US,
                "%.2f m/s (%.1f km/h | %.1f mph)",
                loc.speedMps,
                speedKmH,
                speedMph
            )

            textBuilder.append("Latitude: ${loc.latitude}\n")
            textBuilder.append("Longitude: ${loc.longitude}\n")
            textBuilder.append("Accuracy: ${loc.accuracyMeters} m\n")
            textBuilder.append("Speed: $speedText\n")
            textBuilder.append("Bearing: ${loc.bearingDegrees}°\n")
            textBuilder.append("Timestamp: ${loc.timestampMs}\n")
        } ?: run {
            if (state.errorMessage != null) {
                textBuilder.append("Error: ${state.errorMessage}\n")
            } else {
                textBuilder.append("Waiting for location data...\n")
            }
        }
        statusTextView.text = textBuilder.toString()
    }

    private fun logLocationState(state: LocationState) {
        val statusStr = state.status.name
        state.location?.let { loc ->
            Log.d(
                "CrashShieldGPS",
                "Status: $statusStr | Lat: ${loc.latitude}, Lng: ${loc.longitude}, Acc: ${loc.accuracyMeters}m, Bear: ${loc.bearingDegrees}°, Speed: ${loc.speedMps}m/s, Time: ${loc.timestampMs}"
            )
        } ?: run {
            Log.d("CrashShieldGPS", "Status: $statusStr | No location available. Error: ${state.errorMessage ?: "None"}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        locationProvider.cleanup()
    }
}

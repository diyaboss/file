package com.crashshield.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class CrashShieldLocationProvider(private val context: Context) {

    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _locationState = MutableStateFlow(LocationState(LocationStatus.FETCHING))
    val locationState: StateFlow<LocationState> = _locationState.asStateFlow()

    private var isListening = false
    private var currentBestLocation: RiderLocation? = null
    private val ACCURACY_THRESHOLD_METERS = 2.0f
    
    // Smoothing factor for speed updates (Exponential Moving Average)
    private var smoothedSpeedMps = 0f
    private val SPEED_SMOOTHING_ALPHA = 0.3f

    private fun isBetterLocation(newLocation: RiderLocation, currentBest: RiderLocation?): Boolean {
        if (currentBest == null) {
            return true
        }

        val timeDelta = newLocation.timestampMs - currentBest.timestampMs
        val isSignificantlyNewer = timeDelta > 10000L // 10 seconds
        val isSignificantlyOlder = timeDelta < -10000L
        val isNewer = timeDelta > 0

        if (isSignificantlyNewer) {
            return true
        } else if (isSignificantlyOlder) {
            return false
        }

        val accuracyDelta = newLocation.accuracyMeters - currentBest.accuracyMeters
        val isMoreAccurate = accuracyDelta < 0
        val isLessAccurate = accuracyDelta > 0
        val isSignificantlyLessAccurate = accuracyDelta > 30f

        if (isMoreAccurate) {
            return true
        } else if (isNewer && !isLessAccurate) {
            return true
        } else if (isNewer && !isSignificantlyLessAccurate) {
            return true
        }
        return false
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            val lastLoc = locationResult.lastLocation
            if (lastLoc != null) {
                // Filter speed jitter and apply EMA smoothing
                var rawSpeed = if (lastLoc.hasSpeed()) lastLoc.speed else 0f
                if (lastLoc.hasSpeedAccuracy() && lastLoc.speedAccuracyMetersPerSecond > 1.5f) {
                    rawSpeed = 0f
                }
                if (rawSpeed < 0.25f) {
                    rawSpeed = 0f
                }

                smoothedSpeedMps = if (currentBestLocation == null) {
                    rawSpeed
                } else {
                    SPEED_SMOOTHING_ALPHA * rawSpeed + (1f - SPEED_SMOOTHING_ALPHA) * smoothedSpeedMps
                }

                val riderLocation = RiderLocation(
                    latitude = lastLoc.latitude,
                    longitude = lastLoc.longitude,
                    accuracyMeters = lastLoc.accuracy,
                    speedMps = smoothedSpeedMps,
                    bearingDegrees = lastLoc.bearing,
                    timestampMs = lastLoc.time
                )

                if (isBetterLocation(riderLocation, currentBestLocation)) {
                    currentBestLocation = riderLocation
                    val isAccurate = riderLocation.accuracyMeters <= ACCURACY_THRESHOLD_METERS
                    _locationState.value = LocationState(
                        status = if (isAccurate) LocationStatus.AVAILABLE else LocationStatus.FETCHING,
                        location = riderLocation,
                        errorMessage = if (isAccurate) null else "Low accuracy GPS fix (${riderLocation.accuracyMeters}m). Waiting for high accuracy fix..."
                    )
                }
            } else {
                _locationState.value = LocationState(
                    status = LocationStatus.DISABLED_OR_UNAVAILABLE,
                    errorMessage = "Location update was received but location is null"
                )
            }
        }
    }

    init {
        if (hasRequiredPermissions()) {
            startLocationUpdates()
        } else {
            _locationState.value = LocationState(LocationStatus.PERMISSION_MISSING)
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        val fineLocation = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        
        val coarseLocation = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        
        return fineLocation || coarseLocation
    }

    fun startLocationUpdates() {
        if (!hasRequiredPermissions()) {
            _locationState.value = LocationState(
                status = LocationStatus.PERMISSION_MISSING,
                errorMessage = "Location permissions are not granted"
            )
            return
        }

        val hasFine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasFine) {
            Log.w("CrashShieldGPS", "Warning: ACCESS_FINE_LOCATION is missing. Location tracking will fall back to coarse network level, resulting in low accuracy.")
        }

        if (!LocationUtils.isLocationEnabled(context)) {
            _locationState.value = LocationState(
                status = LocationStatus.DISABLED_OR_UNAVAILABLE,
                errorMessage = "Location services are disabled on this device"
            )
            return
        }

        _locationState.value = LocationState(LocationStatus.FETCHING)

        scope.launch {
            try {
                val lastLoc = fusedLocationClient.lastLocation.await()
                if (lastLoc != null && _locationState.value.status == LocationStatus.FETCHING) {
                    var initialSpeed = if (lastLoc.hasSpeed()) lastLoc.speed else 0f
                    if (lastLoc.hasSpeedAccuracy() && lastLoc.speedAccuracyMetersPerSecond > 1.5f) {
                        initialSpeed = 0f
                    }
                    if (initialSpeed < 0.25f) {
                        initialSpeed = 0f
                    }
                    smoothedSpeedMps = initialSpeed

                    val riderLocation = RiderLocation(
                        latitude = lastLoc.latitude,
                        longitude = lastLoc.longitude,
                        accuracyMeters = lastLoc.accuracy,
                        speedMps = smoothedSpeedMps,
                        bearingDegrees = lastLoc.bearing,
                        timestampMs = lastLoc.time
                    )

                    val isFresh = System.currentTimeMillis() - lastLoc.time <= 15000L
                    val isAccurate = lastLoc.accuracy <= ACCURACY_THRESHOLD_METERS
                    if (isFresh && isAccurate && isBetterLocation(riderLocation, currentBestLocation)) {
                        currentBestLocation = riderLocation
                        _locationState.value = LocationState(
                            status = LocationStatus.AVAILABLE,
                            location = riderLocation
                        )
                    }
                }
            } catch (e: SecurityException) {
                Log.e("CrashShieldGPS", "SecurityException getting last location: ${e.message}")
                _locationState.value = LocationState(
                    status = LocationStatus.PERMISSION_MISSING,
                    errorMessage = e.message
                )
            } catch (e: Exception) {
                Log.e("CrashShieldGPS", "Error getting last location: ${e.message}")
                _locationState.value = LocationState(
                    status = LocationStatus.ERROR,
                    errorMessage = e.message
                )
            }
        }

        // Configure location request specifically for maximum accuracy
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(1000L)
            .setMaxUpdateDelayMillis(0L) // Deliver updates immediately with no batching
            .setGranularity(Granularity.GRANULARITY_FINE) // Request high precision location
            .setWaitForAccurateLocation(true) // Force provider to wait for accurate location
            .setMinUpdateDistanceMeters(0f) // Receive updates even if stationary
            .build()

        try {
            if (!isListening) {
                fusedLocationClient.requestLocationUpdates(
                    locationRequest,
                    locationCallback,
                    Looper.getMainLooper()
                )
                isListening = true
            }
        } catch (e: SecurityException) {
            Log.e("CrashShieldGPS", "SecurityException requesting location updates: ${e.message}")
            _locationState.value = LocationState(
                status = LocationStatus.PERMISSION_MISSING,
                errorMessage = e.message
            )
        } catch (e: Exception) {
            Log.e("CrashShieldGPS", "Error requesting location updates: ${e.message}")
            _locationState.value = LocationState(
                status = LocationStatus.ERROR,
                errorMessage = e.message
            )
        }
    }

    fun cleanup() {
        try {
            if (isListening) {
                fusedLocationClient.removeLocationUpdates(locationCallback)
                isListening = false
            }
        } catch (e: Exception) {
            Log.e("CrashShieldGPS", "Error removing location updates: ${e.message}")
        }
        scope.cancel()
    }
}

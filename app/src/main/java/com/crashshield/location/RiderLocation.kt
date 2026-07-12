package com.crashshield.location

data class RiderLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val speedMps: Float,
    val bearingDegrees: Float,
    val timestampMs: Long
)

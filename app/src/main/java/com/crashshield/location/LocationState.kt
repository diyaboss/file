package com.crashshield.location

data class LocationState(
    val status: LocationStatus,
    val location: RiderLocation? = null,
    val errorMessage: String? = null
)

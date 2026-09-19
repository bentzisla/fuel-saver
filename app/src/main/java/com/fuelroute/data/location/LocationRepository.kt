package com.fuelroute.data.location

import android.content.Context
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

data class Coordinates(val latitude: Double, val longitude: Double)

interface LocationRepository {
    suspend fun currentLocation(): Coordinates?
}

@Singleton
class FusedLocationRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) : LocationRepository {

    private val client = LocationServices.getFusedLocationProviderClient(context)

    override suspend fun currentLocation(): Coordinates? = withTimeoutOrNull(20_000) {
        suspendCancellableCoroutine { continuation ->
            try {
                client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                    .addOnSuccessListener { location ->
                        if (continuation.isActive) {
                            if (location != null) {
                                continuation.resume(Coordinates(location.latitude, location.longitude))
                            } else {
                                continuation.resume(null)
                            }
                        }
                    }
                    .addOnFailureListener {
                        if (continuation.isActive) continuation.resume(null)
                    }
            } catch (e: SecurityException) {
                if (continuation.isActive) continuation.resume(null)
            }
        }
    }
}
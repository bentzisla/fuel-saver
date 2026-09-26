package com.fuelroute.data.routes

import android.util.Log
import com.fuelroute.BuildConfig
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

interface ElevationRepository {
    /**
     * Elevation samples in meters, evenly spaced along [encodedPolyline] (Google returns
     * `samples` points including both endpoints). Null when the polyline is unusable, the API
     * call failed (no network) or was denied (e.g. `REQUEST_DENIED` - Elevation API not enabled
     * on this key) - callers must degrade gracefully rather than throw.
     */
    suspend fun profile(encodedPolyline: String, samples: Int = DEFAULT_SAMPLES): List<Double>?

    companion object {
        const val DEFAULT_SAMPLES = 32
    }
}

@Singleton
class GoogleElevationRepository @Inject constructor(
    private val service: ElevationService,
) : ElevationRepository {

    // Instance (singleton) flag: log the first failure only, so a denied/misconfigured key
    // doesn't spam FuelRoute's log on every route search.
    private var loggedFailureOnce = false

    override suspend fun profile(encodedPolyline: String, samples: Int): List<Double>? {
        if (encodedPolyline.isBlank() || samples < 2) return null
        return try {
            val response = service.elevation(
                path = "enc:$encodedPolyline",
                samples = samples,
                apiKey = BuildConfig.ELEVATION_API_KEY,
            )
            if (response.status != "OK") {
                logFailureOnce("elevation API status=${response.status} ${response.errorMessage.orEmpty()}")
                return null
            }
            val values = response.results.map { it.elevation }
            values.takeIf { it.size >= 2 }
        } catch (e: HttpException) {
            logFailureOnce("elevation HTTP ${e.code()}")
            null
        } catch (e: IOException) {
            logFailureOnce("elevation network failure: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun logFailureOnce(message: String) {
        if (loggedFailureOnce) return
        loggedFailureOnce = true
        Log.w("FuelRoute", "route grade term disabled: $message")
    }
}

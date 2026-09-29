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

        /** Target spacing between elevation samples along the route. */
        const val SAMPLE_SPACING_M = 250.0

        /** Minimum samples per route, so short routes still resolve their step boundaries. */
        const val MIN_SAMPLES = 16

        /**
         * Google's per-request cap on `samples` for a path request. One request at this cap is
         * enough (routes over ~128 km get coarser than [SAMPLE_SPACING_M]), so no request split
         * is needed; the elevation API bills per request, not per sample.
         */
        const val MAX_SAMPLES = 512

        /** About one sample per [SAMPLE_SPACING_M] of [distanceMeters], in [MIN_SAMPLES]..[MAX_SAMPLES]. */
        fun samplesFor(distanceMeters: Double): Int {
            if (!distanceMeters.isFinite() || distanceMeters <= 0.0) return MIN_SAMPLES
            val wanted = kotlin.math.ceil(distanceMeters / SAMPLE_SPACING_M) + 1.0
            return wanted.coerceIn(MIN_SAMPLES.toDouble(), MAX_SAMPLES.toDouble()).toInt()
        }
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
        val requested = samples.coerceAtMost(ElevationRepository.MAX_SAMPLES)
        return try {
            val response = service.elevation(
                path = "enc:$encodedPolyline",
                samples = requested,
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

package com.fuelroute.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "vehicle")
data class VehicleEntity(
    @PrimaryKey val id: String,
    val name: String,
    val fuelType: String,
    val ratedCombinedL100: Double,
    val engineDisplacementL: Double?,
    val tankCapacityL: Double?,
    val fuelRateCorrection: Double,
    val manualCurve: String?,
    val vin: String?,
    val grade: String = "95",
    /** Curb weight for the elevation/grade fuel term; see [com.fuelroute.domain.model.VehicleProfile.massKg]. */
    val massKg: Double = com.fuelroute.domain.fuel.GradeModel.DEFAULT_VEHICLE_MASS_KG,
    val createdAtMs: Long,
)

@Entity(tableName = "learning_extras")
data class LearningExtrasEntity(
    @PrimaryKey val vehicleId: String,
    val coldStartExtraL: Double,
    val coldStartCount: Int,
    val updatedAtMs: Long,
)

@Entity(tableName = "obd_sample", indices = [Index(value = ["timestampMs"])])
data class ObdSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vehicleId: String,
    val timestampMs: Long,
    val speedKmh: Double?,
    val rpm: Double?,
    val mafGps: Double?,
    val fuelRateLph: Double?,
    val mapKpa: Double?,
    val intakeTempC: Double?,
    val coolantTempC: Double?,
    val engineLoadPct: Double?,
    val fuelLevelPct: Double?,
)

@Entity(tableName = "speed_bin_stats", primaryKeys = ["vehicleId", "binIndex"])
data class SpeedBinStatsEntity(
    val vehicleId: String,
    val binIndex: Int,
    val distanceKm: Double,
    val fuelL: Double,
    val seconds: Double,
    val samples: Int,
)

/**
 * Values of [TripEntity.source]: a real OBD drive, a simulated "הדגמה" ride, or a trip that
 * exists only to hold a manual post-drive cost entry for a search that was never OBD-logged
 * (see [com.fuelroute.data.history.DriveHistoryRepository.recordManualCost]).
 */
object TripSource {
    const val REAL = "real"
    const val DEMO = "demo"
    const val MANUAL = "manual"
}

@Entity(
    tableName = "trip",
    indices = [
        Index(value = ["vehicleId"]),
        Index(value = ["routeSearchId"]),
        Index(value = ["isOpen"]),
    ],
)
data class TripEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vehicleId: String,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val distanceKm: Double,
    val fuelL: Double,
    val avgSpeedKmh: Double,
    val maxSpeedKmh: Double,
    val idleSeconds: Double,
    val isOpen: Int = 0,
    val routeSearchId: Int? = null,
    val coldStartFuelL: Double = 0.0,
    val actualCost: Double = 0.0,
    val pricePerLiterAtTrip: Double = 0.0,
    val linkedAtMs: Long? = null,
    val source: String = TripSource.REAL,
    // Manual post-drive entry recorded without OBD. When any of these is present the user's
    // numbers win over the OBD measurement in History (a computed view, not a stored overwrite).
    val manualCost: Double? = null,
    val manualDistanceKm: Double? = null,
    val manualLitersPer100Km: Double? = null,
    val manualEnteredAtMs: Long? = null,
)

@Entity(tableName = "refuel", indices = [Index(value = ["vehicleId"])])
data class RefuelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vehicleId: String,
    val timestampMs: Long,
    val liters: Double,
    val totalPrice: Double,
    val isFull: Boolean,
    val pricePerLiter: Double = 0.0,
    val grade: String = "95",
)

@Entity(
    tableName = "route_search",
    indices = [Index(value = ["timestampMs"]), Index(value = ["departureTimeMs"])],
)
data class RouteSearchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val originLabel: String,
    val destinationLabel: String,
    val timestampMs: Long,
    val cheapestCost: Double,
    val fastestCost: Double,
    val savedAmount: Double,
    val predictedLiters: Double,
    val distanceKm: Double,
    val durationMin: Double,
    val selectedRouteIndex: Int = 0,
    val departureTimeMs: Long? = null,
    val tollUnknown: Int = 0,
    val selectedPredictedCost: Double = 0.0,
    val selectedPredictedLiters: Double = 0.0,
    val selectedPredictedMinutes: Double = 0.0,
    val pricePerLiterAtSearch: Double = 0.0,
    val destinationPlaceId: String? = null,
    val destinationLat: Double? = null,
    val destinationLng: Double? = null,
)

@Entity(tableName = "favorite_destination")
data class FavoriteDestinationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val placeId: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val sortOrder: Int = 0,
    val createdAtMs: Long,
)

@Entity(tableName = "favorite_obd_device")
data class FavoriteObdDeviceEntity(
    @PrimaryKey val address: String,
    val name: String,
    val sortOrder: Int = 0,
    val createdAtMs: Long,
)
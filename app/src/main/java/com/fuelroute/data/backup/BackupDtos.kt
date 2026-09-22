package com.fuelroute.data.backup

import com.fuelroute.data.db.FavoriteDestinationEntity
import com.fuelroute.data.db.LearningExtrasEntity
import com.fuelroute.data.db.RefuelEntity
import com.fuelroute.data.db.RouteSearchEntity
import com.fuelroute.data.db.SpeedBinStatsEntity
import com.fuelroute.data.db.TripEntity
import com.fuelroute.data.db.VehicleEntity
import com.fuelroute.domain.fuel.FuelModelOverrides
import kotlinx.serialization.Serializable

/** Current backup payload version. Bump when a snapshot field changes incompatibly. */
const val BACKUP_SCHEMA_VERSION = 1

/**
 * The whole user-owned dataset, serialized as one JSON document. Deliberately plain
 * `@Serializable` data classes rather than the Room entities, so the on-disk backup format
 * is decoupled from the database schema.
 *
 * `obd_sample` is intentionally absent: raw samples are disposable and pruned by retention.
 */
@Serializable
data class BackupPayload(
    val schemaVersion: Int = BACKUP_SCHEMA_VERSION,
    val exportedAtMs: Long = 0L,
    val vehicles: List<VehicleSnapshot> = emptyList(),
    val speedBins: List<SpeedBinSnapshot> = emptyList(),
    val trips: List<TripSnapshot> = emptyList(),
    val refuels: List<RefuelSnapshot> = emptyList(),
    val routeSearches: List<RouteSearchSnapshot> = emptyList(),
    val learningExtras: List<LearningExtrasSnapshot> = emptyList(),
    val favorites: List<FavoriteSnapshot> = emptyList(),
    val settings: SettingsSnapshot? = null,
    /**
     * Runtime fuel-model calibration overrides (card 33). Null in a backup predating this field;
     * import leaves the local overrides untouched in that case.
     */
    val modelOverrides: FuelModelOverrides? = null,
    val prices: List<PriceSnapshot> = emptyList(),
)

@Serializable
data class VehicleSnapshot(
    val id: String,
    val name: String,
    val fuelType: String,
    val ratedCombinedL100: Double,
    val engineDisplacementL: Double? = null,
    val tankCapacityL: Double? = null,
    val fuelRateCorrection: Double,
    val manualCurve: String? = null,
    val vin: String? = null,
    val grade: String = "95",
    val createdAtMs: Long,
)

/**
 * One learned speed bin. Import merges bins by summing the numeric fields, but a bin whose values
 * are *identical* to the local bin is skipped as an already-imported duplicate: re-importing the
 * same backup file must not double-count learning. (This is a value-identity heuristic rather than
 * a persisted per-export marker; a genuine second source with byte-identical accumulators would be
 * treated as a duplicate. That is acceptable because bins are running sums, so exact equality
 * across independent drives is vanishingly unlikely.)
 */
@Serializable
data class SpeedBinSnapshot(
    val vehicleId: String,
    val binIndex: Int,
    val distanceKm: Double,
    val fuelL: Double,
    val seconds: Double,
    val samples: Int,
)

@Serializable
data class TripSnapshot(
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
)

@Serializable
data class RefuelSnapshot(
    val vehicleId: String,
    val timestampMs: Long,
    val liters: Double,
    val totalPrice: Double,
    val isFull: Boolean,
    val pricePerLiter: Double = 0.0,
    val grade: String = "95",
)

@Serializable
data class RouteSearchSnapshot(
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

@Serializable
data class LearningExtrasSnapshot(
    val vehicleId: String,
    val coldStartExtraL: Double,
    val coldStartCount: Int,
    val updatedAtMs: Long,
)

@Serializable
data class FavoriteSnapshot(
    val label: String,
    val placeId: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val sortOrder: Int = 0,
    val createdAtMs: Long,
)

@Serializable
data class SettingsSnapshot(
    val valuePerMinute: Double,
    val navigationApp: String,
    val autoConnect: Boolean,
    val showOverlay: Boolean,
    val keepScreenOn: Boolean,
    val autoConnectIntroSeen: Boolean,
    val retentionDays: Int,
)

@Serializable
data class PriceSnapshot(
    val grade: String,
    val pricePerLiter: Double,
    val manuallyPinned: Boolean,
)

// --- Entity <-> snapshot mapping ---------------------------------------------------------

fun VehicleEntity.toSnapshot() = VehicleSnapshot(
    id = id,
    name = name,
    fuelType = fuelType,
    ratedCombinedL100 = ratedCombinedL100,
    engineDisplacementL = engineDisplacementL,
    tankCapacityL = tankCapacityL,
    fuelRateCorrection = fuelRateCorrection,
    manualCurve = manualCurve,
    vin = vin,
    grade = grade,
    createdAtMs = createdAtMs,
)

fun VehicleSnapshot.toEntity() = VehicleEntity(
    id = id,
    name = name,
    fuelType = fuelType,
    ratedCombinedL100 = ratedCombinedL100,
    engineDisplacementL = engineDisplacementL,
    tankCapacityL = tankCapacityL,
    fuelRateCorrection = fuelRateCorrection,
    manualCurve = manualCurve,
    vin = vin,
    grade = grade,
    createdAtMs = createdAtMs,
)

fun SpeedBinStatsEntity.toSnapshot() = SpeedBinSnapshot(
    vehicleId = vehicleId,
    binIndex = binIndex,
    distanceKm = distanceKm,
    fuelL = fuelL,
    seconds = seconds,
    samples = samples,
)

fun SpeedBinSnapshot.toEntity() = SpeedBinStatsEntity(
    vehicleId = vehicleId,
    binIndex = binIndex,
    distanceKm = distanceKm,
    fuelL = fuelL,
    seconds = seconds,
    samples = samples,
)

fun TripEntity.toSnapshot() = TripSnapshot(
    vehicleId = vehicleId,
    startedAtMs = startedAtMs,
    endedAtMs = endedAtMs,
    distanceKm = distanceKm,
    fuelL = fuelL,
    avgSpeedKmh = avgSpeedKmh,
    maxSpeedKmh = maxSpeedKmh,
    idleSeconds = idleSeconds,
    isOpen = isOpen,
    routeSearchId = routeSearchId,
    coldStartFuelL = coldStartFuelL,
    actualCost = actualCost,
    pricePerLiterAtTrip = pricePerLiterAtTrip,
    linkedAtMs = linkedAtMs,
)

/** Imported rows always get a fresh auto-generated id to avoid clashing with local rows. */
fun TripSnapshot.toEntity() = TripEntity(
    id = 0,
    vehicleId = vehicleId,
    startedAtMs = startedAtMs,
    endedAtMs = endedAtMs,
    distanceKm = distanceKm,
    fuelL = fuelL,
    avgSpeedKmh = avgSpeedKmh,
    maxSpeedKmh = maxSpeedKmh,
    idleSeconds = idleSeconds,
    isOpen = isOpen,
    routeSearchId = routeSearchId,
    coldStartFuelL = coldStartFuelL,
    actualCost = actualCost,
    pricePerLiterAtTrip = pricePerLiterAtTrip,
    linkedAtMs = linkedAtMs,
)

fun RefuelEntity.toSnapshot() = RefuelSnapshot(
    vehicleId = vehicleId,
    timestampMs = timestampMs,
    liters = liters,
    totalPrice = totalPrice,
    isFull = isFull,
    pricePerLiter = pricePerLiter,
    grade = grade,
)

fun RefuelSnapshot.toEntity() = RefuelEntity(
    id = 0,
    vehicleId = vehicleId,
    timestampMs = timestampMs,
    liters = liters,
    totalPrice = totalPrice,
    isFull = isFull,
    pricePerLiter = pricePerLiter,
    grade = grade,
)

fun RouteSearchEntity.toSnapshot() = RouteSearchSnapshot(
    originLabel = originLabel,
    destinationLabel = destinationLabel,
    timestampMs = timestampMs,
    cheapestCost = cheapestCost,
    fastestCost = fastestCost,
    savedAmount = savedAmount,
    predictedLiters = predictedLiters,
    distanceKm = distanceKm,
    durationMin = durationMin,
    selectedRouteIndex = selectedRouteIndex,
    departureTimeMs = departureTimeMs,
    tollUnknown = tollUnknown,
    selectedPredictedCost = selectedPredictedCost,
    selectedPredictedLiters = selectedPredictedLiters,
    selectedPredictedMinutes = selectedPredictedMinutes,
    pricePerLiterAtSearch = pricePerLiterAtSearch,
    destinationPlaceId = destinationPlaceId,
    destinationLat = destinationLat,
    destinationLng = destinationLng,
)

fun RouteSearchSnapshot.toEntity() = RouteSearchEntity(
    id = 0,
    originLabel = originLabel,
    destinationLabel = destinationLabel,
    timestampMs = timestampMs,
    cheapestCost = cheapestCost,
    fastestCost = fastestCost,
    savedAmount = savedAmount,
    predictedLiters = predictedLiters,
    distanceKm = distanceKm,
    durationMin = durationMin,
    selectedRouteIndex = selectedRouteIndex,
    departureTimeMs = departureTimeMs,
    tollUnknown = tollUnknown,
    selectedPredictedCost = selectedPredictedCost,
    selectedPredictedLiters = selectedPredictedLiters,
    selectedPredictedMinutes = selectedPredictedMinutes,
    pricePerLiterAtSearch = pricePerLiterAtSearch,
    destinationPlaceId = destinationPlaceId,
    destinationLat = destinationLat,
    destinationLng = destinationLng,
)

fun LearningExtrasEntity.toSnapshot() = LearningExtrasSnapshot(
    vehicleId = vehicleId,
    coldStartExtraL = coldStartExtraL,
    coldStartCount = coldStartCount,
    updatedAtMs = updatedAtMs,
)

fun LearningExtrasSnapshot.toEntity() = LearningExtrasEntity(
    vehicleId = vehicleId,
    coldStartExtraL = coldStartExtraL,
    coldStartCount = coldStartCount,
    updatedAtMs = updatedAtMs,
)

fun FavoriteDestinationEntity.toSnapshot() = FavoriteSnapshot(
    label = label,
    placeId = placeId,
    latitude = latitude,
    longitude = longitude,
    sortOrder = sortOrder,
    createdAtMs = createdAtMs,
)

/** Imported favorites always get a fresh auto-generated id; dedupe happens by place/label. */
fun FavoriteSnapshot.toEntity() = FavoriteDestinationEntity(
    id = 0,
    label = label,
    placeId = placeId,
    latitude = latitude,
    longitude = longitude,
    sortOrder = sortOrder,
    createdAtMs = createdAtMs,
)
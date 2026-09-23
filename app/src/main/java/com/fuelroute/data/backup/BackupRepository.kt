package com.fuelroute.data.backup

import com.fuelroute.data.db.FavoriteDestinationDao
import com.fuelroute.data.db.FavoriteDestinationEntity
import com.fuelroute.data.db.LearningExtrasDao
import com.fuelroute.data.db.RefuelDao
import com.fuelroute.data.db.RouteSearchDao
import com.fuelroute.data.db.SpeedBinDao
import com.fuelroute.data.db.TripDao
import com.fuelroute.data.db.VehicleDao
import com.fuelroute.data.places.FavoritesRepository
import com.fuelroute.data.price.FuelGrades
import com.fuelroute.data.price.FuelPriceRepository
import com.fuelroute.data.settings.AppSettings
import com.fuelroute.data.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Counts reported after a merge-import; never a list of raw rows. */
data class ImportResult(
    val success: Boolean,
    val error: String? = null,
    val vehicles: Int = 0,
    val binsMerged: Int = 0,
    val tripsAdded: Int = 0,
    val tripsSkipped: Int = 0,
    val refuelsAdded: Int = 0,
    val refuelsSkipped: Int = 0,
    val routeSearchesAdded: Int = 0,
    val routeSearchesSkipped: Int = 0,
    val learningExtras: Int = 0,
    val favoritesAdded: Int = 0,
    val favoritesSkipped: Int = 0,
    val settingsApplied: Boolean = false,
    val prices: Int = 0,
) {
    companion object {
        fun failure(message: String) = ImportResult(success = false, error = message)
    }
}

interface BackupRepository {
    /** Serializes the whole user dataset to a JSON document. */
    suspend fun export(): String

    /**
     * Parses [json] and merges it into the local database. Local rows that the file does not
     * mention are never deleted. Corrupt input fails before any write happens.
     */
    suspend fun import(json: String): ImportResult
}

@Singleton
class DefaultBackupRepository @Inject constructor(
    private val vehicleDao: VehicleDao,
    private val speedBinDao: SpeedBinDao,
    private val tripDao: TripDao,
    private val refuelDao: RefuelDao,
    private val routeSearchDao: RouteSearchDao,
    private val learningExtrasDao: LearningExtrasDao,
    private val favoriteDao: FavoriteDestinationDao,
    private val settingsRepository: SettingsRepository,
    private val fuelPriceRepository: FuelPriceRepository,
) : BackupRepository {

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    override suspend fun export(): String {
        val settings = settingsRepository.settings.first()
        val prices = FuelGrades.ALL.map { grade ->
            val price = fuelPriceRepository.current(grade)
            PriceSnapshot(
                grade = grade,
                pricePerLiter = price.pricePerLiter,
                manuallyPinned = price.manuallyPinned,
            )
        }
        val payload = BackupPayload(
            schemaVersion = BACKUP_SCHEMA_VERSION,
            exportedAtMs = System.currentTimeMillis(),
            vehicles = vehicleDao.getAll().first().map { it.toSnapshot() },
            speedBins = speedBinDao.getAll().map { it.toSnapshot() },
            trips = tripDao.getAll().map { it.toSnapshot() },
            refuels = refuelDao.getAll().map { it.toSnapshot() },
            routeSearches = routeSearchDao.getAll().map { it.toSnapshot() },
            learningExtras = learningExtrasDao.getAll().map { it.toSnapshot() },
            favorites = favoriteDao.observeAll().first().map { it.toSnapshot() },
            settings = settings.toSnapshot(),
            prices = prices,
        )
        return json.encodeToString(BackupPayload.serializer(), payload)
    }

    override suspend fun import(json: String): ImportResult {
        val payload = parse(json) ?: return ImportResult.failure("invalid or empty backup file")
        if (payload.schemaVersion > BACKUP_SCHEMA_VERSION) {
            return ImportResult.failure("backup schema ${payload.schemaVersion} is newer than supported")
        }

        upsertVehicles(payload)
        val binsMerged = mergeSpeedBins(payload)
        val (tripsAdded, tripsSkipped) = insertTrips(payload)
        val (refuelsAdded, refuelsSkipped) = insertRefuels(payload)
        val (routeSearchesAdded, routeSearchesSkipped) = insertRouteSearches(payload)
        val learningExtras = upsertLearningExtras(payload)
        val (favoritesAdded, favoritesSkipped) = insertFavorites(payload)
        val settingsApplied = applySettings(payload)
        val prices = applyPrices(payload)

        return ImportResult(
            success = true,
            vehicles = payload.vehicles.size,
            binsMerged = binsMerged,
            tripsAdded = tripsAdded,
            tripsSkipped = tripsSkipped,
            refuelsAdded = refuelsAdded,
            refuelsSkipped = refuelsSkipped,
            routeSearchesAdded = routeSearchesAdded,
            routeSearchesSkipped = routeSearchesSkipped,
            learningExtras = learningExtras,
            favoritesAdded = favoritesAdded,
            favoritesSkipped = favoritesSkipped,
            settingsApplied = settingsApplied,
            prices = prices,
        )
    }

    /** Parses and validates before any write; returns null when [json] is not a usable payload. */
    private fun parse(json: String): BackupPayload? {
        if (json.isBlank()) return null
        return runCatching { this.json.decodeFromString(BackupPayload.serializer(), json) }.getOrNull()
    }

    private suspend fun upsertVehicles(payload: BackupPayload) {
        if (payload.vehicles.isEmpty()) return
        vehicleDao.upsert(payload.vehicles.map { it.toEntity() })
    }

    /**
     * Imported bins are *added* to the existing totals with the additive DAO upsert, in one
     * transaction — no read-modify-write, so a concurrently running OBD session (which also
     * only adds deltas) can neither overwrite the import nor be overwritten by it.
     */
    private suspend fun mergeSpeedBins(payload: BackupPayload): Int {
        if (payload.speedBins.isEmpty()) return 0
        speedBinDao.addDeltas(payload.speedBins.map { it.toEntity() })
        return payload.speedBins.size
    }

    private suspend fun insertTrips(payload: BackupPayload): Pair<Int, Int> {
        val existing = tripDao.getAll().mapTo(mutableSetOf()) { it.vehicleId to it.startedAtMs }
        var added = 0
        var skipped = 0
        for (trip in payload.trips) {
            if (existing.add(trip.vehicleId to trip.startedAtMs)) {
                tripDao.insert(trip.toEntity())
                added++
            } else {
                skipped++
            }
        }
        return added to skipped
    }

    private suspend fun insertRefuels(payload: BackupPayload): Pair<Int, Int> {
        val existing = refuelDao.getAll().mapTo(mutableSetOf()) { it.vehicleId to it.timestampMs }
        var added = 0
        var skipped = 0
        for (refuel in payload.refuels) {
            if (existing.add(refuel.vehicleId to refuel.timestampMs)) {
                refuelDao.insert(refuel.toEntity())
                added++
            } else {
                skipped++
            }
        }
        return added to skipped
    }

    private suspend fun insertRouteSearches(payload: BackupPayload): Pair<Int, Int> {
        val existing = routeSearchDao.getAll()
            .mapTo(mutableSetOf()) { Triple(it.timestampMs, it.originLabel, it.destinationLabel) }
        var added = 0
        var skipped = 0
        for (search in payload.routeSearches) {
            val key = Triple(search.timestampMs, search.originLabel, search.destinationLabel)
            if (existing.add(key)) {
                routeSearchDao.insert(search.toEntity())
                added++
            } else {
                skipped++
            }
        }
        return added to skipped
    }

    private suspend fun upsertLearningExtras(payload: BackupPayload): Int {
        for (extras in payload.learningExtras) learningExtrasDao.upsert(extras.toEntity())
        return payload.learningExtras.size
    }

    private suspend fun insertFavorites(payload: BackupPayload): Pair<Int, Int> {
        val existing = favoriteDao.observeAll().first().toMutableList()
        var added = 0
        var skipped = 0
        for (favorite in payload.favorites) {
            if (existing.any { matches(it, favorite) }) {
                skipped++
                continue
            }
            favoriteDao.upsert(favorite.toEntity())
            existing.add(favorite.toEntity())
            added++
        }
        return added to skipped
    }

    private suspend fun applySettings(payload: BackupPayload): Boolean {
        val settings = payload.settings ?: return false
        settingsRepository.saveValuePerMinute(settings.valuePerMinute)
        settingsRepository.saveNavigationApp(settings.navigationApp)
        settingsRepository.saveAutoConnect(settings.autoConnect)
        settingsRepository.saveShowOverlay(settings.showOverlay)
        settingsRepository.saveKeepScreenOn(settings.keepScreenOn)
        settingsRepository.saveLastDeviceAddress(settings.lastDeviceAddress)
        settingsRepository.saveLastDeviceName(settings.lastDeviceName)
        settingsRepository.saveLastAutoStart(settings.lastAutoStartMs)
        settingsRepository.saveLastObdError(settings.lastObdError)
        settingsRepository.saveAutoConnectIntroSeen(settings.autoConnectIntroSeen)
        settingsRepository.saveRetentionDays(settings.retentionDays)
        return true
    }

    private suspend fun applyPrices(payload: BackupPayload): Int {
        for (price in payload.prices) {
            fuelPriceRepository.saveManualPrice(price.grade, price.pricePerLiter)
            fuelPriceRepository.setPinned(price.grade, price.manuallyPinned)
        }
        return payload.prices.size
    }

    /** Same rule as [FavoritesRepository]: match on `placeId` when both have one, else normalized label. */
    private fun matches(existing: FavoriteDestinationEntity, incoming: FavoriteSnapshot): Boolean =
        if (!existing.placeId.isNullOrBlank() && !incoming.placeId.isNullOrBlank()) {
            existing.placeId == incoming.placeId
        } else {
            FavoritesRepository.normalizeLabel(existing.label) ==
                FavoritesRepository.normalizeLabel(incoming.label)
        }
}

fun AppSettings.toSnapshot() = SettingsSnapshot(
    valuePerMinute = valuePerMinute,
    navigationApp = navigationApp,
    autoConnect = autoConnect,
    showOverlay = showOverlay,
    keepScreenOn = keepScreenOn,
    lastDeviceAddress = lastDeviceAddress,
    lastDeviceName = lastDeviceName,
    lastAutoStartMs = lastAutoStartMs,
    lastObdError = lastObdError,
    autoConnectIntroSeen = autoConnectIntroSeen,
    retentionDays = retentionDays,
)
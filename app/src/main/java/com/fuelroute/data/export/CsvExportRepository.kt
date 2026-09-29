package com.fuelroute.data.export

import com.fuelroute.data.db.RefuelDao
import com.fuelroute.data.db.RouteSearchDao
import com.fuelroute.data.db.TripDao
import com.fuelroute.data.db.VehicleDao
import com.fuelroute.data.history.displayActualCost
import com.fuelroute.data.history.effectiveDistanceKm
import com.fuelroute.data.history.effectiveFuelL
import com.fuelroute.domain.export.RefuelCsvFormatter
import com.fuelroute.domain.export.RefuelCsvRow
import com.fuelroute.domain.export.TripCsvFormatter
import com.fuelroute.domain.export.TripCsvRow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** Fallback vehicle name for a row whose vehicle was deleted after the trip/refuel was recorded. */
private const val UNKNOWN_VEHICLE = "?"

interface CsvExportRepository {
    /** Every closed trip across every vehicle, oldest first, as the "Export trips (CSV)" document. */
    suspend fun exportTripsCsv(): String

    /** Every refuel across every vehicle, oldest first, as the "Export refuels (CSV)" document. */
    suspend fun exportRefuelsCsv(): String
}

/**
 * Builds the two CSV exports (backlog item 39) straight from the DB, across every vehicle (not
 * just the active one) with a vehicle-name column so the two files stay meaningful once merged.
 * The pure formatting (quoting, number/date layout) lives in `domain/export`; this class only
 * resolves what to put in each column.
 */
@Singleton
class DefaultCsvExportRepository @Inject constructor(
    private val tripDao: TripDao,
    private val refuelDao: RefuelDao,
    private val vehicleDao: VehicleDao,
    private val routeSearchDao: RouteSearchDao,
) : CsvExportRepository {

    override suspend fun exportTripsCsv(): String {
        val vehicleNames = vehicleDao.getAll().first().associate { it.id to it.name }
        val searchesById = routeSearchDao.getAll().associateBy { it.id }
        val rows = tripDao.getAll()
            // An open (still-driving) trip has no meaningful end time or total yet.
            .filter { it.isOpen == 0 }
            .sortedBy { it.startedAtMs }
            .map { trip ->
                val linkedSearch = trip.routeSearchId?.let { searchesById[it.toLong()] }
                TripCsvRow(
                    startedAtMs = trip.startedAtMs,
                    endedAtMs = trip.endedAtMs,
                    distanceKm = trip.effectiveDistanceKm(),
                    fuelL = trip.effectiveFuelL(),
                    costNis = trip.displayActualCost(),
                    pricePerLiter = trip.pricePerLiterAtTrip.takeIf { it > 0.0 },
                    source = if (trip.manualCost != null) "manual" else "OBD",
                    linkedRouteSearchId = trip.routeSearchId?.toLong(),
                    predictedCostNis = linkedSearch?.let { it.selectedPredictedCost.takeIf { c -> c > 0.0 } ?: it.cheapestCost },
                    vehicleName = vehicleNames[trip.vehicleId] ?: UNKNOWN_VEHICLE,
                )
            }
        return TripCsvFormatter.format(rows)
    }

    override suspend fun exportRefuelsCsv(): String {
        val vehicleNames = vehicleDao.getAll().first().associate { it.id to it.name }
        val rows = refuelDao.getAll()
            .sortedBy { it.timestampMs }
            .map { refuel ->
                RefuelCsvRow(
                    timestampMs = refuel.timestampMs,
                    liters = refuel.liters,
                    totalPrice = refuel.totalPrice,
                    pricePerLiter = refuel.pricePerLiter,
                    grade = refuel.grade,
                    isFull = refuel.isFull,
                    vehicleName = vehicleNames[refuel.vehicleId] ?: UNKNOWN_VEHICLE,
                )
            }
        return RefuelCsvFormatter.format(rows)
    }
}

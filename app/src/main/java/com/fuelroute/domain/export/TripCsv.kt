package com.fuelroute.domain.export

/**
 * One CSV row's worth of trip data, already resolved by the caller from storage: manual entry
 * wins over the OBD measurement (see `data/history/TripReadout`), and the linked-search fields are
 * only set for a trip actually paired with a route search.
 */
data class TripCsvRow(
    val startedAtMs: Long,
    val endedAtMs: Long,
    val distanceKm: Double,
    val fuelL: Double,
    /** Actual cost in NIS: the manual entry if the user set one, else the OBD-derived cost. */
    val costNis: Double?,
    val pricePerLiter: Double?,
    /** "manual" when [costNis] came from a manual post-drive entry, else "OBD". */
    val source: String,
    val linkedRouteSearchId: Long?,
    /** The linked search's predicted cost; null when the trip has no linked search. */
    val predictedCostNis: Double?,
    val vehicleName: String,
)

/** Formats [TripCsvRow]s into the "Export trips (CSV)" document (backlog item 39). */
object TripCsvFormatter {
    val HEADER = listOf(
        "start", "end", "duration_min", "distance_km", "fuel_l", "l_per_100km",
        "cost_nis", "price_per_l", "source", "route_search_id", "predicted_cost_nis", "vehicle",
    )

    fun format(rows: List<TripCsvRow>): String = Csv.document(HEADER, rows.map { it.toCsv() })

    private fun TripCsvRow.toCsv(): List<String?> {
        val durationMin = (endedAtMs - startedAtMs) / 60_000.0
        val litersPer100Km = if (distanceKm > 0.0) fuelL / distanceKm * 100.0 else null
        return listOf(
            CsvDates.isoLocal(startedAtMs),
            CsvDates.isoLocal(endedAtMs),
            CsvNumbers.fixed(durationMin, 1),
            CsvNumbers.fixed(distanceKm, 2),
            CsvNumbers.fixed(fuelL, 3),
            CsvNumbers.fixedOrBlank(litersPer100Km, 1),
            CsvNumbers.fixedOrBlank(costNis, 2),
            CsvNumbers.fixedOrBlank(pricePerLiter, 2),
            source,
            CsvNumbers.orBlank(linkedRouteSearchId),
            CsvNumbers.fixedOrBlank(predictedCostNis, 2),
            vehicleName,
        )
    }
}

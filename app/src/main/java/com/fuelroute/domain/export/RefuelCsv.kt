package com.fuelroute.domain.export

/** One CSV row's worth of refuel data. */
data class RefuelCsvRow(
    val timestampMs: Long,
    val liters: Double,
    val totalPrice: Double,
    val pricePerLiter: Double,
    /** Raw grade code, e.g. "95", "98", "diesel" (see `data/price/FuelGrades`). */
    val grade: String,
    val isFull: Boolean,
    val vehicleName: String,
)

/** Formats [RefuelCsvRow]s into the "Export refuels (CSV)" document (backlog item 39). */
object RefuelCsvFormatter {
    val HEADER = listOf("date", "liters", "total_price_nis", "price_per_l", "grade", "full_tank", "vehicle")

    fun format(rows: List<RefuelCsvRow>): String = Csv.document(HEADER, rows.map { it.toCsv() })

    private fun RefuelCsvRow.toCsv(): List<String?> = listOf(
        CsvDates.isoLocal(timestampMs),
        CsvNumbers.fixed(liters, 3),
        CsvNumbers.fixed(totalPrice, 2),
        CsvNumbers.fixed(pricePerLiter, 2),
        grade,
        if (isFull) "1" else "0",
        vehicleName,
    )
}

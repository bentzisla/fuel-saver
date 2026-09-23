package com.fuelroute.domain.learning

/**
 * Normalizes the user-entered engine displacement to litres.
 *
 * Root cause of the "thousands of L/100 km" report: a user typed `1800` (cc) into the litres
 * field, the value was stored verbatim and the speed-density fallback multiplied the airflow by
 * 1800 L instead of 1.8 L — a 1000x fuel-rate error whenever MAF was missing.
 *
 * Rules:
 *  - a value above [CC_THRESHOLD] cannot be litres for a passenger car, so it is treated as
 *    cubic centimetres and divided by 1000 (`1800` -> `1.8`, `1598` -> `1.598`);
 *  - the result must fall inside [MIN_LITERS]..[MAX_LITERS], otherwise it is unknown (`null`),
 *    which disables the speed-density estimate instead of feeding it a wrong number.
 */
object EngineDisplacement {

    const val CC_THRESHOLD = 20.0
    const val MIN_LITERS = 0.6
    const val MAX_LITERS = 8.0

    fun normalizeLiters(value: Double?): Double? {
        val v = value?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        val liters = if (v > CC_THRESHOLD) v / 1000.0 else v
        return liters.takeIf { it in MIN_LITERS..MAX_LITERS }
    }

    /** Parses free text (`"1.8"`, `"1,8"`, `"1800"`) and normalizes it; blank/invalid -> null. */
    fun parseLiters(text: String?): Double? =
        normalizeLiters(text?.trim()?.replace(',', '.')?.toDoubleOrNull())

    /** True when a stored raw value would be changed by [normalizeLiters] (needs a data repair). */
    fun needsRepair(stored: Double?): Boolean = stored != null && normalizeLiters(stored) != stored
}

package com.fuelroute.domain.fuel

/**
 * How trustworthy a calibration fit is, based on its mean absolute percentage error (MAPE)
 * and how many linked drives it was fitted over. The UI maps each band to a label/colour;
 * the thresholds live here so the screen and its tests share one definition (card 33).
 */
enum class CalibrationAccuracy { NONE, LOW, MEDIUM, HIGH }

object CalibrationAccuracyBands {

    /** At or below this MAPE the fit is considered [CalibrationAccuracy.HIGH]. */
    const val HIGH_MAX_MAPE = 5.0

    /** At or below this MAPE (but above [HIGH_MAX_MAPE]) the fit is [CalibrationAccuracy.MEDIUM]. */
    const val MEDIUM_MAX_MAPE = 15.0

    /** Fewer linked drives than this cannot support a trustworthy fit. */
    const val MIN_PAIRS = 3

    /**
     * Band for a fit over [pairCount] linked drives with the given [mapePercent].
     * [CalibrationAccuracy.NONE] when there is no usable score (null/non-finite/negative)
     * or too few drives to trust.
     */
    fun from(mapePercent: Double?, pairCount: Int): CalibrationAccuracy {
        if (mapePercent == null || !mapePercent.isFinite() || mapePercent < 0.0) {
            return CalibrationAccuracy.NONE
        }
        if (pairCount < MIN_PAIRS) return CalibrationAccuracy.NONE
        return when {
            mapePercent <= HIGH_MAX_MAPE -> CalibrationAccuracy.HIGH
            mapePercent <= MEDIUM_MAX_MAPE -> CalibrationAccuracy.MEDIUM
            else -> CalibrationAccuracy.LOW
        }
    }
}
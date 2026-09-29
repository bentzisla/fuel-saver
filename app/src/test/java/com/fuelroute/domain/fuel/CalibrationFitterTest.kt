package com.fuelroute.domain.fuel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationFitterTest {

    @Test
    fun `fit recovers an exact constant multiplier`() {
        val pairs = listOf(
            10.0 to 11.0,
            4.0 to 4.4,
            2.5 to 2.75,
        )

        assertEquals(1.1, CalibrationFitter.fitCorrection(pairs)!!, 1e-9)
    }

    @Test
    fun `fit clamps a consistently-low-but-plausible ratio instead of trusting it fully`() {
        // Ratio 0.6 is inside the per-pair outlier band (not a mis-link), but a real car's
        // accuracy should never be trusted past MIN_CORRECTION without more data.
        val pairs = listOf(10.0 to 6.0, 20.0 to 12.0, 5.0 to 3.0)

        val factor = CalibrationFitter.fitCorrection(pairs)!!

        assertEquals(CalibrationFitter.MIN_CORRECTION, factor, 1e-9)
    }

    @Test
    fun `fit clamps a consistently-high-but-plausible ratio instead of trusting it fully`() {
        val pairs = listOf(10.0 to 18.0, 20.0 to 36.0, 5.0 to 9.0)

        val factor = CalibrationFitter.fitCorrection(pairs)!!

        assertEquals(CalibrationFitter.MAX_CORRECTION, factor, 1e-9)
    }

    @Test
    fun `a single mis-linked pair is dropped as an outlier, not fit`() {
        // Regression: a demo/simulated ride, or a short real trip linked to a long searched
        // route, can carry an actual liters far below the predicted - e.g. ratio ~0.02 - and
        // must never single-handedly drag the correction down with it.
        val pairs = listOf(
            10.0 to 11.0,
            20.0 to 22.0,
            5.0 to 5.5,
            34.0 to 0.7, // ratio ~0.02: the mis-linked/demo outlier
        )

        val factor = CalibrationFitter.fitCorrection(pairs)!!

        assertEquals(1.1, factor, 1e-9)
    }

    @Test
    fun `fewer than 3 plausible pairs yields no fit`() {
        assertNull(CalibrationFitter.fitCorrection(listOf(10.0 to 11.0, 20.0 to 22.0)))
        // Two plausible + one outlier still leaves only 2 plausible pairs.
        assertNull(CalibrationFitter.fitCorrection(listOf(10.0 to 11.0, 20.0 to 22.0, 34.0 to 0.7)))
    }

    @Test
    fun `fit returns null for empty input`() {
        assertNull(CalibrationFitter.fitCorrection(emptyList()))
    }

    @Test
    fun `fit returns null when the denominator is zero`() {
        val pairs = listOf(0.0 to 5.0, 0.0 to 3.0, -1.0 to 2.0)

        assertNull(CalibrationFitter.fitCorrection(pairs))
    }

    @Test
    fun `suggested mape improves with the fitted factor`() {
        val pairs = listOf(
            10.0 to 11.0,
            20.0 to 22.0,
            5.0 to 5.5,
        )
        val factor = CalibrationFitter.fitCorrection(pairs)!!

        val before = CalibrationFitter.suggestedMape(pairs, 1.0)
        val after = CalibrationFitter.suggestedMape(pairs, factor)

        assertTrue("after ($after) should be better than before ($before)", after < before)
        assertEquals(0.0, after, 1e-9)
    }

    @Test
    fun `suggested mape is zero when there is nothing usable`() {
        assertEquals(0.0, CalibrationFitter.suggestedMape(emptyList(), 1.0), 1e-9)
        assertEquals(0.0, CalibrationFitter.suggestedMape(listOf(0.0 to 1.0), 1.0), 1e-9)
    }

    @Test
    fun `fit does not double count the active correction`() {
        // True uncorrected model output and the actual fuel it should have predicted.
        val uncorrected = listOf(10.0 to 12.0, 20.0 to 24.0, 5.0 to 6.0)
        // Persisted predictions already include the active correction 0.8.
        val persisted = uncorrected.map { (predicted, actual) -> (predicted * 0.8) to actual }

        // Fitting the raw persisted rows folds 0.8 in twice and overshoots.
        assertEquals(1.5, CalibrationFitter.fitCorrection(persisted)!!, 1e-9)

        // Undoing the active correction recovers the true absolute factor 1.2.
        val refit = CalibrationFitter.fitCorrection(CalibrationFitter.uncorrectedPairs(persisted, 0.8))
        assertEquals(1.2, refit!!, 1e-9)
    }

    @Test
    fun `uncorrected pairs are unchanged for the identity correction`() {
        val pairs = listOf(10.0 to 12.0, 20.0 to 24.0)

        assertEquals(pairs, CalibrationFitter.uncorrectedPairs(pairs, 1.0))
        assertEquals(pairs, CalibrationFitter.uncorrectedPairs(pairs, Double.NaN))
        assertEquals(pairs, CalibrationFitter.uncorrectedPairs(pairs, 0.0))
    }

    private fun drive(predicted: Double, actual: Double, correction: Double?, km: Double = 50.0, drivenKm: Double = 50.0) =
        LinkedDrive(predicted, actual, correction, predictedDistanceKm = km, actualDistanceKm = drivenKm)

    @Test
    fun `each prediction is un-corrected with the correction active at its search`() {
        // True model output 10/20/5 L; the car burns 1.2x. Two searches were priced with 0.8,
        // one with 1.1 (a later override). Undoing today's 1.1 from all three would bias the fit.
        val drives = listOf(
            drive(predicted = 10.0 * 0.8, actual = 12.0, correction = 0.8),
            drive(predicted = 20.0 * 0.8, actual = 24.0, correction = 0.8),
            drive(predicted = 5.0 * 1.1, actual = 6.0, correction = 1.1),
        )
        val pairs = CalibrationFitter.uncorrectedPairsOf(drives, currentCorrection = 1.1)
        listOf(10.0, 20.0, 5.0).zip(pairs).forEach { (expected, pair) -> assertEquals(expected, pair.first, 1e-9) }
        assertEquals(1.2, CalibrationFitter.fitCorrection(pairs)!!, 1e-9)
    }

    @Test
    fun `rows from before the correction was stored fall back to the current one`() {
        val pairs = CalibrationFitter.uncorrectedPairsOf(listOf(drive(8.0, 12.0, correction = null)), currentCorrection = 0.8)
        assertEquals(10.0, pairs.single().first, 1e-9)
        val none = CalibrationFitter.uncorrectedPairsOf(listOf(drive(8.0, 12.0, correction = null)), currentCorrection = Double.NaN)
        assertEquals(8.0, none.single().first, 1e-9)
    }

    @Test
    fun `drives whose distance does not match the searched route are dropped`() {
        val drives = listOf(
            drive(10.0, 11.0, 1.0, km = 50.0, drivenKm = 50.0),
            drive(10.0, 11.0, 1.0, km = 50.0, drivenKm = 43.0), // 0.86: kept
            drive(10.0, 11.0, 1.0, km = 50.0, drivenKm = 57.0), // 1.14: kept
            drive(10.0, 4.0, 1.0, km = 50.0, drivenKm = 20.0), // stopped short
            drive(10.0, 16.0, 1.0, km = 50.0, drivenKm = 70.0), // long detour
            LinkedDrive(10.0, 11.0, 1.0, predictedDistanceKm = null, actualDistanceKm = 50.0),
            LinkedDrive(10.0, 11.0, 1.0, predictedDistanceKm = 50.0, actualDistanceKm = null),
        )
        assertEquals(3, CalibrationFitter.uncorrectedPairsOf(drives, 1.0).size)
    }
}
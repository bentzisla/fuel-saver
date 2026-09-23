package com.fuelroute.ui.vehicle

/** Why a numeric vehicle field is rejected by the form (presentation-only validation). */
enum class FieldIssue {
    /** Not a number. */
    NotANumber,

    /** Outside the plausible range for this field. */
    OutOfRange,

    /** Engine displacement typed in cc (e.g. 1800) instead of litres (1.8). */
    LooksLikeCc,
}

/**
 * Plausibility checks for the vehicle form, so a unit mix-up is caught while typing. Pure Kotlin
 * (no Android) and unit-tested. Blank optional fields are valid. The ViewModel/data layer may add
 * its own normalisation (e.g. cc → L); this only decides what the form highlights.
 */
object VehicleInputRules {

    /** Engine displacement in litres: small kei cars ~0.6 L up to big V8s ~8 L. */
    val DISPLACEMENT_RANGE_L = 0.6..8.0

    /** Rated combined consumption in L/100km. */
    val RATED_COMBINED_RANGE = 2.0..30.0

    /** Fuel-tank capacity in litres. */
    val TANK_RANGE_L = 10.0..200.0

    /** Anything at or above this was almost certainly typed in cc. */
    private const val CC_THRESHOLD = 100.0

    fun displacementIssue(text: String): FieldIssue? {
        if (text.isBlank()) return null
        val value = parse(text) ?: return FieldIssue.NotANumber
        return when {
            value >= CC_THRESHOLD -> FieldIssue.LooksLikeCc
            value !in DISPLACEMENT_RANGE_L -> FieldIssue.OutOfRange
            else -> null
        }
    }

    /** Blank falls back to the ViewModel's default rating, so only typed values are checked. */
    fun ratedCombinedIssue(text: String): FieldIssue? {
        if (text.isBlank()) return null
        val value = parse(text) ?: return FieldIssue.NotANumber
        return if (value in RATED_COMBINED_RANGE) null else FieldIssue.OutOfRange
    }

    fun tankIssue(text: String): FieldIssue? {
        if (text.isBlank()) return null
        val value = parse(text) ?: return FieldIssue.NotANumber
        return if (value in TANK_RANGE_L) null else FieldIssue.OutOfRange
    }

    /** "1800" → 1.8, used to suggest the litre value when cc was typed. */
    fun ccToLitres(text: String): Double? = parse(text)?.let { it / 1000.0 }

    private fun parse(text: String): Double? =
        // Same parsing as the ViewModel's save(): the form never accepts what it would drop.
        text.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
}

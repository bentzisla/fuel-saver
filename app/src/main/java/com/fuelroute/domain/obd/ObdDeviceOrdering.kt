package com.fuelroute.domain.obd

/** Display model for one paired Bluetooth adapter row in the Stats "connect" card. */
data class ObdDevice(
    val address: String,
    val name: String,
    val isFavorite: Boolean = false,
    val isLastUsed: Boolean = false,
)

/**
 * Pure ordering/merge rules for the OBD device picker.
 *
 * Kept in `domain/` (no Android imports) so the ranking is unit-testable on the JVM.
 *
 * Ranking: the most recently connected adapter first, then favorites, then everyone else
 * alphabetically by display name. Ties break on the Bluetooth address so the list is stable.
 */
object ObdDeviceOrdering {

    /**
     * Merges the bonded adapters with the set of favorite addresses and the last-used address
     * into the sorted display list. Address matching is case-insensitive (Bluetooth addresses
     * are hex pairs; casing is not meaningful).
     */
    fun merge(
        bonded: List<BondedObdDevice>,
        favoriteAddresses: Set<String>,
        lastDeviceAddress: String?,
    ): List<ObdDevice> {
        val favorites = favoriteAddresses.mapTo(HashSet()) { it.lowercase() }
        val last = lastDeviceAddress?.takeIf { it.isNotBlank() }?.lowercase()
        return sort(
            bonded.map { device ->
                ObdDevice(
                    address = device.address,
                    name = device.name?.takeIf { it.isNotBlank() } ?: device.address,
                    isFavorite = device.address.lowercase() in favorites,
                    isLastUsed = last != null && device.address.lowercase() == last,
                )
            },
        )
    }

    /** Sorts already-enriched rows: last used → favorite → alphabetical by name, then address. */
    fun sort(devices: List<ObdDevice>): List<ObdDevice> =
        devices.sortedWith(
            compareByDescending<ObdDevice> { it.isLastUsed }
                .thenByDescending { it.isFavorite }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                .thenBy { it.address },
        )
}
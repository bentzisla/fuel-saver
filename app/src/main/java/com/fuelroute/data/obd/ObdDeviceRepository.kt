package com.fuelroute.data.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import com.fuelroute.data.db.FavoriteObdDeviceDao
import com.fuelroute.data.db.FavoriteObdDeviceEntity
import com.fuelroute.data.settings.SettingsRepository
import com.fuelroute.domain.obd.BondedObdDevice
import com.fuelroute.domain.obd.ObdDevice
import com.fuelroute.domain.obd.ObdDeviceOrdering
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/**
 * Merges the bonded Bluetooth adapters ([BluetoothDevicesRepository]) with the user's favorite
 * dongles (Room `favorite_obd_device`) and the last-used address
 * ([SettingsRepository.lastDeviceAddress]) into the sorted, UI-ready [ObdDevice] list used by the
 * Stats connect card.
 *
 * Ordering itself lives in the pure [ObdDeviceOrdering] helper so it is unit-tested on the JVM.
 */
@Singleton
class ObdDeviceRepository @Inject constructor(
    private val bluetoothRepository: BluetoothDevicesRepository,
    private val favoritesDao: FavoriteObdDeviceDao,
    private val settingsRepository: SettingsRepository,
) {

    /** Latest bonded snapshot, refreshed on demand via [refresh]. */
    private val bonded = MutableStateFlow<List<BluetoothDevice>>(emptyList())

    /** Known favorite adapters, ordered by the DAO. */
    val favorites: Flow<List<FavoriteObdDeviceEntity>> = favoritesDao.observeAll()

    /** Bonded adapters enriched with favorite/last-used flags and sorted for display. */
    val devices: Flow<List<ObdDevice>> =
        combine(bonded, favoritesDao.observeAll(), settingsRepository.settings) { bonded, favorites, settings ->
            ObdDeviceOrdering.merge(
                bonded = bonded.map { it.toBonded() },
                favoriteAddresses = favorites.mapTo(HashSet()) { it.address },
                lastDeviceAddress = settings.lastDeviceAddress,
            )
        }

    /** Re-reads the bonded device list (permission-gated in [BluetoothDevicesRepository]). */
    suspend fun refresh() {
        bonded.value = bluetoothRepository.bondedDevices()
    }

    /**
     * Stars an adapter, or clears the star when it is already a favorite. New favorites get the
     * next `sortOrder` so a future manual reordering stays deterministic.
     */
    suspend fun toggleFavorite(address: String, name: String) {
        if (isFavorite(address)) {
            favoritesDao.deleteByAddress(address)
            return
        }
        val existing = favoritesDao.observeAll().first()
        val nextOrder = (existing.maxOfOrNull { it.sortOrder } ?: -1) + 1
        favoritesDao.upsert(
            FavoriteObdDeviceEntity(
                address = address,
                name = name.trim().ifBlank { address },
                sortOrder = nextOrder,
                createdAtMs = System.currentTimeMillis(),
            ),
        )
    }

    /** True when the adapter is already starred (address comparison is case-insensitive). */
    suspend fun isFavorite(address: String): Boolean =
        favoritesDao.observeAll().first().any { it.address.equals(address, ignoreCase = true) }

    @SuppressLint("MissingPermission")
    private fun BluetoothDevice.toBonded(): BondedObdDevice =
        BondedObdDevice(address = address, name = runCatching { name }.getOrNull())
}
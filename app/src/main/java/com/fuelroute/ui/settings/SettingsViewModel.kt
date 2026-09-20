package com.fuelroute.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fuelroute.data.backup.BackupRepository
import com.fuelroute.data.backup.ImportResult
import com.fuelroute.data.price.FuelGrades
import com.fuelroute.data.price.FuelPriceRepository
import com.fuelroute.data.settings.NAV_GOOGLE
import com.fuelroute.data.settings.SettingsRepository
import com.fuelroute.data.vehicle.VehicleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val isLoaded: Boolean = false,
    val fuelPrice: String = "",
    val pricePinned: Boolean = false,
    val priceGrade: String = FuelGrades.GASOLINE_95,
    val valuePerMinute: String = "",
    val navigationApp: String = NAV_GOOGLE,
    val autoConnect: Boolean = true,
    val showOverlay: Boolean = false,
    val keepScreenOn: Boolean = false,
    val lastDeviceAddress: String? = null,
    val lastDeviceName: String? = null,
    val lastAutoStartMs: Long? = null,
    val lastObdError: String? = null,
    val autoConnectIntroSeen: Boolean = false,
    val retentionDays: Int = com.fuelroute.domain.retention.RetentionPolicy.DEFAULT_RETENTION_DAYS,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val fuelPriceRepository: FuelPriceRepository,
    private val vehicleRepository: VehicleRepository,
    private val backupRepository: BackupRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val vehicle = vehicleRepository.active()
            val price = fuelPriceRepository.current(vehicle.grade)
            val settings = settingsRepository.settings.first()
            _uiState.value = SettingsUiState(
                isLoaded = true,
                fuelPrice = price.pricePerLiter.toString(),
                pricePinned = price.manuallyPinned,
                priceGrade = vehicle.grade,
                valuePerMinute = settings.valuePerMinute.toString(),
                navigationApp = settings.navigationApp,
                autoConnect = settings.autoConnect,
                showOverlay = settings.showOverlay,
                keepScreenOn = settings.keepScreenOn,
                lastDeviceAddress = settings.lastDeviceAddress,
                lastDeviceName = settings.lastDeviceName,
                lastAutoStartMs = settings.lastAutoStartMs,
                lastObdError = settings.lastObdError,
                autoConnectIntroSeen = settings.autoConnectIntroSeen,
                retentionDays = settings.retentionDays,
            )
        }
    }

    fun onFuelPriceChange(value: String) {
        _uiState.update { it.copy(fuelPrice = value) }
        val grade = _uiState.value.priceGrade
        value.toDoubleOrNull()?.let { viewModelScope.launch { fuelPriceRepository.saveManualPrice(grade, it) } }
    }

    fun onPricePinnedChange(pinned: Boolean) {
        _uiState.update { it.copy(pricePinned = pinned) }
        val grade = _uiState.value.priceGrade
        viewModelScope.launch { fuelPriceRepository.setPinned(grade, pinned) }
    }

    fun onValuePerMinuteChange(value: String) {
        _uiState.update { it.copy(valuePerMinute = value) }
        value.toDoubleOrNull()?.let { viewModelScope.launch { settingsRepository.saveValuePerMinute(it) } }
    }

    fun onNavigationAppChange(value: String) {
        _uiState.update { it.copy(navigationApp = value) }
        viewModelScope.launch { settingsRepository.saveNavigationApp(value) }
    }

    fun onAutoConnectChange(value: Boolean) {
        _uiState.update { it.copy(autoConnect = value) }
        viewModelScope.launch { settingsRepository.saveAutoConnect(value) }
    }

    fun onShowOverlayChange(value: Boolean) {
        _uiState.update { it.copy(showOverlay = value) }
        viewModelScope.launch { settingsRepository.saveShowOverlay(value) }
    }

    fun onKeepScreenOnChange(value: Boolean) {
        _uiState.update { it.copy(keepScreenOn = value) }
        viewModelScope.launch { settingsRepository.saveKeepScreenOn(value) }
    }

    fun onAutoConnectIntroSeen() {
        _uiState.update { it.copy(autoConnectIntroSeen = true) }
        viewModelScope.launch { settingsRepository.saveAutoConnectIntroSeen(true) }
    }

    fun onRetentionDaysChange(days: Int) {
        _uiState.update { it.copy(retentionDays = days) }
        viewModelScope.launch { settingsRepository.saveRetentionDays(days) }
    }

    /** Serializes the learned dataset; the caller writes the string to the chosen document. */
    suspend fun exportBackup(): String = backupRepository.export()

    /** Merges a previously exported document back into local storage. */
    suspend fun importBackup(json: String): ImportResult = backupRepository.import(json)
}
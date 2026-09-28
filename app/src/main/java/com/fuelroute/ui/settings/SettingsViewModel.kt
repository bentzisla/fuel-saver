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
import com.fuelroute.domain.obd.ObdProbePolicy
import com.fuelroute.service.ObdProbeScheduler
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
    /** True while [valuePerMinute] is not a finite number in [SettingsViewModel.VALUE_PER_MINUTE_RANGE]. */
    val valuePerMinuteInvalid: Boolean = false,
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
    val obdProbeEnabled: Boolean = true,
    val obdProbeIntervalMin: Int = ObdProbePolicy.DEFAULT_INTERVAL_MIN,
    val lastProbeAtMs: Long? = null,
    val lastProbeResult: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val fuelPriceRepository: FuelPriceRepository,
    private val vehicleRepository: VehicleRepository,
    private val backupRepository: BackupRepository,
    private val obdProbeScheduler: ObdProbeScheduler,
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
                obdProbeEnabled = settings.obdProbeEnabled,
                obdProbeIntervalMin = settings.obdProbeIntervalMin,
                lastProbeAtMs = settings.lastProbeAtMs,
                lastProbeResult = settings.lastProbeResult,
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
        val parsed = parseValuePerMinute(value)
        _uiState.update { it.copy(valuePerMinute = value, valuePerMinuteInvalid = parsed == null) }
        // Autosave only valid input; an invalid value stays on screen with an inline error.
        parsed?.let { viewModelScope.launch { settingsRepository.saveValuePerMinute(it) } }
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

    fun onObdProbeEnabledChange(value: Boolean) {
        _uiState.update { it.copy(obdProbeEnabled = value) }
        viewModelScope.launch {
            settingsRepository.saveObdProbeEnabled(value)
            obdProbeScheduler.reschedule()
        }
    }

    fun onObdProbeIntervalChange(minutes: Int) {
        _uiState.update { it.copy(obdProbeIntervalMin = minutes) }
        viewModelScope.launch {
            settingsRepository.saveObdProbeIntervalMin(minutes)
            obdProbeScheduler.reschedule()
        }
    }

    fun onRetentionDaysChange(days: Int) {
        _uiState.update { it.copy(retentionDays = days) }
        viewModelScope.launch { settingsRepository.saveRetentionDays(days) }
    }

    /** Serializes the learned dataset; the caller writes the string to the chosen document. */
    suspend fun exportBackup(): String = backupRepository.export()

    /** Merges a previously exported document back into local storage. */
    suspend fun importBackup(json: String): ImportResult = backupRepository.import(json)

    companion object {
        /** Sensible bounds for "what a minute is worth", in shekels. */
        val VALUE_PER_MINUTE_RANGE = 0.0..10.0

        /** Parses [text]; null unless it is a finite number within [VALUE_PER_MINUTE_RANGE]. */
        fun parseValuePerMinute(text: String): Double? = text.trim().toDoubleOrNull()
            ?.takeIf { it.isFinite() && it in VALUE_PER_MINUTE_RANGE }
    }
}

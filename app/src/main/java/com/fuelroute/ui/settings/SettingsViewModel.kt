package com.fuelroute.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fuelroute.data.settings.NAV_GOOGLE
import com.fuelroute.data.settings.SettingsRepository
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
    val valuePerMinute: String = "",
    val navigationApp: String = NAV_GOOGLE,
    val autoConnect: Boolean = true,
    val showOverlay: Boolean = false,
    val keepScreenOn: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val settings = settingsRepository.settings.first()
            _uiState.value = SettingsUiState(
                isLoaded = true,
                fuelPrice = settings.fuelPricePerLiter.toString(),
                valuePerMinute = settings.valuePerMinute.toString(),
                navigationApp = settings.navigationApp,
                autoConnect = settings.autoConnect,
                showOverlay = settings.showOverlay,
                keepScreenOn = settings.keepScreenOn,
            )
        }
    }

    fun onFuelPriceChange(value: String) {
        _uiState.update { it.copy(fuelPrice = value) }
        value.toDoubleOrNull()?.let { viewModelScope.launch { settingsRepository.saveFuelPrice(it) } }
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
}
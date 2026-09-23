package com.fuelroute.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fuelroute.BuildConfig
import com.fuelroute.Changelog
import com.fuelroute.R
import com.fuelroute.data.price.FuelGrades
import com.fuelroute.data.settings.AppSettings
import com.fuelroute.data.settings.NAV_GOOGLE
import com.fuelroute.data.settings.NAV_WAZE
import com.fuelroute.data.settings.SettingsRepository
import com.fuelroute.service.BatteryOptimization
import com.fuelroute.ui.components.Dimens
import com.fuelroute.ui.components.FuelTopBar
import com.fuelroute.ui.components.ListRow
import com.fuelroute.ui.components.PrimaryButton
import com.fuelroute.ui.components.SectionCard
import com.fuelroute.ui.components.SectionTitle
import com.fuelroute.ui.components.SwitchRow
import com.fuelroute.ui.components.formatDateTime
import com.fuelroute.ui.theme.FuelTheme
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * Settings, grouped into five short lists — prices & navigation, OBD logging, data, advanced,
 * about. Each row does one thing; long explanations (Android Auto, changelog) open in a sheet or
 * dialog instead of sitting inline.
 */
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    onOpenCalibration: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var ignoringBattery by remember { mutableStateOf(BatteryOptimization.isIgnoring(context)) }
    var showAndroidAuto by rememberSaveable { mutableStateOf(false) }
    var showChangelog by rememberSaveable { mutableStateOf(false) }
    var showRetention by rememberSaveable { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        ignoringBattery = BatteryOptimization.isIgnoring(context)
        onPauseOrDispose { }
    }

    Column(modifier = modifier.fillMaxSize()) {
        FuelTopBar(title = stringResource(R.string.settings_title))
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(start = Dimens.l, end = Dimens.l, bottom = Dimens.xl),
            verticalArrangement = Arrangement.spacedBy(Dimens.s),
        ) {
            SettingsGroup(title = stringResource(R.string.settings_section_fuel)) {
                PricesAndNavigation(state = state, viewModel = viewModel)
            }

            SettingsGroup(title = stringResource(R.string.settings_section_obd)) {
                ObdLogging(state = state, viewModel = viewModel, ignoringBattery = ignoringBattery)
            }

            SettingsGroup(title = stringResource(R.string.settings_section_data)) {
                BackupRows(viewModel = viewModel)
                Divider()
                ListRow(
                    title = stringResource(R.string.settings_retention_label),
                    subtitle = stringResource(R.string.settings_retention_days, state.retentionDays),
                    onClick = { showRetention = true },
                    trailing = { Chevron() },
                )
            }

            SettingsGroup(title = stringResource(R.string.settings_section_advanced)) {
                ListRow(
                    title = stringResource(R.string.settings_calibration_title),
                    subtitle = stringResource(R.string.settings_calibration_hint),
                    leading = { Icon(painterResource(R.drawable.ic_tune), contentDescription = null) },
                    onClick = onOpenCalibration,
                    trailing = { Chevron() },
                )
                Divider()
                ListRow(
                    title = stringResource(R.string.settings_android_auto_title),
                    subtitle = stringResource(R.string.settings_android_auto_row_hint),
                    leading = { Icon(painterResource(R.drawable.ic_car), contentDescription = null) },
                    onClick = { showAndroidAuto = true },
                    trailing = { Chevron() },
                )
            }

            SettingsGroup(title = stringResource(R.string.settings_section_about)) {
                ListRow(
                    title = stringResource(R.string.settings_version_label, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                    subtitle = stringResource(R.string.settings_autosaved),
                )
                Divider()
                ListRow(
                    title = stringResource(R.string.settings_changelog_title),
                    onClick = { showChangelog = true },
                    trailing = { Chevron() },
                )
            }
        }
    }

    if (showAndroidAuto) AndroidAutoSheet(onDismiss = { showAndroidAuto = false })
    if (showChangelog) ChangelogDialog(onDismiss = { showChangelog = false })
    if (showRetention) {
        RetentionDialog(
            selected = state.retentionDays,
            onSelect = { days ->
                viewModel.onRetentionDaysChange(days)
                showRetention = false
            },
            onDismiss = { showRetention = false },
        )
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    SectionTitle(title, modifier = Modifier.padding(top = Dimens.s))
    SectionCard(contentPadding = Dimens.l) {
        Column(content = content)
    }
}

@Composable
private fun Divider() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

@Composable
private fun Chevron() {
    Icon(
        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PricesAndNavigation(state: SettingsUiState, viewModel: SettingsViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
        OutlinedTextField(
            value = state.fuelPrice,
            onValueChange = viewModel::onFuelPriceChange,
            label = { Text(stringResource(R.string.settings_fuel_price_label)) },
            suffix = { Text(stringResource(R.string.settings_unit_price_per_liter)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        SwitchRow(
            title = stringResource(R.string.settings_price_pin),
            subtitle = stringResource(R.string.settings_price_grade, stringResource(gradeLabelRes(state.priceGrade))),
            checked = state.pricePinned,
            onCheckedChange = viewModel::onPricePinnedChange,
        )
        OutlinedTextField(
            value = state.valuePerMinute,
            onValueChange = viewModel::onValuePerMinuteChange,
            label = { Text(stringResource(R.string.settings_value_per_minute_label)) },
            suffix = { Text(stringResource(R.string.settings_unit_per_minute)) },
            supportingText = { Text(stringResource(R.string.settings_value_per_minute_help)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(text = stringResource(R.string.settings_nav_app_label), style = MaterialTheme.typography.titleSmall)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            listOf(NAV_GOOGLE to R.string.settings_nav_google, NAV_WAZE to R.string.settings_nav_waze)
                .forEachIndexed { index, (app, label) ->
                    SegmentedButton(
                        selected = state.navigationApp == app,
                        onClick = { viewModel.onNavigationAppChange(app) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
                    ) {
                        Text(stringResource(label))
                    }
                }
        }
        if (state.navigationApp == NAV_WAZE) {
            Text(
                text = stringResource(R.string.nav_waze_destination_only),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

/**
 * Zero-touch logging and the switches around it. The first time auto-connect is on, a short intro
 * asks for the permissions/battery exemption it needs.
 */
@Composable
private fun ObdLogging(state: SettingsUiState, viewModel: SettingsViewModel, ignoringBattery: Boolean) {
    val context = LocalContext.current
    val permissions = remember {
        buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val missing = permissions.filterNot {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.onAutoConnectIntroSeen()
        if (!BatteryOptimization.isIgnoring(context)) BatteryOptimization.request(context)
    }

    if (state.autoConnect && !state.autoConnectIntroSeen) {
        SectionCard(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
            Text(text = stringResource(R.string.settings_auto_logging_intro), style = MaterialTheme.typography.titleMedium)
            Text(text = stringResource(R.string.settings_auto_logging_intro_detail), style = MaterialTheme.typography.bodyMedium)
            PrimaryButton(
                text = stringResource(R.string.settings_auto_logging_enable),
                onClick = {
                    viewModel.onAutoConnectIntroSeen()
                    if (missing.isNotEmpty()) {
                        launcher.launch(missing.toTypedArray())
                    } else if (!BatteryOptimization.isIgnoring(context)) {
                        BatteryOptimization.request(context)
                    }
                },
            )
        }
    }

    val device = state.lastDeviceName?.takeIf { it.isNotBlank() }
        ?: state.lastDeviceAddress
        ?: stringResource(R.string.settings_auto_logging_no_device)
    SwitchRow(
        title = stringResource(R.string.settings_auto_connect),
        subtitle = stringResource(R.string.settings_auto_logging_device, device) + "\n" +
            stringResource(
                R.string.settings_auto_logging_last_start,
                state.lastAutoStartMs?.let { formatDateTime(it) } ?: stringResource(R.string.settings_auto_logging_never),
            ),
        checked = state.autoConnect,
        onCheckedChange = viewModel::onAutoConnectChange,
    )
    state.lastObdError?.let {
        Text(
            text = stringResource(R.string.settings_auto_logging_last_error, it),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (state.autoConnect && missing.isNotEmpty()) {
        Text(
            text = stringResource(R.string.settings_auto_logging_permission_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (!ignoringBattery) {
        Divider()
        ListRow(
            title = stringResource(R.string.settings_battery_optimization_button),
            subtitle = stringResource(R.string.settings_battery_optimization_rationale),
            leading = { Icon(Icons.Filled.Warning, contentDescription = null, tint = FuelTheme.colors.caution) },
            onClick = { BatteryOptimization.request(context) },
        )
    }
    Divider()
    SwitchRow(
        title = stringResource(R.string.settings_keep_screen_on),
        subtitle = stringResource(R.string.settings_keep_screen_on_hint),
        checked = state.keepScreenOn,
        onCheckedChange = viewModel::onKeepScreenOnChange,
    )
    Divider()
    SwitchRow(
        title = stringResource(R.string.settings_show_overlay),
        subtitle = stringResource(R.string.settings_show_overlay_hint),
        checked = state.showOverlay,
        onCheckedChange = { value ->
            viewModel.onShowOverlayChange(value)
            if (value && !Settings.canDrawOverlays(context)) {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri()),
                    )
                }
            }
        },
    )
    Text(
        text = stringResource(R.string.settings_elm_battery_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Dimens.s),
    )
}

@Composable
private fun BackupRows(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingImport by remember { mutableStateOf<Uri?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = runCatching {
                val json = viewModel.exportBackup()
                context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                    ?: error("cannot open $uri for writing")
            }.isSuccess
            Toast.makeText(
                context,
                if (ok) R.string.settings_backup_export_success else R.string.settings_backup_export_failed,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pendingImport = uri
    }

    ListRow(
        title = stringResource(R.string.settings_backup_export),
        subtitle = stringResource(R.string.settings_backup_hint),
        onClick = { exportLauncher.launch(defaultBackupFileName()) },
    )
    Divider()
    ListRow(
        title = stringResource(R.string.settings_backup_import),
        subtitle = stringResource(R.string.settings_backup_import_hint),
        onClick = { importLauncher.launch(arrayOf("application/json")) },
    )

    val target = pendingImport
    if (target != null) {
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text(stringResource(R.string.settings_backup_import_confirm_title)) },
            text = { Text(stringResource(R.string.settings_backup_import_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingImport = null
                        scope.launch {
                            val result = runCatching {
                                val json = context.contentResolver.openInputStream(target)
                                    ?.use { it.readBytes().decodeToString() }
                                    ?: error("cannot open $target for reading")
                                viewModel.importBackup(json)
                            }.getOrNull()
                            val message = if (result != null && result.success) {
                                context.getString(
                                    R.string.settings_backup_import_result,
                                    result.tripsAdded,
                                    result.refuelsAdded,
                                    result.tripsSkipped + result.refuelsSkipped,
                                )
                            } else {
                                context.getString(R.string.settings_backup_import_failed)
                            }
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        }
                    },
                ) {
                    Text(stringResource(R.string.settings_backup_import_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingImport = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

@Composable
private fun RetentionDialog(selected: Int, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_retention_label)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.settings_retention_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = Dimens.s),
                )
                RETENTION_OPTIONS.forEach { days ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = Dimens.touchTarget)
                            .selectable(selected = days == selected, role = Role.RadioButton) { onSelect(days) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = days == selected, onClick = null)
                        Text(
                            text = stringResource(R.string.settings_retention_days, days),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = Dimens.m),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        },
    )
}

@Composable
private fun ChangelogDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_changelog_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Dimens.xs),
            ) {
                Changelog.entries.forEach { entry ->
                    Text(
                        text = "${entry.versionName} — ${entry.title}",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = Dimens.s),
                    )
                    entry.changes.forEach { change ->
                        Text(
                            text = "• $change",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        },
    )
}

/**
 * Android Auto first-run checklist plus a last-seen diagnostic so the user can tell, without adb,
 * whether the phone ever bound to the car host. Reads the DataStore through a minimal Hilt entry
 * point to avoid widening `SettingsViewModel`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AndroidAutoSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val settingsRepository = remember(context) {
        EntryPointAccessors.fromApplication(context.applicationContext, CarDiagnosticsEntryPoint::class.java)
            .settingsRepository()
    }
    val settings by settingsRepository.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val lastSeenMs = settings.carLastSeenMs
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = Dimens.l, end = Dimens.l, bottom = Dimens.xl),
            verticalArrangement = Arrangement.spacedBy(Dimens.s),
        ) {
            Text(text = stringResource(R.string.settings_android_auto_title), style = MaterialTheme.typography.titleLarge)
            // One-line verdict — green once a car host has ever bound, red otherwise.
            Text(
                text = stringResource(
                    if (lastSeenMs != null) {
                        R.string.settings_android_auto_verdict_connected
                    } else {
                        R.string.settings_android_auto_verdict_not_connected
                    },
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = if (lastSeenMs != null) FuelTheme.colors.positive else MaterialTheme.colorScheme.error,
            )
            Text(
                text = if (lastSeenMs != null) {
                    stringResource(R.string.settings_android_auto_last_seen, formatDateTime(lastSeenMs))
                } else {
                    stringResource(R.string.settings_android_auto_never)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            settings.carLastHost?.let { host ->
                Text(
                    text = stringResource(R.string.settings_android_auto_last_host, host),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Installed build type: DEBUG accepts any host; RELEASE only the allow-list.
            Text(
                text = stringResource(
                    R.string.settings_android_auto_build_type,
                    stringResource(
                        if (BuildConfig.DEBUG) {
                            R.string.settings_android_auto_build_debug
                        } else {
                            R.string.settings_android_auto_build_release
                        },
                    ),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!BuildConfig.DEBUG && lastSeenMs == null) {
                Text(
                    text = stringResource(R.string.settings_android_auto_release_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            SectionTitle(stringResource(R.string.settings_android_auto_checklist_title))
            listOf(
                R.string.settings_android_auto_step_1,
                R.string.settings_android_auto_step_2,
                R.string.settings_android_auto_step_3,
                R.string.settings_android_auto_step_4,
            ).forEach { step ->
                Text(text = stringResource(step), style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                text = stringResource(R.string.settings_android_auto_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.settings_android_auto_trusted_store),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.settings_android_auto_diagnostic_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Hilt access to the settings store for the car-diagnostics sheet. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface CarDiagnosticsEntryPoint {
    fun settingsRepository(): SettingsRepository
}

private val RETENTION_OPTIONS = listOf(30, 60, 90, 180, 365)

private fun defaultBackupFileName(): String {
    val stamp = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
    return "fuelroute-backup-$stamp.json"
}

private fun gradeLabelRes(grade: String): Int = when (grade) {
    FuelGrades.GASOLINE_98 -> R.string.vehicle_grade_98
    FuelGrades.DIESEL -> R.string.vehicle_grade_diesel
    else -> R.string.vehicle_grade_95
}


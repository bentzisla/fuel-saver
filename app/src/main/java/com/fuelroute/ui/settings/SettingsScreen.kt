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
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.FilterChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
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
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    onOpenCalibration: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var ignoringBattery by remember { mutableStateOf(BatteryOptimization.isIgnoring(context)) }

    LifecycleResumeEffect(Unit) {
        ignoringBattery = BatteryOptimization.isIgnoring(context)
        onPauseOrDispose { }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineSmall,
        )

        SettingsSection(title = stringResource(R.string.settings_section_fuel)) {
            OutlinedTextField(
                value = state.fuelPrice,
                onValueChange = viewModel::onFuelPriceChange,
                label = { Text(stringResource(R.string.settings_fuel_price_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = state.valuePerMinute,
                onValueChange = viewModel::onValuePerMinuteChange,
                label = { Text(stringResource(R.string.settings_value_per_minute_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )

            ExpandableSettingsRow(
                label = stringResource(R.string.settings_section_fuel_advanced),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_price_pin),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = stringResource(
                                R.string.settings_price_grade,
                                stringResource(gradeLabelRes(state.priceGrade)),
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.pricePinned,
                        onCheckedChange = viewModel::onPricePinnedChange,
                    )
                }
            }
        }

        SettingsSection(title = stringResource(R.string.settings_section_nav)) {
            Text(
                text = stringResource(R.string.settings_nav_app_label),
                style = MaterialTheme.typography.titleSmall,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.navigationApp == NAV_GOOGLE,
                    onClick = { viewModel.onNavigationAppChange(NAV_GOOGLE) },
                    label = { Text(stringResource(R.string.settings_nav_google)) },
                )
                FilterChip(
                    selected = state.navigationApp == NAV_WAZE,
                    onClick = { viewModel.onNavigationAppChange(NAV_WAZE) },
                    label = { Text(stringResource(R.string.settings_nav_waze)) },
                )
            }
            if (state.navigationApp == NAV_WAZE) {
                Text(
                    text = stringResource(R.string.nav_waze_destination_only),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        SettingsSection(title = stringResource(R.string.settings_section_obd)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.settings_auto_connect),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = state.autoConnect,
                    onCheckedChange = viewModel::onAutoConnectChange,
                )
            }

            AutoLoggingSection(
                state = state,
                onIntroSeen = viewModel::onAutoConnectIntroSeen,
            )

            ExpandableSettingsRow(
                label = stringResource(R.string.settings_section_obd_advanced),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_show_overlay),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = stringResource(R.string.settings_show_overlay_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.showOverlay,
                        onCheckedChange = { value ->
                            viewModel.onShowOverlayChange(value)
                            if (value && !Settings.canDrawOverlays(context)) {
                                runCatching {
                                    context.startActivity(
                                        Intent(
                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.parse("package:${context.packageName}"),
                                        ),
                                    )
                                }
                            }
                        },
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_keep_screen_on),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = stringResource(R.string.settings_keep_screen_on_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.keepScreenOn,
                        onCheckedChange = viewModel::onKeepScreenOnChange,
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.settings_retention_label),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = stringResource(R.string.settings_retention_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(30, 60, 90, 180, 365).forEach { days ->
                            FilterChip(
                                selected = state.retentionDays == days,
                                onClick = { viewModel.onRetentionDaysChange(days) },
                                label = { Text(stringResource(R.string.settings_retention_days, days)) },
                            )
                        }
                    }
                }

                if (!ignoringBattery) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.settings_battery_optimization_rationale),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Button(
                                onClick = { BatteryOptimization.request(context) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.settings_battery_optimization_button))
                            }
                        }
                    }
                }

                Text(
                    text = stringResource(R.string.settings_elm_battery_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SettingsSection(
            title = stringResource(R.string.settings_section_data),
            initiallyExpanded = false,
        ) {
            CalibrationSection(onOpen = onOpenCalibration)
            BackupSection(viewModel = viewModel)
        }

        SettingsSection(
            title = stringResource(R.string.settings_section_android_auto),
            initiallyExpanded = false,
        ) {
            AndroidAutoHelpCard()
        }

        SettingsSection(
            title = stringResource(R.string.settings_section_about),
            initiallyExpanded = false,
        ) {
            VersionFooter()
            ChangelogCard()
            Text(
                text = stringResource(R.string.settings_autosaved),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    initiallyExpanded: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) {
                    Icons.Filled.KeyboardArrowUp
                } else {
                    Icons.Filled.KeyboardArrowDown
                },
                contentDescription = stringResource(
                    if (expanded) {
                        R.string.settings_section_collapse
                    } else {
                        R.string.settings_section_expand
                    },
                ),
            )
        }
        if (expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun ExpandableSettingsRow(
    label: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) {
                    Icons.Filled.KeyboardArrowUp
                } else {
                    Icons.Filled.KeyboardArrowDown
                },
                contentDescription = stringResource(
                    if (expanded) {
                        R.string.settings_section_collapse
                    } else {
                        R.string.settings_section_expand
                    },
                ),
            )
        }
        if (expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun VersionFooter() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(
                    R.string.settings_version_label,
                    BuildConfig.VERSION_NAME,
                    BuildConfig.VERSION_CODE,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * In-app "מה חדש" changelog. Renders [Changelog.entries] newest-first so the user can see what
 * changed in the installed build without opening a store page.
 */
@Composable
private fun ChangelogCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_changelog_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Changelog.entries.forEach { entry ->
                Text(
                    text = "${entry.versionName} — ${entry.title}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                entry.changes.forEach { change ->
                    Text(
                        text = "• $change",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * "אנדרואיד אוטו" help card (card 42): a first-run checklist plus a last-seen diagnostic so the user
 * can tell, without adb, whether the phone ever bound to the car host. Reads the DataStore directly
 * through a minimal Hilt entry point (the same pattern as `CarEntryPoint`/`RetentionWorkerEntryPoint`)
 * to avoid widening `SettingsViewModel`.
 */
@Composable
private fun AndroidAutoHelpCard() {
    val context = LocalContext.current
    val settingsRepository = remember(context) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            CarDiagnosticsEntryPoint::class.java,
        ).settingsRepository()
    }
    val settings by settingsRepository.settings.collectAsStateWithLifecycle(
        initialValue = AppSettings(),
    )

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_android_auto_title),
                style = MaterialTheme.typography.titleSmall,
            )
            // Installed build type (card 45): DEBUG accepts any host; RELEASE only the allow-list.
            val buildType = if (BuildConfig.DEBUG) {
                stringResource(R.string.settings_android_auto_build_debug)
            } else {
                stringResource(R.string.settings_android_auto_build_release)
            }
            Text(
                text = stringResource(R.string.settings_android_auto_build_type, buildType),
                style = MaterialTheme.typography.bodyMedium,
            )
            val lastSeenMs = settings.carLastSeenMs
            Text(
                text = if (lastSeenMs != null) {
                    stringResource(
                        R.string.settings_android_auto_last_seen,
                        formatTimestamp(lastSeenMs),
                    )
                } else {
                    stringResource(R.string.settings_android_auto_never)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            settings.carLastHost?.let { host ->
                Text(
                    text = stringResource(R.string.settings_android_auto_last_host, host),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // One-line verdict (card 45) — green once a car host has ever bound, red otherwise.
            Text(
                text = if (lastSeenMs != null) {
                    stringResource(R.string.settings_android_auto_verdict_connected)
                } else {
                    stringResource(R.string.settings_android_auto_verdict_not_connected)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (lastSeenMs != null) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            if (!BuildConfig.DEBUG && lastSeenMs == null) {
                Text(
                    text = stringResource(R.string.settings_android_auto_release_warning),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                text = stringResource(R.string.settings_android_auto_checklist_title),
                style = MaterialTheme.typography.titleSmall,
            )
            listOf(
                R.string.settings_android_auto_step_1,
                R.string.settings_android_auto_step_2,
                R.string.settings_android_auto_step_3,
                R.string.settings_android_auto_step_4,
            ).forEach { step ->
                Text(
                    text = stringResource(step),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                text = stringResource(R.string.settings_android_auto_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.settings_android_auto_diagnostic_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // The 2026 trusted-source gate: a sideloaded APK can be filtered by Android Auto even
            // with "Unknown sources" on, so the checklist above is not always sufficient.
            Text(
                text = stringResource(R.string.settings_android_auto_trusted_store),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** Hilt access to the settings store for the car-diagnostics help card (card 42). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface CarDiagnosticsEntryPoint {
    fun settingsRepository(): SettingsRepository
}

@Composable
private fun AutoLoggingSection(
    state: SettingsUiState,
    onIntroSeen: () -> Unit,
) {
    val context = LocalContext.current
    val permissions = remember {
        buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
    val missing = permissions.filterNot {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
    var batteryExempt by remember { mutableStateOf(BatteryOptimization.isIgnoring(context)) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        onIntroSeen()
        batteryExempt = BatteryOptimization.isIgnoring(context)
        if (!batteryExempt) BatteryOptimization.request(context)
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (state.autoConnect && !state.autoConnectIntroSeen) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.settings_auto_logging_intro),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.settings_auto_logging_intro_detail),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        onClick = {
                            onIntroSeen()
                            if (missing.isNotEmpty()) {
                                launcher.launch(missing.toTypedArray())
                            } else if (!BatteryOptimization.isIgnoring(context)) {
                                BatteryOptimization.request(context)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.settings_auto_logging_enable))
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_auto_logging_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = stringResource(
                        R.string.settings_auto_logging_state,
                        stringResource(
                            if (state.autoConnect) R.string.settings_on else R.string.settings_off,
                        ),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(
                        R.string.settings_auto_logging_device,
                        state.lastDeviceName?.takeIf { it.isNotBlank() }
                            ?: state.lastDeviceAddress
                            ?: stringResource(R.string.settings_auto_logging_no_device),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(
                        R.string.settings_auto_logging_last_start,
                        state.lastAutoStartMs?.let { formatTimestamp(it) }
                            ?: stringResource(R.string.settings_auto_logging_never),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            }
        }
    }
}

private fun formatTimestamp(ms: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ms))

@Composable
private fun CalibrationSection(onOpen: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_calibration_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(R.string.settings_calibration_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_calibration_open))
            }
        }
    }
}

@Composable
private fun BackupSection(viewModel: SettingsViewModel) {
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

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) pendingImport = uri
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_backup_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(R.string.settings_backup_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { exportLauncher.launch(defaultBackupFileName()) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.settings_backup_export))
                }
                Button(
                    onClick = { importLauncher.launch(arrayOf("application/json")) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.settings_backup_import))
                }
            }
        }
    }

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
                TextButton(onClick = { pendingImport = null }) {
                    Text(stringResource(R.string.settings_backup_cancel))
                }
            },
        )
    }
}

private fun defaultBackupFileName(): String {
    val stamp = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
    return "fuelroute-backup-$stamp.json"
}

@StringRes
private fun gradeLabelRes(grade: String): Int = when (grade) {
    FuelGrades.GASOLINE_98 -> R.string.vehicle_grade_98
    FuelGrades.DIESEL -> R.string.vehicle_grade_diesel
    else -> R.string.vehicle_grade_95
}
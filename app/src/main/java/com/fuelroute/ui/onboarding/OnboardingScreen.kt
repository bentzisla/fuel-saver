package com.fuelroute.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fuelroute.R

/**
 * First-run setup screen, shown once before the tabs (persisted via
 * `SettingsRepository.onboardingSeen`). It gates nothing — the user can dismiss it with
 * "סיימתי" — and mirrors the step-card style of the Android Auto help card in Settings.
 *
 * Covers the two hard prerequisites:
 *  1. a Google Cloud project + API key (Routes API, Maps SDK for Android, Places API New,
 *     SHA-1 restriction, `local.properties`), and
 *  2. an ELM327 dongle (pairing, Bluetooth permissions, battery-optimization exemption).
 * plus a "try demo" entry point that needs no dongle.
 */
@Composable
fun OnboardingScreen(
    onDone: () -> Unit,
    onTryDemo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.onboarding_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(R.string.onboarding_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SetupCard(
            title = stringResource(R.string.onboarding_google_title),
            steps = listOf(
                R.string.onboarding_google_step_1,
                R.string.onboarding_google_step_2,
                R.string.onboarding_google_step_3,
                R.string.onboarding_google_step_4,
            ),
            note = stringResource(R.string.onboarding_google_note),
        )

        SetupCard(
            title = stringResource(R.string.onboarding_obd_title),
            steps = listOf(
                R.string.onboarding_obd_step_1,
                R.string.onboarding_obd_step_2,
                R.string.onboarding_obd_step_3,
            ),
            note = stringResource(R.string.onboarding_obd_note),
        )

        OutlinedButton(onClick = onTryDemo, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.onboarding_demo))
        }
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.onboarding_done))
        }
    }
}

@Composable
private fun SetupCard(
    title: String,
    steps: List<Int>,
    note: String?,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
            )
            steps.forEach { step ->
                Text(
                    text = stringResource(step),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            note?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
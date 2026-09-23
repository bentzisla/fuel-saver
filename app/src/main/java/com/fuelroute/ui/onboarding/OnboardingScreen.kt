package com.fuelroute.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.fuelroute.R
import com.fuelroute.ui.components.Dimens
import com.fuelroute.ui.components.PrimaryButton
import com.fuelroute.ui.components.SecondaryButton
import com.fuelroute.ui.components.SectionCard

/**
 * First-run setup screen, shown once before the tabs (persisted via
 * `SettingsRepository.onboardingSeen`). It gates nothing — the user can dismiss it with
 * "סיימתי" — and covers the two hard prerequisites (a Google Cloud API key, an ELM327 dongle)
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
            .padding(Dimens.l),
        verticalArrangement = Arrangement.spacedBy(Dimens.l),
    ) {
        Text(
            text = stringResource(R.string.onboarding_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(top = Dimens.l),
        )
        Text(
            text = stringResource(R.string.onboarding_intro),
            style = MaterialTheme.typography.bodyLarge,
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

        PrimaryButton(text = stringResource(R.string.onboarding_done), onClick = onDone)
        SecondaryButton(text = stringResource(R.string.onboarding_demo), onClick = onTryDemo)
    }
}

@Composable
private fun SetupCard(
    title: String,
    steps: List<Int>,
    note: String?,
) {
    SectionCard(title = title) {
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
            steps.forEach { step ->
                Text(text = stringResource(step), style = MaterialTheme.typography.bodyMedium)
            }
        }
        note?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

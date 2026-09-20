package com.fuelroute.ui.permission

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.fuelroute.R

/**
 * Gates a feature behind a set of runtime permissions.
 *
 * - [permissions] must all be granted before [content] is shown.
 * - [optionalPermissions] are requested alongside them but never block [content]
 *   (e.g. POST_NOTIFICATIONS: the foreground service still runs without it).
 * - When denied, it shows [rationale] and a grant button. Once the system reports
 *   the request is permanently denied (no rationale and still not granted) it
 *   shows [permanentlyDeniedMessage] plus a deep-link to the app settings, and the
 *   feature is disabled instead of crashing.
 *
 * Location permissions are deliberately never requested here; they are only asked
 * for from the Route tab where the location is actually used.
 */
@Composable
fun PermissionGate(
    permissions: List<String>,
    rationale: String,
    permanentlyDeniedMessage: String,
    modifier: Modifier = Modifier,
    optionalPermissions: List<String> = emptyList(),
    onGranted: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    var granted by remember(permissions) {
        mutableStateOf(permissions.all { context.isGranted(it) })
    }
    var requested by remember(permissions) { mutableStateOf(false) }
    val allToRequest = remember(permissions, optionalPermissions) {
        (permissions + optionalPermissions).distinct()
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        requested = true
        granted = permissions.all { context.isGranted(it) }
    }

    val permanentlyDenied = requested && !granted &&
        permissions.none { activity?.shouldShowRequestPermissionRationale(it) == true }

    LaunchedEffect(granted) {
        if (granted) onGranted()
    }

    if (granted) {
        content()
        return
    }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = rationale,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (permanentlyDenied) {
                Text(
                    text = permanentlyDeniedMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Button(
                    onClick = {
                        requested = true
                        launcher.launch(allToRequest.toTypedArray())
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.permission_grant))
                }
            }
            TextButton(
                onClick = { context.openAppSettings() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.permission_open_settings))
            }
        }
    }
}

private fun Context.isGranted(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

internal fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

private fun Context.openAppSettings() {
    runCatching {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
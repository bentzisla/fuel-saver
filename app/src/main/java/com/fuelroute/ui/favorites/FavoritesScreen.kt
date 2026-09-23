package com.fuelroute.ui.favorites

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star as StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fuelroute.R
import com.fuelroute.data.places.FavoriteDestination
import com.fuelroute.data.places.RecentPlace
import com.fuelroute.ui.components.ConfirmDialog
import com.fuelroute.ui.components.Dimens
import com.fuelroute.ui.components.EmptyState
import com.fuelroute.ui.components.FuelTopBar
import com.fuelroute.ui.components.ListRow
import com.fuelroute.ui.components.SectionTitle

/**
 * Full-screen "my destinations" manager, opened from the route screen's quick-destinations row.
 * Everything that used to be a tiny icon inside a chip lives here as a proper 48dp control:
 * rename, reorder and delete favorites, and star a recent destination.
 */
@Composable
fun FavoritesScreen(
    favorites: List<FavoriteDestination>,
    recents: List<RecentPlace>,
    onRename: (FavoriteDestination, String) -> Unit,
    onMoveUp: (FavoriteDestination) -> Unit,
    onMoveDown: (FavoriteDestination) -> Unit,
    onDelete: (FavoriteDestination) -> Unit,
    onAddRecent: (RecentPlace) -> Unit,
    onDismiss: () -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<FavoriteDestination?>(null) }
    var renaming by remember { mutableStateOf<FavoriteDestination?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding(),
            ) {
                FuelTopBar(title = stringResource(R.string.favorites_title), onBack = onDismiss)
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = Dimens.l, vertical = Dimens.s),
                ) {
                    item { SectionTitle(stringResource(R.string.route_favorites_title)) }
                    if (favorites.isEmpty()) {
                        item {
                            EmptyState(
                                title = stringResource(R.string.favorites_empty),
                                body = stringResource(R.string.route_favorites_empty_hint),
                            )
                        }
                    }
                    itemsIndexed(favorites, key = { _, favorite -> "f${favorite.id}" }) { index, favorite ->
                        // Tapping the name renames it; the trailing icons reorder / delete.
                        ListRow(
                            title = favorite.label,
                            subtitle = stringResource(R.string.favorites_tap_to_rename),
                            onClick = { renaming = favorite },
                            leading = {
                                Icon(
                                    Icons.Filled.Star,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            },
                            trailing = {
                                IconButton(onClick = { onMoveUp(favorite) }, enabled = index > 0) {
                                    Icon(
                                        imageVector = Icons.Filled.KeyboardArrowUp,
                                        contentDescription = stringResource(R.string.favorites_move_up),
                                    )
                                }
                                IconButton(
                                    onClick = { onMoveDown(favorite) },
                                    enabled = index < favorites.lastIndex,
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.KeyboardArrowDown,
                                        contentDescription = stringResource(R.string.favorites_move_down),
                                    )
                                }
                                IconButton(onClick = { pendingDelete = favorite }) {
                                    Icon(
                                        imageVector = Icons.Filled.Delete,
                                        contentDescription = stringResource(R.string.route_favorite_delete),
                                    )
                                }
                            },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }

                    if (recents.isNotEmpty()) {
                        item { SectionTitle(stringResource(R.string.route_recent_destinations)) }
                        itemsIndexed(recents) { _, place ->
                            ListRow(
                                title = place.label,
                                leading = { Icon(painterResource(R.drawable.ic_history), contentDescription = null) },
                                trailing = {
                                    IconButton(onClick = { onAddRecent(place) }) {
                                        Icon(
                                            imageVector = Icons.Outlined.StarOutline,
                                            contentDescription = stringResource(R.string.route_favorite_add),
                                        )
                                    }
                                },
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
    }

    renaming?.let { favorite ->
        RenameDialog(
            initialLabel = favorite.label,
            onDismiss = { renaming = null },
            onRename = { label ->
                onRename(favorite, label)
                renaming = null
            },
        )
    }

    pendingDelete?.let { favorite ->
        ConfirmDialog(
            title = stringResource(R.string.route_favorite_delete_title),
            message = stringResource(R.string.route_favorite_delete_message, favorite.label),
            confirmLabel = stringResource(R.string.route_favorite_delete),
            onConfirm = {
                onDelete(favorite)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun RenameDialog(
    initialLabel: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    var label by remember { mutableStateOf(initialLabel) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.route_favorite_rename_title)) },
        text = {
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                singleLine = true,
                label = { Text(stringResource(R.string.route_favorite_label_label)) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onRename(label.trim()) }, enabled = label.isNotBlank()) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

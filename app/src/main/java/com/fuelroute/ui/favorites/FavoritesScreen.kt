package com.fuelroute.ui.favorites

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fuelroute.R
import com.fuelroute.data.places.FavoriteDestination

/**
 * Full-screen manager for the (rare) case where the favorites list grows past the handful of
 * chips shown on the route screen. Reorder with up/down buttons and delete with confirmation.
 */
@Composable
fun FavoritesScreen(
    favorites: List<FavoriteDestination>,
    onMoveUp: (FavoriteDestination) -> Unit,
    onMoveDown: (FavoriteDestination) -> Unit,
    onDelete: (FavoriteDestination) -> Unit,
    onDismiss: () -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<FavoriteDestination?>(null) }

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
                    .padding(16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.favorites_title),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.route_close))
                    }
                }

                if (favorites.isEmpty()) {
                    Text(
                        text = stringResource(R.string.favorites_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        itemsIndexed(favorites, key = { _, favorite -> favorite.id }) { index, favorite ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = favorite.label,
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                    favorite.placeId?.let { id ->
                                        Text(
                                            text = id,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = { onMoveUp(favorite) },
                                    enabled = index > 0,
                                ) {
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
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { favorite ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.route_favorite_delete_title)) },
            text = { Text(stringResource(R.string.route_favorite_delete_message, favorite.label)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(favorite)
                        pendingDelete = null
                    },
                ) {
                    Text(stringResource(R.string.route_favorite_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.route_favorite_cancel))
                }
            },
        )
    }
}
package com.metrolist.music.ui.screens.equalizer

import android.annotation.SuppressLint
import android.content.Context
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.metrolist.music.constants.BassBoostEnabledKey
import com.metrolist.music.constants.BassBoostStrengthKey
import com.metrolist.music.constants.VirtualizerEnabledKey
import com.metrolist.music.constants.VirtualizerStrengthKey
import com.metrolist.music.utils.dataStore
import androidx.datastore.preferences.core.edit
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.music.LocalNavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.eq.data.SavedEQProfile
import timber.log.Timber

/**
 * EQ Screen - Manage and select EQ profiles
 */
@SuppressLint("LocalContextGetResourceValueCall")
@Composable
fun EqScreen(
    viewModel: EQViewModel = hiltViewModel(),
) {
    val navController = LocalNavController.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showError by remember { mutableStateOf<String?>(null) }
    var showAddMenu by remember { mutableStateOf(false) }

    // File picker for custom EQ import
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            try {
                val contentResolver = context.contentResolver

                // Extract file name from URI
                var fileName = "custom_eq.txt"
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val displayNameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (displayNameIndex >= 0) {
                            val name = cursor.getString(displayNameIndex)
                            if (!name.isNullOrBlank()) {
                                fileName = name
                            }
                        }
                    }
                }

                val inputStream = contentResolver.openInputStream(uri)

                if (inputStream != null) {
                    viewModel.importCustomProfile(
                        fileName = fileName,
                        inputStream = inputStream,
                        onSuccess = {
                            Timber.d("Custom EQ profile imported successfully: $fileName")
                        },
                        onError = { error ->
                            Timber.d("Error: Unable to import Custom EQ profile: $fileName")
                            showError = context.getString(R.string.import_error_title) + ": " + error.message
                        })
                } else {
                    showError = context.getString(R.string.error_file_read)
                }
            } catch (e: Exception) {
                showError = context.getString(R.string.error_file_open, e.message)
            }
        }
    }

    val activeProfile = state.profiles.find { it.id == state.activeProfileId }

    EqScreenContent(
        profiles = state.profiles,
        activeProfileId = state.activeProfileId,
        activeProfile = activeProfile,
        onProfileSelected = { viewModel.selectProfile(it) },
        onNavigateBack = { navController.navigateUp() },
        showAddMenu = showAddMenu,
        onAddClicked = { showAddMenu = true },
        onAddMenuDismissed = { showAddMenu = false },
        onWizardClicked = {
            showAddMenu = false
            navController.navigate("eq_wizard")
        },
        onImportClicked = {
            showAddMenu = false
            filePickerLauncher.launch("text/plain")
        },
        onDeleteProfile = { viewModel.deleteProfile(it) }
    )

    // Error dialog
    if (showError != null) {
        AlertDialog(
            onDismissRequest = { showError = null },
            title = {
                Text(stringResource(R.string.import_error_title))
            },
            text = {
                Text(showError ?: "")
            },
            confirmButton = {
                TextButton(onClick = { showError = null }) {
                    Text(stringResource(android.R.string.ok))
                }
            }
        )
    }

    // Error dialog for apply failure
    if (state.error != null) {
        AlertDialog(
            onDismissRequest = { viewModel.clearError() },
            title = {
                Text(stringResource(R.string.error_title))
            },
            text = {
                Text(stringResource(R.string.error_eq_apply_failed, state.error ?: ""))
            },
            confirmButton = {
                TextButton(onClick = { viewModel.clearError() }) {
                    Text(stringResource(android.R.string.ok))
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EqScreenContent(
    profiles: List<SavedEQProfile>,
    activeProfileId: String?,
    activeProfile: SavedEQProfile?,
    onProfileSelected: (String?) -> Unit,
    onNavigateBack: () -> Unit,
    showAddMenu: Boolean,
    onAddClicked: () -> Unit,
    onAddMenuDismissed: () -> Unit,
    onWizardClicked: () -> Unit,
    onImportClicked: () -> Unit,
    onDeleteProfile: (String) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.equalizer_header)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            painter = painterResource(R.drawable.arrow_back),
                            contentDescription = null
                        )
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = onAddClicked) {
                            Icon(
                                painter = painterResource(R.drawable.add),
                                contentDescription = stringResource(R.string.import_profile)
                            )
                        }
                        DropdownMenu(
                            expanded = showAddMenu,
                            onDismissRequest = onAddMenuDismissed
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.eq_wizard)) },
                                leadingIcon = {
                                    Icon(
                                        painter = painterResource(R.drawable.discover_tune),
                                        contentDescription = null
                                    )
                                },
                                onClick = onWizardClicked
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.import_from_file)) },
                                leadingIcon = {
                                    Icon(
                                        painter = painterResource(R.drawable.upload),
                                        contentDescription = null
                                    )
                                },
                                onClick = onImportClicked
                            )
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        // Profile list
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(paddingValues),
            contentPadding = PaddingValues(bottom = LocalPlayerAwareWindowInsets.current
                .asPaddingValues().calculateBottomPadding())
        ) {
            // Frequency response graph
            item {
                EqFrequencyResponseGraph(
                    bands = activeProfile?.bands ?: emptyList(),
                    preamp = activeProfile?.preamp ?: 0.0
                )
            }

            // Auralys Extended: Bass Boost & Virtualizer controls
            item {
                BassVirtualizerCard()
            }

            // "No Equalization" option (always first)
            item {
                NoEqualizationItem(
                    isSelected = activeProfileId == null,
                    onSelected = { onProfileSelected(null) }
                )
            }

            // Custom profiles only
            val customProfiles = profiles.filter { it.isCustom }

            if (customProfiles.isNotEmpty()) {
                items(customProfiles) { profile ->
                    EQProfileItem(
                        profile = profile,
                        isSelected = activeProfileId == profile.id,
                        onSelected = { onProfileSelected(profile.id) },
                        onDelete = { onDeleteProfile(profile.id) }
                    )
                }
            }

            // Empty state
            if (customProfiles.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.equalizer),
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = stringResource(R.string.no_profiles),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(onClick = onAddClicked) {
                                Text(stringResource(R.string.import_profile))
                            }
                        }
                    }
                }
            }
        }
    }
}

// --- HELPER COMPOSABLES ---

/**
 * Auralys Extended: Bass Boost & Virtualizer controls.
 * Talks to the running MusicService via MediaBrowser/Controller binder when
 * available; falls back to writing DataStore preferences directly.
 */
@Composable
private fun BassVirtualizerCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.dataStore.data }

    var bassEnabled by remember { mutableStateOf(false) }
    var bassStrength by remember { mutableIntStateOf(0) }
    var virtEnabled by remember { mutableStateOf(false) }
    var virtStrength by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        context.dataStore.data.collect { p ->
            bassEnabled = p[BassBoostEnabledKey] ?: false
            bassStrength = p[BassBoostStrengthKey] ?: 0
            virtEnabled = p[VirtualizerEnabledKey] ?: false
            virtStrength = p[VirtualizerStrengthKey] ?: 0
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.bass_virtualizer_header),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Bass Boost row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.bass_boost),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = bassEnabled,
                    onCheckedChange = { enabled ->
                        val strength = if (bassStrength == 0) 500 else bassStrength
                        bassStrength = strength
                        scope.launch { applyBassVirtualizer(context, bass = enabled to strength, virtualizer = null) }
                    },
                )
            }
            Slider(
                value = bassStrength / 1000f,
                onValueChange = { v ->
                    bassStrength = (v * 1000).toInt()
                    scope.launch { applyBassVirtualizer(context, bass = bassEnabled to bassStrength, virtualizer = null) }
                },
                enabled = bassEnabled,
            )

            // Virtualizer row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.virtualizer),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = virtEnabled,
                    onCheckedChange = { enabled ->
                        val strength = if (virtStrength == 0) 500 else virtStrength
                        virtStrength = strength
                        scope.launch { applyBassVirtualizer(context, bass = null, virtualizer = enabled to strength) }
                    },
                )
            }
            Slider(
                value = virtStrength / 1000f,
                onValueChange = { v ->
                    virtStrength = (v * 1000).toInt()
                    scope.launch { applyBassVirtualizer(context, bass = null, virtualizer = virtEnabled to virtStrength) }
                },
                enabled = virtEnabled,
            )
        }
    }
}

/**
 * Persist Bass Boost / Virtualizer settings. MusicService observes these keys
 * and pushes them live into the running audio processors, so no binder is
 * needed here.
 */
private suspend fun applyBassVirtualizer(
    context: Context,
    bass: Pair<Boolean, Int>?,
    virtualizer: Pair<Boolean, Int>?,
) {
    context.dataStore.edit { p ->
        bass?.let { (en, st) ->
            p[BassBoostEnabledKey] = en
            p[BassBoostStrengthKey] = st
        }
        virtualizer?.let { (en, st) ->
            p[VirtualizerEnabledKey] = en
            p[VirtualizerStrengthKey] = st
        }
    }
}

@Composable
private fun NoEqualizationItem(
    isSelected: Boolean,
    onSelected: () -> Unit
) {
    ListItem(
        content = {
            Text(
                stringResource(R.string.eq_disabled),
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
            )
        },
        leadingContent = {
            RadioButton(
                selected = isSelected,
                onClick = onSelected
            )
        },
        modifier = Modifier
            .clickable(onClick = onSelected)
            .padding(horizontal = 8.dp) // align with design
    )
}

@Composable
private fun EQProfileItem(
    profile: SavedEQProfile,
    isSelected: Boolean,
    onSelected: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    ListItem(
        content = {
            Text(
                text = profile.deviceModel,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
            )
        },
        supportingContent = {
            Text(
                pluralStringResource(
                    id = R.plurals.band_count,
                    count = profile.bands.size,
                    profile.bands.size
                )
            )
        },
        leadingContent = {
            RadioButton(
                selected = isSelected,
                onClick = onSelected
            )
        },
        trailingContent = {
            IconButton(onClick = { showDeleteDialog = true }) {
                Icon(
                    painter = painterResource(R.drawable.delete),
                    contentDescription = stringResource(R.string.delete_profile_desc),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        },
        modifier = Modifier
            .clickable(onClick = onSelected)
            .padding(horizontal = 8.dp)
    )

    // Delete confirmation dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.delete_profile_desc)) },
            text = {
                Text(
                    stringResource(R.string.delete_profile_confirmation, profile.name)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete()
                        showDeleteDialog = false
                    }
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}

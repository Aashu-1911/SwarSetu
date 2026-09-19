// The file's single top-level class (ProfileFormState, the content composable's state holder) rides
// along with the screen composable that is the file's real subject.
@file:Suppress("MatchingDeclarationName")

package app.swarsetu.ui.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.swarsetu.BuildConfig
import app.swarsetu.R
import app.swarsetu.TextLimits
import app.swarsetu.identity.displayNameFor
import app.swarsetu.ui.components.Avatar
import app.swarsetu.ui.isIgnoringBatteryOptimizations
import app.swarsetu.ui.preview.SwarSetuPreview
import app.swarsetu.ui.requestIgnoreBatteryOptimizations
import org.koin.androidx.compose.koinViewModel

import androidx.compose.foundation.layout.height
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import app.swarsetu.stt.SttLanguage

/** UI-local projection of [ProfileViewModel]'s per-field flows for the stateless content. */
internal data class ProfileFormState(
    val name: String,
    val status: String,
    val nodeId: String,
    val alias: String,
    val avatarHash: String?,
    val contentFilteringEnabled: Boolean,

    val isDirty: Boolean,
    val preferredLanguage: SttLanguage = SttLanguage.HINDI,
    val availableLanguages: List<SttLanguage> = SttLanguage.entries,
    val installedLanguages: Set<SttLanguage> = emptySet(),
    val themePreference: String = "system",
    val autoPlayTts: Boolean = true,
)

@Composable
fun ProfileScreen(
    onBack: () -> Unit,

    viewModel: ProfileViewModel = koinViewModel(),
) {
    val name by viewModel.displayName.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val nodeId by viewModel.nodeId.collectAsStateWithLifecycle()
    val alias by viewModel.alias.collectAsStateWithLifecycle()
    val avatarHash by viewModel.avatarHash.collectAsStateWithLifecycle()
    val cropTarget by viewModel.cropTarget.collectAsStateWithLifecycle()
    val contentFilteringEnabled by viewModel.contentFilteringEnabled.collectAsStateWithLifecycle()

    val isDirty by viewModel.isDirty.collectAsStateWithLifecycle()
    val preferredLanguage by viewModel.preferredLanguage.collectAsStateWithLifecycle()
    val availableLanguages by viewModel.availableLanguages.collectAsStateWithLifecycle()
    val installedLanguages by viewModel.installedLanguages.collectAsStateWithLifecycle()
    val themePreference by viewModel.themePreference.collectAsStateWithLifecycle()
    val autoPlayTts by viewModel.autoPlayTts.collectAsStateWithLifecycle()

    // Navigate back only once Save has finished persisting (the write outlives this composition because
    // it runs in viewModelScope, but we wait so the user lands back on the previous screen on success).
    LaunchedEffect(Unit) {
        viewModel.saved.collect { onBack() }
    }

    val picker =
        rememberLauncherForActivityResult(
            ActivityResultContracts.PickVisualMedia(),
        ) { uri -> uri?.let(viewModel::pickAvatar) }

    cropTarget?.let { bmp ->
        val image = remember(bmp) { bmp.asImageBitmap() }
        AvatarCropDialog(
            bitmap = image,
            onCancel = viewModel::cancelCrop,
            onConfirm = viewModel::confirmCrop,
        )
    }

    val context = LocalContext.current
    ProfileScreenContent(
        form =
            ProfileFormState(
                name = name,
                status = status,
                nodeId = nodeId,
                alias = alias,
                avatarHash = avatarHash,
                contentFilteringEnabled = contentFilteringEnabled,

                isDirty = isDirty,
                preferredLanguage = preferredLanguage,
                availableLanguages = availableLanguages,
                installedLanguages = installedLanguages,
                themePreference = themePreference,
                autoPlayTts = autoPlayTts,
            ),
        batteryExempt = rememberBatteryExempt(),
        onBack = onBack,
        onNameChange = viewModel::setDisplayName,
        onNameCommit = viewModel::commitDisplayName,
        onStatusChange = viewModel::setStatus,
        onStatusCommit = viewModel::commitStatus,
        onToggleContentFiltering = viewModel::setContentFilteringEnabled,
        onLanguageChange = viewModel::setPreferredLanguage,
        onThemeChange = viewModel::setThemePreference,
        onAutoPlayTtsChange = viewModel::setAutoPlayTts,

        onPickPhoto = {
            picker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        },
        onClearPhoto = viewModel::clearAvatar,
        onAllowBattery = { requestIgnoreBatteryOptimizations(context) },
        onSave = viewModel::save,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProfileScreenContent(
    form: ProfileFormState,
    batteryExempt: Boolean,
    onBack: () -> Unit,
    onNameChange: (String) -> Unit,
    onNameCommit: () -> Unit,
    onStatusChange: (String) -> Unit,
    onStatusCommit: () -> Unit,
    onToggleContentFiltering: (Boolean) -> Unit,
    onLanguageChange: (SttLanguage) -> Unit = {},
    onThemeChange: (String) -> Unit = {},
    onAutoPlayTtsChange: (Boolean) -> Unit = {},

    onPickPhoto: () -> Unit,
    onClearPhoto: () -> Unit,
    onAllowBattery: () -> Unit,
    onSave: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.settings_title),
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // --- 1. Avatar Header ---
            Box {
                Avatar(
                    avatarHash = form.avatarHash,
                    name = displayNameFor(form.name, form.nodeId),
                    size = 96.dp,
                    background = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    textStyle = MaterialTheme.typography.displaySmall,
                    contentDescription = stringResource(R.string.profile_change_photo_desc),
                    onClick = onPickPhoto,
                )
                if (form.avatarHash != null) {
                    RemovePhotoButton(onClick = onClearPhoto)
                }
            }

            // --- 2. Account Profile Card ---
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "ACCOUNT",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        ),
                    )

                    OutlinedTextField(
                        value = form.name,
                        onValueChange = onNameChange,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .testTag("profile_name")
                                .onFocusChanged { if (!it.isFocused) onNameCommit() },
                        label = { Text(stringResource(R.string.profile_display_name_label)) },
                        placeholder = { Text(form.alias) },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        supportingText = { CharCounter(form.name.length, TextLimits.DISPLAY_NAME) },
                    )
                    OutlinedTextField(
                        value = form.status,
                        onValueChange = onStatusChange,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .testTag("profile_status")
                                .onFocusChanged { if (!it.isFocused) onStatusCommit() },
                        label = { Text(stringResource(R.string.profile_status_label)) },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        supportingText = { CharCounter(form.status.length, TextLimits.STATUS) },
                    )
                    Text(
                        text = stringResource(R.string.profile_node_id, form.nodeId),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // --- 3. Appearance Card ---
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "APPEARANCE",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        ),
                    )

                    AppearanceSelectorRow(
                        currentTheme = form.themePreference,
                        onThemeSelect = onThemeChange,
                    )
                }
            }

            // --- 4. Voice & Translation Card ---
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "VOICE & TRANSLATION",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        ),
                    )

                    PreferredLanguageSection(
                        selectedLanguage = form.preferredLanguage,
                        allLanguages = form.availableLanguages,
                        installedLanguages = form.installedLanguages,
                        onLanguageChange = onLanguageChange,
                    )

                    ActiveVoiceModelSection(
                        language = form.preferredLanguage,
                    )

                    AutoPlayTtsRow(
                        enabled = form.autoPlayTts,
                        onToggle = onAutoPlayTtsChange,
                    )
                }
            }

            // --- 5. Connection & Privacy Card ---
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "CONNECTION & SECURITY",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        ),
                    )

                    ContentFilteringRow(
                        enabled = form.contentFilteringEnabled,
                        onToggle = onToggleContentFiltering,
                    )



                    BatteryOptimizationRow(exempt = batteryExempt, onAllow = onAllowBattery)
                }
            }

            // --- 6. Save Button ---
            Button(
                onClick = onSave,
                enabled = form.isDirty,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().testTag("profile_save"),
            ) {
                Text(
                    text = stringResource(R.string.action_save),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun AppearanceSelectorRow(
    currentTheme: String,
    onThemeSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = listOf(
        "system" to "System",
        "light" to "Light",
        "dark" to "Dark",
    )

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (key, label) ->
            val isSelected = currentTheme.equals(key, ignoreCase = true)
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                border = BorderStroke(
                    1.dp,
                    if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                ),
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onThemeSelect(key) },
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(vertical = 10.dp),
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun AutoPlayTtsRow(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onToggle(!enabled) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Auto-play Translated Voice",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Automatically speak incoming voice messages in your preferred language",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = onToggle,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreferredLanguageSection(
    selectedLanguage: SttLanguage,
    allLanguages: List<SttLanguage>,
    installedLanguages: Set<SttLanguage>,
    onLanguageChange: (SttLanguage) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(R.string.profile_pref_lang_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.profile_pref_lang_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedTextField(
                value = selectedLanguage.fullDisplayName,
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.profile_pref_lang_selector_label)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                modifier = Modifier.menuAnchor().fillMaxWidth(),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                allLanguages.forEach { lang ->
                    val isInstalled = installedLanguages.contains(lang)
                    val isSelected = lang == selectedLanguage
                    val localizedTitle = lang.localizedNameIn(selectedLanguage)
                    val subtitle = lang.subtitleIn(selectedLanguage)
                    DropdownMenuItem(
                        text = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f, fill = false)) {
                                    Text(
                                        text = localizedTitle,
                                        style = MaterialTheme.typography.bodyLarge.copy(
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                        ),
                                    )
                                    if (subtitle.isNotBlank()) {
                                        Text(
                                            text = subtitle,
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            ),
                                        )
                                    }
                                }
                                if (isInstalled) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                    ) {
                                        Text(
                                            text = "Ready",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontWeight = FontWeight.SemiBold,
                                            ),
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                            }
                        },
                        onClick = {
                            onLanguageChange(lang)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ActiveVoiceModelSection(
    language: SttLanguage,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_active_voice_model_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // STT Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Mic,
                        contentDescription = stringResource(R.string.settings_active_voice_model_stt),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_active_voice_model_stt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(R.string.settings_active_voice_model_sravaani, language.displayName),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }

                    // Always ready: the SraVaani model is packaged in the APK and serves all
                    // 10 languages — there is nothing to download or install per language.
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Text(
                            text = stringResource(R.string.settings_voice_model_status_ready),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                // TTS Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = stringResource(R.string.settings_active_voice_model_tts),
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_active_voice_model_tts),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(R.string.settings_active_voice_model_system_tts, language.displayName),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Text(
                            text = stringResource(R.string.settings_voice_model_tts_status),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Small circular "X" badge that clears the photo, straddling the avatar's top-end edge. The visible
 * circle is intentionally small (28dp), but [minimumInteractiveComponentSize] keeps the *touch target* at
 * the 48dp accessibility minimum, and the badge carries its own spoken label + [Role.Button] so TalkBack
 * announces it as a distinct, named action separate from the avatar's "change photo" tap.
 *
 * The [offset] nudges the badge up-and-out along the circle's 45° so its center lands on the avatar's
 * rim — roughly half the button hangs outside the circle — so it reads as an attached control rather
 * than an overlay covering the photo.
 */
@Composable
private fun BoxScope.RemovePhotoButton(onClick: () -> Unit) {
    val description = stringResource(R.string.profile_remove_photo_desc)
    Box(
        modifier =
            Modifier
                .align(Alignment.TopEnd)
                .offset(x = 10.dp, y = (-10).dp)
                .minimumInteractiveComponentSize()
                .size(28.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Close,
            // Decorative: the enclosing Box carries the accessible name.
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** Right-aligned "used / limit" counter shown beneath a capped single-line field. */
@Composable
private fun CharCounter(
    length: Int,
    limit: Int,
) {
    Text(
        text = "$length / $limit",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.End,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Toggle for on-device content moderation (abusive-text + explicit-image filtering). */
@Composable
private fun ContentFilteringRow(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                // One toggle target: the row owns the switch so a screen reader announces the title +
                // subtitle as the label with an on/off state, instead of an unlabelled switch node.
                .toggleable(value = enabled, onValueChange = onToggle, role = Role.Switch)
                .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.settings_content_filtering_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.settings_content_filtering_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        // null handler: the row's toggleable owns the interaction (avoids a duplicate focus stop).
        Switch(checked = enabled, onCheckedChange = null)
    }
}



/**
 * Whether the app is currently exempt from battery optimization, refreshed on every screen resume.
 * Lives in the stateful wrapper (not [BatteryOptimizationRow]) because the `PowerManager` read is not
 * available to the preview renderer.
 */
@Composable
private fun rememberBatteryExempt(): Boolean {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var exempt by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    exempt = isIgnoringBatteryOptimizations(context)
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return exempt
}

/** Shows whether the app is exempt from battery optimization, with an "allow" affordance when not. */
@Composable
private fun BatteryOptimizationRow(
    exempt: Boolean,
    onAllow: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text =
                if (exempt) {
                    stringResource(R.string.battery_allowed)
                } else {
                    stringResource(R.string.battery_restricted)
                },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (!exempt) {
            TextButton(onClick = onAllow) {
                Text(stringResource(R.string.battery_allow_button))
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun CharCounterPreview() =
    SwarSetuPreview {
        Column {
            CharCounter(length = 12, limit = 40)
            CharCounter(length = 40, limit = 40)
        }
    }

@Preview(showBackground = true)
@Composable
fun ContentFilteringRowPreview() =
    SwarSetuPreview {
        Column {
            ContentFilteringRow(enabled = true, onToggle = {})
            ContentFilteringRow(enabled = false, onToggle = {})
        }
    }

@Preview(showBackground = true)
@Composable
fun BatteryOptimizationRowPreview() =
    SwarSetuPreview {
        Column {
            BatteryOptimizationRow(exempt = true, onAllow = {})
            BatteryOptimizationRow(exempt = false, onAllow = {})
        }
    }

@Preview(showBackground = true)
@Composable
fun ProfileScreenPreview() =
    SwarSetuPreview {
        ProfileScreenContent(
            form =
                ProfileFormState(
                    name = "Ada Lovelace",
                    status = "Hiking this weekend",
                    nodeId = "8f3a2b1c9d4e",
                    alias = "Rustling Rabbit",
                    avatarHash = null,
                    contentFilteringEnabled = true,

                    isDirty = true,
                ),
            batteryExempt = false,
            onBack = {},
            onNameChange = {},
            onNameCommit = {},
            onStatusChange = {},
            onStatusCommit = {},
            onToggleContentFiltering = {},

            onPickPhoto = {},
            onClearPhoto = {},
            onAllowBattery = {},
            onSave = {},
        )
    }

// A fresh install: no name yet (the generated alias shows as the placeholder), nothing to save.
@Preview(showBackground = true)
@Composable
fun ProfileScreenNewUserPreview() =
    SwarSetuPreview {
        ProfileScreenContent(
            form =
                ProfileFormState(
                    name = "",
                    status = "",
                    nodeId = "8f3a2b1c9d4e",
                    alias = "Rustling Rabbit",
                    avatarHash = null,
                    contentFilteringEnabled = true,

                    isDirty = false,
                ),
            batteryExempt = true,
            onBack = {},
            onNameChange = {},
            onNameCommit = {},
            onStatusChange = {},
            onStatusCommit = {},
            onToggleContentFiltering = {},

            onPickPhoto = {},
            onClearPhoto = {},
            onAllowBattery = {},
            onSave = {},
        )
    }

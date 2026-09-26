package app.swarsetu.ui.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.swarsetu.R
import app.swarsetu.ui.hasAllMeshPermissions
import app.swarsetu.ui.hasBleHardware
import app.swarsetu.ui.hasWifiAwareHardware
import app.swarsetu.ui.i18n.UiTextKey
import app.swarsetu.ui.i18n.uiText
import app.swarsetu.ui.preview.SwarSetuPreview
import app.swarsetu.ui.requestIgnoreBatteryOptimizations
import app.swarsetu.ui.requiredMeshPermissions

/**
 * First-run gate: explains why the mesh needs its nearby-Wi-Fi + Bluetooth + notification permissions
 * and (optionally) battery exemption, requests them, then hands off to the chat once granted. The mesh
 * can start even if a permission was denied — it just degrades. Only on hardware with **neither** Wi-Fi
 * Aware nor Bluetooth LE does it show a clear "unsupported" notice (but still lets the user in).
 *
 * This stateful wrapper owns the Android-only pieces (hardware/permission probes, the permission
 * launcher, the battery-exemption intent); [OnboardingScreenContent] is the previewable layout.
 */
@Composable
fun OnboardingScreen(onReady: () -> Unit) {
    val context = LocalContext.current
    // The mesh runs on either radio plane, so a device with Wi-Fi Aware OR Bluetooth LE can participate.
    val meshSupported = remember { hasWifiAwareHardware(context) || hasBleHardware(context) }
    var granted by remember { mutableStateOf(hasAllMeshPermissions(context)) }

    val launcher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) {
            granted = hasAllMeshPermissions(context)
        }

    OnboardingScreenContent(
        meshSupported = meshSupported,
        granted = granted,
        onGrantPermissions = { launcher.launch(requiredMeshPermissions()) },
        onAllowBattery = { requestIgnoreBatteryOptimizations(context) },
        onReady = onReady,
    )
}

@Composable
internal fun OnboardingScreenContent(
    meshSupported: Boolean,
    granted: Boolean,
    onGrantPermissions: () -> Unit,
    onAllowBattery: () -> Unit,
    onReady: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = uiText(UiTextKey.ONBOARDING_WELCOME_TITLE),
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = uiText(UiTextKey.ONBOARDING_WELCOME_DESC),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 24.dp),
            )

            if (!meshSupported) {
                Text(
                    text = uiText(UiTextKey.ONBOARDING_UNSUPPORTED),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            }

            Button(
                onClick = onGrantPermissions,
                modifier = Modifier.fillMaxWidth().testTag("onboarding_grant"),
            ) {
                Text(
                    if (granted) {
                        uiText(UiTextKey.ONBOARDING_PERMISSIONS_GRANTED)
                    } else {
                        uiText(UiTextKey.ONBOARDING_GRANT_PERMISSIONS)
                    },
                )
            }

            OutlinedButton(
                onClick = onAllowBattery,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
            ) {
                Text(uiText(UiTextKey.BATTERY_ALLOW_BUTTON))
            }

            Button(
                onClick = onReady,
                enabled = granted,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp)
                        .testTag("onboarding_start"),
            ) {
                Text(uiText(UiTextKey.ONBOARDING_START))
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun OnboardingScreenPreview() =
    SwarSetuPreview {
        OnboardingScreenContent(
            meshSupported = true,
            granted = false,
            onGrantPermissions = {},
            onAllowBattery = {},
            onReady = {},
        )
    }

@Preview(showBackground = true)
@Composable
fun OnboardingScreenGrantedPreview() =
    SwarSetuPreview {
        OnboardingScreenContent(
            meshSupported = true,
            granted = true,
            onGrantPermissions = {},
            onAllowBattery = {},
            onReady = {},
        )
    }

@Preview(showBackground = true)
@Composable
fun OnboardingScreenUnsupportedPreview() =
    SwarSetuPreview {
        OnboardingScreenContent(
            meshSupported = false,
            granted = false,
            onGrantPermissions = {},
            onAllowBattery = {},
            onReady = {},
        )
    }

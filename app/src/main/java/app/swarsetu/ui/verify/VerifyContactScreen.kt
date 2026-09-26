package app.swarsetu.ui.verify

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.swarsetu.R
import app.swarsetu.ui.i18n.UiTextKey
import app.swarsetu.ui.i18n.uiText
import app.swarsetu.ui.preview.SwarSetuPreview
import app.swarsetu.ui.scan.QrScanner
import org.koin.androidx.compose.koinViewModel

/**
 * Standalone "Verify contact" screen (chat-list overflow → Verify contact). Shows this device's identity
 * QR and scans another's, so two people can verify each other's keys — and add each other as contacts —
 * even before they've ever chatted (and thus before a profile exists to hang [app.swarsetu.ui.profile
 * .ProfileDetailsScreen]'s verification section on). Reuses the shared [EncryptionSection] in its
 * no-bound-peer (standalone) mode; scanning a code pins + verifies the peer via [VerifyContactViewModel].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VerifyContactScreen(
    onBack: () -> Unit,
    viewModel: VerifyContactViewModel = koinViewModel(),
) {
    val myQrPayload by viewModel.myQrPayload.collectAsStateWithLifecycle()
    val scanResult by viewModel.scanResult.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val addedMessage = uiText(UiTextKey.VERIFY_SUCCESS)
    val mismatchMessage = uiText(UiTextKey.VERIFY_MISMATCH)
    val selfMessage = uiText(UiTextKey.VERIFY_SELF)
    val invalidMessage = uiText(UiTextKey.VERIFY_MISMATCH)
    LaunchedEffect(scanResult) {
        when (scanResult) {
            VerifyResult.VERIFIED -> snackbarHostState.showSnackbar(addedMessage)
            VerifyResult.MISMATCH -> snackbarHostState.showSnackbar(mismatchMessage)
            VerifyResult.SELF -> snackbarHostState.showSnackbar(selfMessage)
            VerifyResult.INVALID -> snackbarHostState.showSnackbar(invalidMessage)
            null -> Unit
        }
        if (scanResult != null) viewModel.consumeScanResult()
    }

    // The scanner takes over the whole screen rather than launching an Activity — see [QrScanner].
    var scanning by remember { mutableStateOf(false) }
    if (scanning) {
        QrScanner(
            onResult = {
                scanning = false
                viewModel.onScanned(it)
            },
            onCancel = { scanning = false },
        )
    } else {
        VerifyContactScreenContent(
            myQrPayload = myQrPayload,
            snackbarHostState = snackbarHostState,
            onBack = onBack,
            onScan = { scanning = true },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VerifyContactScreenContent(
    myQrPayload: String?,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onScan: () -> Unit,
) {
    Scaffold(
        modifier = Modifier.testTag("screen_verify"),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(uiText(UiTextKey.VERIFY_CONTACT)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = uiText(UiTextKey.CD_BACK),
                        )
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
        ) {
            EncryptionSection(
                myQrPayload = myQrPayload,
                peer = null,
                onScan = onScan,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun VerifyContactScreenPreview() =
    SwarSetuPreview {
        VerifyContactScreenContent(
            myQrPayload = "knit-id:v1:me:bundle",
            snackbarHostState = remember { SnackbarHostState() },
            onBack = {},
            onScan = {},
        )
    }

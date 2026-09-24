package app.swarsetu.ui.calling

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.swarsetu.calling.CallStatus
import org.koin.androidx.compose.koinViewModel

@Composable
fun CallScreen(
    peerId: String,
    onNavigateBack: () -> Unit,
    viewModel: CallViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(peerId) {
        // Start call with defaults (assuming Hindi -> English for now, would be configurable)
        viewModel.startCall(peerId, "hi", "en")
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.endCall()
        }
    }

    Scaffold(
        topBar = {
            @OptIn(ExperimentalMaterial3Api::class)
            TopAppBar(
                title = { Text("Walkie-Talkie Call") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Text("Back") // Placeholder for icon
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Incoming Call Text
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    text = "Peer's Voice (${state.targetLanguage}):",
                    style = MaterialTheme.typography.labelMedium
                )
                Text(
                    text = state.translatedIncomingText.ifBlank { "Waiting for incoming voice..." },
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (state.translatedIncomingText.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (state.incomingText.isNotBlank()) {
                    Text(
                        text = "Original: ${state.incomingText}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Outgoing Call Text
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalAlignment = Alignment.End
            ) {
                Text(
                    text = "Your Voice (${state.sourceLanguage}):",
                    style = MaterialTheme.typography.labelMedium
                )
                Text(
                    text = state.outgoingText.ifBlank { "Hold to talk..." },
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (state.outgoingText.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                )
            }

            // PTT Button
            Button(
                onClick = {}, // Handled by pointerInput
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                viewModel.startListening()
                                tryAwaitRelease()
                                viewModel.stopListening()
                            }
                        )
                    }
            ) {
                Text(if (state.status == CallStatus.LISTENING) "Listening..." else "Hold to Talk")
            }
        }
    }
}

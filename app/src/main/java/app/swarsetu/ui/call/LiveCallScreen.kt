package app.swarsetu.ui.call

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.swarsetu.R
import app.swarsetu.call.CallEndReason
import app.swarsetu.call.CallSession
import app.swarsetu.call.CallState
import app.swarsetu.call.CallTranscriptItem
import app.swarsetu.ui.i18n.UiTextKey
import app.swarsetu.ui.i18n.uiText
import org.koin.androidx.compose.koinViewModel

@Composable
fun LiveCallScreen(
    peerId: String,
    peerName: String,
    onBack: () -> Unit,
    viewModel: LiveCallViewModel = koinViewModel(),
) {
    val callState by viewModel.callState.collectAsStateWithLifecycle()
    val isModelLoading by viewModel.isModelLoading.collectAsStateWithLifecycle()
    val session by viewModel.currentSession.collectAsStateWithLifecycle()
    val isMuted by viewModel.isMuted.collectAsStateWithLifecycle()
    val isSpeakerOn by viewModel.isSpeakerOn.collectAsStateWithLifecycle()
    val callDuration by viewModel.callDuration.collectAsStateWithLifecycle()
    val localAmplitude by viewModel.localAmplitude.collectAsStateWithLifecycle()
    val isProcessing by viewModel.isProcessing.collectAsStateWithLifecycle()

    // Auto-initiate call when entering if IDLE and no session exists
    LaunchedEffect(peerId) {
        if (callState == CallState.IDLE && session == null) {
            viewModel.initiateCall(peerId, peerName)
        }
    }

    // Track whether the remote side ended the call (for "ended the call" banner)
    var remoteHungUp by remember { mutableStateOf(false) }
    val prevCallState = remember { mutableStateOf(callState) }

    // Close screen when call ends; detect remote hangup
    LaunchedEffect(callState, session?.endReason) {
        if (session?.endReason == CallEndReason.REMOTE_HANGUP ||
            (prevCallState.value == CallState.CONNECTED && callState == CallState.ENDED)
        ) {
            remoteHungUp = true
        }
        
        val wasActive = prevCallState.value != CallState.IDLE
        prevCallState.value = callState
        
        if (callState == CallState.ENDED || callState == CallState.FAILED) {
            kotlinx.coroutines.delay(500)
            onBack()
        } else if (callState == CallState.IDLE && wasActive) {
            onBack()
        }
    }

    // Show full-screen WhatsApp-style incoming call UI for callee
    if (callState == CallState.INCOMING) {
        IncomingCallScreen(
            callerName = session?.peerName ?: peerName,
            onAccept = { viewModel.acceptCall() },
            onReject = { viewModel.rejectCall() },
        )
        return
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (callState == CallState.CONNECTED || callState == CallState.RINGING) 1.08f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseScale",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0F172A),
                        Color(0xFF1E293B),
                        Color(0xFF0F172A),
                    )
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Top Bar: Back & Security Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        if (callState.isActive) viewModel.endCall() else onBack()
                    },
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF334155).copy(alpha = 0.6f)),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = uiText(UiTextKey.CD_BACK),
                        tint = Color.White,
                    )
                }

                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFF1E293B).copy(alpha = 0.8f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF475569)),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = null,
                            tint = Color(0xFF10B981),
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = uiText(UiTextKey.CALL_TITLE),
                            color = Color(0xFF94A3B8),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Avatar with animated pulse
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(120.dp)
                    .scale(pulseScale),
            ) {
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFF3B82F6).copy(alpha = 0.4f),
                                    Color(0xFF1D4ED8).copy(alpha = 0.1f),
                                    Color.Transparent,
                                )
                            )
                        )
                )
                Box(
                    modifier = Modifier
                        .size(88.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF3B82F6))
                        .border(3.dp, Color(0xFF60A5FA), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = (session?.peerName ?: peerName).take(1).uppercase(),
                        color = Color.White,
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Peer Name
            Text(
                text = session?.peerName ?: peerName,
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Call State & Timer
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                if (callState == CallState.CONNECTING || callState == CallState.ACCEPTING ||
                    callState == CallState.OUTGOING || callState == CallState.OUTGOING_REQUESTED ||
                    callState == CallState.RECONNECTING || (callState == CallState.CONNECTED && isModelLoading)) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        color = Color(0xFF60A5FA),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = when {
                        callState == CallState.CONNECTED && isModelLoading -> "Loading STT Model..."
                        callState == CallState.CONNECTED -> callDuration
                        callState == CallState.IDLE -> uiText(UiTextKey.CALL_ENDED)
                        callState == CallState.OUTGOING_REQUESTED || callState == CallState.OUTGOING -> uiText(UiTextKey.CALL_OUTGOING)
                        callState == CallState.INCOMING -> uiText(UiTextKey.CALL_INCOMING)
                        callState == CallState.ACCEPTING || callState == CallState.CONNECTING -> uiText(UiTextKey.CALL_CONNECTING)
                        callState == CallState.RINGING -> uiText(UiTextKey.CALL_CALLING)
                        callState == CallState.RECONNECTING -> "Reconnecting..."
                        callState == CallState.ENDING || callState == CallState.ENDED -> uiText(UiTextKey.CALL_ENDED)
                        callState == CallState.FAILED -> uiText(UiTextKey.CALL_FAILED)
                        else -> ""
                    },
                    color = if (callState == CallState.CONNECTED && !isModelLoading) Color(0xFF34D399) else Color(0xFF94A3B8),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Dynamic Waveform Audio Visualizer
            if (callState == CallState.CONNECTED) {
                app.swarsetu.ui.voice.DynamicWaveform(
                    amplitude = localAmplitude,
                    barColor = if (isMuted) Color.Gray else Color(0xFF60A5FA),
                    barCount = 7,
                    isProcessing = isProcessing,
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Language Translation Direction Badge
            session?.let { activeSession ->
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xFF1E293B),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF3B82F6).copy(alpha = 0.5f)),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Translate,
                            contentDescription = null,
                            tint = Color(0xFF60A5FA),
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${activeSession.localSourceLanguage.uppercase()} ⇄ ${activeSession.localTargetLanguage.uppercase()}",
                            color = Color(0xFFE2E8F0),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Latency HUD Metrics
            session?.metrics?.collectAsStateWithLifecycle()?.value?.let { m ->
                if (callState == CallState.CONNECTED && m.totalTranslatedMs > 0) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF0F172A).copy(alpha = 0.7f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155)),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Speed,
                                contentDescription = null,
                                tint = Color(0xFFFBBF24),
                                modifier = Modifier.size(14.dp),
                            )
                            Text(
                                text = "STT: ${m.lastSttLatencyMs}ms | Trans: ${m.lastTranslationLatencyMs}ms | Total: ${m.totalTranslatedMs}ms",
                                color = Color(0xFFCBD5E1),
                                fontSize = 11.sp,
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Live Transcript Stream
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF1E293B).copy(alpha = 0.5f))
                    .border(1.dp, Color(0xFF334155), RoundedCornerShape(16.dp))
                    .padding(8.dp),
            ) {
                val transcripts = session?.transcripts?.collectAsStateWithLifecycle()?.value ?: emptyList()
                val listState = rememberLazyListState()

                LaunchedEffect(transcripts.size) {
                    if (transcripts.isNotEmpty()) {
                        listState.animateScrollToItem(transcripts.size - 1)
                    }
                }

                if (transcripts.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Filled.GraphicEq,
                                contentDescription = null,
                                tint = Color(0xFF475569),
                                modifier = Modifier.size(36.dp),
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = if (callState == CallState.CONNECTED) uiText(UiTextKey.CALL_LIVE_TRANSLATION_ACTIVE)
                                else uiText(UiTextKey.CALL_CONNECTING),
                                color = Color(0xFF64748B),
                                fontSize = 13.sp,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(transcripts, key = { it.id }) { item ->
                            TranscriptBubble(item)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Bottom Call Action Controls (INCOMING is shown as full-screen via IncomingCallScreen)
            if (callState != CallState.INCOMING) {
                // Active / Outgoing Call Controls: Mute, Speaker, Hangup
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CallActionButton(
                        icon = if (isMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                        label = if (isMuted) uiText(UiTextKey.CALL_UNMUTE) else uiText(UiTextKey.CALL_MUTE),
                        backgroundColor = if (isMuted) Color(0xFFEF4444) else Color(0xFF334155),
                        tint = Color.White,
                        onClick = { viewModel.toggleMute() },
                    )

                    CallActionButton(
                        icon = Icons.Filled.CallEnd,
                        label = uiText(UiTextKey.CALL_END),
                        backgroundColor = Color(0xFFDC2626),
                        tint = Color.White,
                        onClick = { viewModel.endCall() },
                        size = 64.dp,
                    )

                    CallActionButton(
                        icon = if (isSpeakerOn) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                        label = if (isSpeakerOn) uiText(UiTextKey.CALL_EARPIECE) else uiText(UiTextKey.CALL_SPEAKER),
                        backgroundColor = if (isSpeakerOn) Color(0xFF3B82F6) else Color(0xFF334155),
                        tint = Color.White,
                        onClick = { viewModel.toggleSpeaker() },
                    )
                }
            }
        }

        // "X ended the call" overlay when remote peer hangs up
        if (remoteHungUp && (callState == CallState.ENDED || callState == CallState.ENDING)) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xCC000000)),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xFF1E293B),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.6f)),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 36.dp, vertical = 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CallEnd,
                            contentDescription = null,
                            tint = Color(0xFFEF4444),
                            modifier = Modifier.size(44.dp),
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = session?.peerName ?: peerName,
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "ended the call",
                            color = Color(0xFF94A3B8),
                            fontSize = 15.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun IncomingCallScreen(
    callerName: String,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "incoming_ring")
    val ringScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.18f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "ringScale",
    )
    val ringAlpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.65f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "ringAlpha",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF0B1A2C), Color(0xFF0F2744), Color(0xFF0B1A2C))
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Spacer(modifier = Modifier.height(64.dp))

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xFF10B981).copy(alpha = 0.12f),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = null,
                            tint = Color(0xFF10B981),
                            modifier = Modifier.size(12.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "SwarSetu Encrypted Call",
                            color = Color(0xFF34D399),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "Incoming Call",
                    color = Color(0xFF64748B),
                    fontSize = 15.sp,
                    letterSpacing = 2.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(modifier = Modifier.height(28.dp))

                Box(contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .size(180.dp)
                            .scale(ringScale)
                            .clip(CircleShape)
                            .background(Color(0xFF10B981).copy(alpha = ringAlpha * 0.18f)),
                    )
                    Box(
                        modifier = Modifier
                            .size(148.dp)
                            .scale(ringScale * 0.92f)
                            .clip(CircleShape)
                            .background(Color(0xFF10B981).copy(alpha = ringAlpha * 0.28f)),
                    )
                    Box(
                        modifier = Modifier
                            .size(110.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(Color(0xFF10B981), Color(0xFF047857))
                                )
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = callerName.take(1).uppercase(),
                            color = Color.White,
                            fontSize = 44.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(22.dp))
                Text(
                    text = callerName,
                    color = Color.White,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
            }

            Column(
                modifier = Modifier.padding(bottom = 52.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FloatingActionButton(
                            onClick = onReject,
                            modifier = Modifier.size(76.dp),
                            shape = CircleShape,
                            containerColor = Color(0xFFDC2626),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.CallEnd,
                                contentDescription = "Decline call",
                                tint = Color.White,
                                modifier = Modifier.size(34.dp),
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Decline",
                            color = Color(0xFF94A3B8),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FloatingActionButton(
                            onClick = onAccept,
                            modifier = Modifier.size(76.dp),
                            shape = CircleShape,
                            containerColor = Color(0xFF10B981),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Call,
                                contentDescription = "Accept call",
                                tint = Color.White,
                                modifier = Modifier.size(34.dp),
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Accept",
                            color = Color(0xFF94A3B8),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TranscriptBubble(item: CallTranscriptItem) {
    val isLocal = item.isLocal
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isLocal) Alignment.End else Alignment.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 14.dp,
                topEnd = 14.dp,
                bottomStart = if (isLocal) 14.dp else 2.dp,
                bottomEnd = if (isLocal) 2.dp else 14.dp,
            ),
            color = if (isLocal) Color(0xFF1E3A8A) else Color(0xFF334155),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                if (isLocal) Color(0xFF3B82F6).copy(alpha = 0.6f) else Color(0xFF475569)
            ),
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = item.speakerName,
                        color = if (isLocal) Color(0xFF93C5FD) else Color(0xFFFCD34D),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    if (item.totalLatencyMs != null && item.totalLatencyMs > 0) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${item.totalLatencyMs}ms",
                            color = Color(0xFF94A3B8),
                            fontSize = 10.sp,
                        )
                    }
                }
                if (item.originalText.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = item.originalText,
                        color = Color(0xFFE2E8F0),
                        fontSize = 14.sp,
                    )
                }
                if (item.translatedText.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Translate,
                            contentDescription = null,
                            tint = Color(0xFF34D399),
                            modifier = Modifier.size(12.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = item.translatedText,
                            color = Color(0xFF6EE7B7),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CallActionButton(
    icon: ImageVector,
    label: String,
    backgroundColor: Color,
    tint: Color = Color.White,
    size: androidx.compose.ui.unit.Dp = 52.dp,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(backgroundColor),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(size * 0.48f),
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = label,
            color = Color(0xFF94A3B8),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

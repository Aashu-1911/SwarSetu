package app.swarsetu.ui.chat.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.swarsetu.ui.i18n.UiTextKey
import app.swarsetu.ui.i18n.uiText
import app.swarsetu.ui.theme.BrandPrimaryDark
import app.swarsetu.ui.theme.BrandPrimaryLight
import app.swarsetu.ui.theme.SwarSetuTheme

@Composable
fun ChatComposer(
    textFieldState: TextFieldState,
    isRecording: Boolean,
    recordingDurationMs: Long,
    liveTranscriptionPreview: String,
    onSendText: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onCancelRecording: () -> Unit,
    onAttachmentClick: () -> Unit,
    replySnippet: @Composable (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    val accentColor = if (isDark) BrandPrimaryDark else BrandPrimaryLight
    val textLength = textFieldState.text.length
    val hasText = textLength > 0

    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 4.dp,
        modifier = modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            // Reply quote preview if any
            if (replySnippet != null) {
                replySnippet()
                Spacer(modifier = Modifier.height(4.dp))
            }

            if (isRecording) {
                // Recording Mode Overlay
                RecordingStateRow(
                    durationMs = recordingDurationMs,
                    previewText = liveTranscriptionPreview,
                    onCancel = onCancelRecording,
                    onSend = onStopRecording,
                    accentColor = accentColor,
                )
            } else {
                // Normal Input Composer Row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    // Attachment button
                    IconButton(
                        onClick = onAttachmentClick,
                        modifier = Modifier.size(38.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = uiText(UiTextKey.CD_ATTACH_PHOTO),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp),
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    // Rounded Input Text Field
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(1.dp, SwarSetuTheme.chatColors.composerBorder),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 40.dp, max = 120.dp),
                    ) {
                        Box(
                            contentAlignment = Alignment.CenterStart,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            if (!hasText) {
                                Text(
                                    text = uiText(UiTextKey.CHAT_MESSAGE_HINT),
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontSize = 15.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    ),
                                )
                            }

                            BasicTextField(
                                state = textFieldState,
                                textStyle = TextStyle(
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 15.sp,
                                ),
                                cursorBrush = SolidColor(accentColor),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // Mic / Send Dynamic Action Button
                    AnimatedContent(
                        targetState = hasText,
                        transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(150)) },
                        label = "actionBtn",
                    ) { typing ->
                        if (typing) {
                            Surface(
                                shape = CircleShape,
                                color = accentColor,
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .clickable { onSendText() },
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.Send,
                                        contentDescription = uiText(UiTextKey.ACTION_SEND),
                                        tint = if (isDark) Color(0xFF1F1604) else Color.White,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        } else {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                border = BorderStroke(1.dp, SwarSetuTheme.chatColors.composerBorder),
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .clickable { onStartRecording() },
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Mic,
                                        contentDescription = uiText(UiTextKey.CD_RECORD_VOICE),
                                        tint = accentColor,
                                        modifier = Modifier.size(22.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordingStateRow(
    durationMs: Long,
    previewText: String,
    onCancel: () -> Unit,
    onSend: () -> Unit,
    accentColor: Color,
    modifier: Modifier = Modifier,
) {
    val seconds = (durationMs / 1000) % 60
    val minutes = (durationMs / 1000) / 60
    val timeFormatted = String.format("%02d:%02d", minutes, seconds)

    val infiniteTransition = rememberInfiniteTransition(label = "recPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )

    Column(modifier = modifier.fillMaxWidth()) {
        if (previewText.isNotBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    text = "“$previewText”",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    maxLines = 2,
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            // Cancel button
            IconButton(
                onClick = onCancel,
                modifier = Modifier.size(38.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = uiText(UiTextKey.ACTION_CANCEL),
                    tint = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            // Pulsing recording indicator
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .scale(1.1f)
                    .clip(CircleShape)
                    .background(Color.Red.copy(alpha = pulseAlpha)),
            )

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = "${uiText(UiTextKey.CHAT_RECORDING)} $timeFormatted",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
            )

            Spacer(modifier = Modifier.weight(1f))

            // Stop / Send Button
            Surface(
                shape = CircleShape,
                color = accentColor,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable { onSend() },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = uiText(UiTextKey.ACTION_SEND),
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

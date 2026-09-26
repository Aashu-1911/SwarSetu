package app.swarsetu.ui.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.swarsetu.data.message.DeliveryPlane
import app.swarsetu.ui.chat.DeliveryStatus
import app.swarsetu.ui.i18n.UiTextKey
import app.swarsetu.ui.i18n.uiText
import app.swarsetu.ui.theme.BrandPrimaryDark
import app.swarsetu.ui.theme.BrandPrimaryLight
import app.swarsetu.ui.theme.SwarSetuTheme

@Composable
fun TranslatedVoiceMessageBubble(
    translatedText: String,
    originalText: String?,
    sourceLanguage: String?,
    targetLanguage: String?,
    isPlaying: Boolean,
    onPlayToggle: () -> Unit,
    isOutgoing: Boolean,
    timestampText: String,
    deliveryStatus: DeliveryStatus?,
    deliveryPlane: DeliveryPlane?,
    senderName: String? = null,
    modifier: Modifier = Modifier,
) {
    var isOriginalExpanded by remember { mutableStateOf(false) }
    val textColor = if (isOutgoing) SwarSetuTheme.chatColors.outgoingText else SwarSetuTheme.chatColors.incomingText
    val metaColor = if (isOutgoing) SwarSetuTheme.chatColors.outgoingMeta else SwarSetuTheme.chatColors.incomingMeta
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    val accentColor = if (isDark) BrandPrimaryDark else BrandPrimaryLight

    // Format language pair badge e.g. "Hindi → English"
    val languageBadgeText = remember(sourceLanguage, targetLanguage) {
        val src = sourceLanguage?.uppercase() ?: "HI"
        val tgt = targetLanguage?.uppercase() ?: "EN"
        "$src → $tgt"
    }

    Column(modifier = modifier.width(260.dp)) {
        if (!isOutgoing && !senderName.isNullOrBlank()) {
            Text(
                text = senderName,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = accentColor,
                ),
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }

        // Voice Message Header Tag
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(SwarSetuTheme.chatColors.voiceBadgeBackground)
                .padding(horizontal = 7.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = null,
                tint = SwarSetuTheme.chatColors.voiceBadgeText,
                modifier = Modifier.size(12.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "${uiText(UiTextKey.CHAT_VOICE_DESC)} • $languageBadgeText",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp,
                    color = SwarSetuTheme.chatColors.voiceBadgeText,
                ),
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Primary Translated Speech Text
        Text(
            text = translatedText,
            color = textColor,
            fontSize = 15.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.Normal,
            modifier = Modifier.fillMaxWidth(),
        )

        // Expandable Original Text
        if (!originalText.isNullOrBlank() && originalText != translatedText) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { isOriginalExpanded = !isOriginalExpanded }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = uiText(UiTextKey.CHAT_ORIGINAL_MESSAGE),
                    fontSize = 11.sp,
                    color = metaColor,
                    fontWeight = FontWeight.Medium,
                )
                Icon(
                    imageVector = if (isOriginalExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = metaColor,
                    modifier = Modifier.size(14.dp),
                )
            }

            AnimatedVisibility(visible = isOriginalExpanded) {
                Text(
                    text = "“$originalText”",
                    color = textColor.copy(alpha = 0.8f),
                    fontSize = 13.sp,
                    fontStyle = FontStyle.Italic,
                    lineHeight = 17.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp, bottom = 4.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Play / Audio Wave Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = accentColor,
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable { onPlayToggle() },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) uiText(UiTextKey.CHAT_PAUSE_VOICE) else uiText(UiTextKey.CHAT_PLAY_VOICE),
                        tint = if (isDark) Color(0xFF1F1604) else Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Subtle waveform bars
            WaveformIndicator(
                isPlaying = isPlaying,
                activeColor = SwarSetuTheme.chatColors.voiceWaveformActive,
                inactiveColor = SwarSetuTheme.chatColors.voiceWaveformInactive,
                modifier = Modifier.weight(1f),
            )

            Spacer(modifier = Modifier.width(8.dp))

            // Meta Timestamp & Status
            MessageMetaRow(
                timestampText = timestampText,
                isOutgoing = isOutgoing,
                deliveryStatus = deliveryStatus,
                deliveryPlane = deliveryPlane,
                metaColor = metaColor,
            )
        }
    }
}

@Composable
private fun WaveformIndicator(
    isPlaying: Boolean,
    activeColor: Color,
    inactiveColor: Color,
    modifier: Modifier = Modifier,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "wave")
    val waveAnim by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "waveHeight",
    )

    val barHeights = remember { listOf(0.4f, 0.7f, 0.9f, 0.5f, 0.8f, 0.6f, 1.0f, 0.7f, 0.4f, 0.6f, 0.8f, 0.5f) }

    Row(
        modifier = modifier.height(18.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        barHeights.forEachIndexed { index, baseHeight ->
            val animatedFraction = if (isPlaying) {
                (baseHeight * (0.5f + 0.5f * kotlin.math.sin(waveAnim * Math.PI.toFloat() + index * 0.5f))).coerceIn(0.2f, 1f)
            } else {
                baseHeight * 0.7f
            }

            Box(
                modifier = Modifier
                    .width(2.5.dp)
                    .height((18 * animatedFraction).dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(if (isPlaying) activeColor else inactiveColor),
            )
        }
    }
}

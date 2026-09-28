package app.swarsetu.ui.voice

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat

@Composable
fun DynamicWaveform(
    amplitude: Float,
    modifier: Modifier = Modifier,
    barColor: Color = MaterialTheme.colorScheme.error,
    barCount: Int = 7,
    isProcessing: Boolean = false,
) {
    // Smooth the amplitude changes using a spring animation
    val animatedAmplitude by animateFloatAsState(
        targetValue = amplitude.coerceIn(0f, 1f),
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "amplitudeAnimation"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "processing_transition")
    val processingPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2 * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Restart
        ),
        label = "processing_phase"
    )

    Row(
        modifier = modifier.height(24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        val mid = barCount / 2
        for (i in 0 until barCount) {
            val distance = kotlin.math.abs(i - mid).toFloat()
            val baseScale = 1f - (distance * 0.2f)
            
            val heightPercent = if (isProcessing) {
                // A sine wave that travels across the bars
                val offset = (i.toFloat() / barCount) * Math.PI.toFloat()
                val sine = (kotlin.math.sin(processingPhase + offset) + 1f) / 2f
                (0.2f + (sine * baseScale * 0.8f)).coerceIn(0.2f, 1f)
            } else {
                (0.2f + (animatedAmplitude * baseScale * 1.5f)).coerceIn(0.2f, 1f)
            }
            
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight(heightPercent)
                    .clip(CircleShape)
                    .background(barColor)
            )
        }
    }
}

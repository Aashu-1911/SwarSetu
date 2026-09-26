package app.swarsetu.ui.chat

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import app.swarsetu.R
import app.swarsetu.data.message.DeliveryPlane
import app.swarsetu.data.message.MessageEntity

/**
 * How far one of *our* messages has got, folded out of the three independent columns that record it:
 * [MessageEntity.pendingKey] (saved locally, never sealed — we don't hold the recipient's key yet),
 * [MessageEntity.received] (a delivery receipt came back), and nothing at all in between.
 *
 * A received message has no status of its own — delivery isn't ours to report — so [deliveryLabel]
 * takes `mine` and describes *arrival* instead for the other direction.
 */
enum class DeliveryStatus {
    /** Written locally but never sent: the recipient's public key hasn't reached us yet. */
    Pending,

    /** Flooded, no receipt back yet (or a broadcast/group send, which never gets one). */
    Sent,

    /** A delivery receipt came back. */
    Delivered,

    ;

    companion object {
        /** The status the stored row implies. Order matters: `pendingKey` outranks the (always false) tick. */
        fun of(message: MessageEntity): DeliveryStatus =
            when {
                message.pendingKey -> Pending
                message.received -> Delivered
                else -> Sent
            }
    }
}

/**
 * A resolved delivery line: the string resource plus any format arguments it takes. A plain `@StringRes`
 * no longer suffices now that the group form carries a count, and keeping the arguments beside the id (
 * rather than formatting here) leaves [deliveryLabel] a pure function the JVM tests can assert on
 * directly. Format it with [resolve].
 */
data class DeliveryText(
    @param:StringRes val resId: Int,
    val args: List<Any> = emptyList(),
)

/** Formats a [DeliveryText] against the current resources. */
@Composable
fun DeliveryText.resolve(): String = stringResource(resId, *args.toTypedArray())

/**
 * The user-facing line for a message's delivery [status] on [plane].
 */
fun deliveryLabel(
    status: DeliveryStatus,
    plane: DeliveryPlane,
    mine: Boolean,
    delivered: Int? = null,
    total: Int? = null,
): DeliveryText =
    when {
        !mine && plane == DeliveryPlane.Internet -> {
            DeliveryText(R.string.chat_status_arrived_internet)
        }

        !mine -> {
            DeliveryText(R.string.chat_status_arrived_nearby)
        }

        status == DeliveryStatus.Pending -> {
            DeliveryText(R.string.chat_status_pending_key)
        }

        status == DeliveryStatus.Delivered && delivered != null && delivered > 0 && total != null && total > 0 -> {
            DeliveryText(R.string.chat_status_delivered_count, listOf(delivered, total))
        }

        status == DeliveryStatus.Delivered && plane == DeliveryPlane.Internet -> {
            DeliveryText(R.string.chat_status_delivered_internet)
        }

        status == DeliveryStatus.Delivered -> {
            DeliveryText(R.string.chat_status_delivered)
        }

        else -> {
            DeliveryText(R.string.chat_status_sent)
        }
    }

/**
 * The tick glyph for one of *our* messages at [status]: a clock while it can't be sent yet, one check once
 * it's flooded, two once a receipt is back. The single definition shared by the chat-list row and the
 * message-details screen, so the two can't drift apart. Pair it with [deliveryLabel] for the words —
 * the glyph alone never carries the meaning.
 */
fun deliveryIcon(status: DeliveryStatus): ImageVector =
    when (status) {
        DeliveryStatus.Pending -> Icons.Filled.Schedule
        DeliveryStatus.Sent -> Icons.Filled.Done
        DeliveryStatus.Delivered -> Icons.Filled.DoneAll
    }

package app.swarsetu.ui.chat

import app.swarsetu.R
import app.swarsetu.data.message.DeliveryPlane
import app.swarsetu.data.message.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The one definition of "how far did it get", shared by the chat bubble's tick description and the
 * message-details screen's visible line. The rule worth pinning: the plane only qualifies a delivery that
 * actually happened — an un-acked send names no plane, however it was eventually going to travel.
 */
class DeliveryStatusTest {
    @Test
    fun `pendingKey outranks the tick`() {
        assertEquals(DeliveryStatus.Pending, DeliveryStatus.of(message(pendingKey = true)))
        assertEquals(DeliveryStatus.Sent, DeliveryStatus.of(message()))
        assertEquals(DeliveryStatus.Delivered, DeliveryStatus.of(message(received = true)))
    }

    @Test
    fun `our own message reports sent, delivered, or delivered over the Internet`() {
        assertEquals(
            DeliveryText(R.string.chat_status_pending_key),
            deliveryLabel(DeliveryStatus.Pending, DeliveryPlane.Unknown, mine = true),
        )
        assertEquals(
            DeliveryText(R.string.chat_status_sent),
            deliveryLabel(DeliveryStatus.Sent, DeliveryPlane.Unknown, mine = true),
        )
        assertEquals(
            DeliveryText(R.string.chat_status_delivered),
            deliveryLabel(DeliveryStatus.Delivered, DeliveryPlane.Nearby, mine = true),
        )
        assertEquals(
            DeliveryText(R.string.chat_status_delivered_internet),
            deliveryLabel(DeliveryStatus.Delivered, DeliveryPlane.Internet, mine = true),
        )
    }

    @Test
    fun `a group send reports the ratio the tick cannot — but only once a receipt is recorded`() {
        assertEquals(
            DeliveryText(R.string.chat_status_delivered_count, listOf(2, 3)),
            deliveryLabel(DeliveryStatus.Delivered, DeliveryPlane.Nearby, mine = true, delivered = 2, total = 3),
        )
        assertEquals(
            DeliveryText(R.string.chat_status_delivered_count, listOf(3, 3)),
            deliveryLabel(DeliveryStatus.Delivered, DeliveryPlane.Internet, mine = true, delivered = 3, total = 3),
        )
        assertEquals(
            DeliveryText(R.string.chat_status_delivered),
            deliveryLabel(DeliveryStatus.Delivered, DeliveryPlane.Nearby, mine = true, delivered = 0, total = 3),
        )
        assertEquals(
            DeliveryText(R.string.chat_status_sent),
            deliveryLabel(DeliveryStatus.Sent, DeliveryPlane.Unknown, mine = true, delivered = 0, total = 3),
        )
    }

    @Test
    fun `callers with no roster to hand get exactly the old wording`() {
        assertEquals(
            deliveryLabel(DeliveryStatus.Delivered, DeliveryPlane.Nearby, mine = true),
            deliveryLabel(DeliveryStatus.Delivered, DeliveryPlane.Nearby, mine = true, delivered = null, total = null),
        )
        assertEquals(
            DeliveryText(R.string.chat_status_delivered_internet),
            deliveryLabel(DeliveryStatus.Delivered, DeliveryPlane.Internet, mine = true, delivered = 2, total = 0),
        )
    }

    @Test
    fun `an un-acked send names no plane even when one is recorded`() {
        assertEquals(
            DeliveryText(R.string.chat_status_sent),
            deliveryLabel(DeliveryStatus.Sent, DeliveryPlane.Internet, mine = true),
        )
    }

    @Test
    fun `a received message describes its own arrival, not our delivery`() {
        assertEquals(
            DeliveryText(R.string.chat_status_arrived_internet),
            deliveryLabel(DeliveryStatus.Delivered, DeliveryPlane.Internet, mine = false),
        )
        for (plane in listOf(DeliveryPlane.Nearby, DeliveryPlane.Bluetooth, DeliveryPlane.WifiAware, DeliveryPlane.Unknown)) {
            assertEquals(
                DeliveryText(R.string.chat_status_arrived_nearby),
                deliveryLabel(DeliveryStatus.Delivered, plane, mine = false),
            )
        }
    }

    private fun message(
        received: Boolean = false,
        pendingKey: Boolean = false,
    ) = MessageEntity(
        id = "m1",
        senderId = "me",
        body = "hi",
        sentAt = 0L,
        received = received,
        pendingKey = pendingKey,
    )
}

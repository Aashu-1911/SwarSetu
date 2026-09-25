package app.swarsetu.call

import java.util.PriorityQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * Jitter Buffer for live call audio packets.
 * Handles out-of-order delivery, packet loss tracking, and jitter smoothing.
 */
class JitterBuffer(
    private val targetDelayMs: Long = 80L,
    private val maxBufferCapacity: Int = 100,
) {
    private val queue = PriorityQueue<CallPacket>(compareBy { it.sequenceNumber })
    private val lock = Any()

    private var lastPlayedSeq: Long = -1L
    private val packetsReceived = AtomicLong(0)
    private val packetsDropped = AtomicLong(0)
    private val packetsLost = AtomicLong(0)

    // Jitter estimation (RFC 3550 standard algorithm)
    private var estimatedJitterMs: Double = 0.0
    private var lastTransitTimeMs: Long = -1L

    val currentQueueSize: Int
        get() = synchronized(lock) { queue.size }

    val jitterMs: Double
        get() = synchronized(lock) { estimatedJitterMs }

    val lossRatePercent: Float
        get() {
            val total = packetsReceived.get() + packetsLost.get()
            if (total == 0L) return 0f
            return (packetsLost.get().toFloat() / total.toFloat()) * 100f
        }

    /**
     * Pushes a received packet into the jitter buffer.
     * Computes RFC 3550 transit jitter and sequence gaps.
     */
    fun push(packet: CallPacket, localArrivalTimestampMs: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            packetsReceived.incrementAndGet()

            // Update RFC 3550 Jitter estimate: D(i, j) = (R_j - S_j) - (R_i - S_i)
            val transitTime = localArrivalTimestampMs - packet.timestampMs
            if (lastTransitTimeMs != -1L) {
                val diff = abs(transitTime - lastTransitTimeMs)
                estimatedJitterMs += (diff - estimatedJitterMs) / 16.0
            }
            lastTransitTimeMs = transitTime

            if (lastPlayedSeq != -1L && packet.sequenceNumber <= lastPlayedSeq) {
                // Late packet arriving after already played sequence
                packetsDropped.incrementAndGet()
                return
            }

            if (queue.size >= maxBufferCapacity) {
                // Buffer overflow: evict oldest
                val evicted = queue.poll()
                if (evicted != null) {
                    lastPlayedSeq = maxOf(lastPlayedSeq, evicted.sequenceNumber)
                    packetsDropped.incrementAndGet()
                }
            }

            queue.offer(packet)
        }
    }

    /**
     * Pops the next ready packet from the jitter buffer if playout delay has elapsed.
     */
    fun popReady(nowMs: Long = System.currentTimeMillis()): CallPacket? {
        synchronized(lock) {
            val peek = queue.peek() ?: return null
            val age = nowMs - peek.timestampMs

            if (age >= targetDelayMs || queue.size >= maxBufferCapacity / 2) {
                val packet = queue.poll()
                if (packet != null) {
                    lastPlayedSeq = packet.sequenceNumber
                }
                return packet
            }
            return null
        }
    }

    /**
     * Clears all packets from the buffer.
     */
    fun clear() {
        synchronized(lock) {
            queue.clear()
            lastPlayedSeq = -1L
            lastTransitTimeMs = -1L
            estimatedJitterMs = 0.0
            packetsReceived.set(0)
            packetsDropped.set(0)
            packetsLost.set(0)
        }
    }
}

package app.swarsetu.call

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Utility for discovering local network addresses suitable for Wi-Fi Aware / Wi-Fi direct socket transport.
 */
object CallNetworkUtils {
    /**
     * Discovers the best non-loopback IP address of this device for direct socket communication.
     * Prefers:
     * 1. Wi-Fi Aware NDP ("aware_data*", "nan*")
     * 2. Wi-Fi ("wlan*", "p2p*", "ap*") IPv4
     * 3. Any other active non-loopback IPv4
     * 4. Any other active non-loopback IPv6
     */
    fun getLocalIpAddress(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()

            // 1. Wi-Fi Aware NDP interface
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                val name = iface.name.lowercase()
                if (name.startsWith("aware") || name.startsWith("nan")) {
                    for (addr in iface.inetAddresses) {
                        if (!addr.isLoopbackAddress) {
                            val host = addr.hostAddress ?: continue
                            return host.substringBefore('%')
                        }
                    }
                }
            }

            // 2. Wi-Fi interface IPv4
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                val name = iface.name.lowercase()
                if (name.startsWith("wlan") || name.startsWith("p2p") || name.startsWith("ap")) {
                    for (addr in iface.inetAddresses) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            return addr.hostAddress
                        }
                    }
                }
            }

            // 3. Any active IPv4
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }

            // 4. Any active IPv6
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses) {
                    if (!addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        return host.substringBefore('%')
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}

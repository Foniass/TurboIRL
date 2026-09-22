package fr.turboirl.app

import java.net.Inet4Address
import java.net.NetworkInterface

object NetUtil {

    data class LocalAddress(val iface: String, val ip: String)

    // Cellular / VPN / internal interfaces: the camera can never reach those.
    private val IGNORED = listOf("rmnet", "ccmni", "clat", "v4-", "tun", "dummy", "lo", "ip6", "sit", "p2p")

    // Usual soft AP interface names (MediaTek: ap0, Qualcomm: wlan1/swlan0/softap0)
    private val HOTSPOT = listOf("ap", "swlan", "softap", "wlan1", "wlan2")

    fun isHotspot(iface: String): Boolean = HOTSPOT.any { iface.startsWith(it) }

    /** IPv4 addresses the camera could stream to, most likely hotspot interface first. */
    fun localAddresses(): List<LocalAddress> {
        val result = ArrayList<LocalAddress>()
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                if (IGNORED.any { nif.name.startsWith(it) }) continue
                for (addr in nif.inetAddresses) {
                    if (addr is Inet4Address && addr.isSiteLocalAddress) {
                        result.add(LocalAddress(nif.name, addr.hostAddress.orEmpty()))
                    }
                }
            }
        } catch (_: Exception) {
        }
        return result.sortedBy { a -> if (isHotspot(a.iface)) 0 else 1 }
    }
}

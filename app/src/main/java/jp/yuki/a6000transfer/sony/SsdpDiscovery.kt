package jp.yuki.a6000transfer.sony

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface

data class SsdpResult(
    val location: String,
    val st: String,
    val usn: String,
    val server: String,
    val raw: String,
)

object SsdpDiscovery {
    const val MULTICAST_ADDR = "239.255.255.250"
    const val MULTICAST_PORT = 1900
    const val SONY_ST = "urn:schemas-sony-com:service:ScalarWebAPI:1"

    private fun buildMSearch(st: String, mx: Int = 3): ByteArray {
        return (
            "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: $MULTICAST_ADDR:$MULTICAST_PORT\r\n" +
                "MAN: \"ns=01; ns=01\"\r\n" +
                "MX: $mx\r\n" +
                "ST: $st\r\n" +
                "USER-AGENT: a6000-transfer-diag/0.1\r\n" +
                "\r\n"
            ).toByteArray(Charsets.UTF_8)
    }

    private fun parseResponse(text: String): SsdpResult? {
        if (!text.startsWith("HTTP/1.1 200")) return null
        val headers = mutableMapOf<String, String>()
        text.lineSequence().drop(1).forEach { line ->
            val idx = line.indexOf(':')
            if (idx > 0) {
                headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
        }
        val location = headers["location"] ?: return null
        return SsdpResult(
            location = location,
            st = headers["st"] ?: "",
            usn = headers["usn"] ?: "",
            server = headers["server"] ?: "",
            raw = text,
        )
    }

    /** MulticastLockを取得してM-SEARCHを送り、応答を収集する */
    suspend fun discover(
        context: Context,
        st: String = SONY_ST,
        waitMs: Long = 6000,
        unicastTargets: List<String> = listOf("10.0.0.1"),
    ): List<SsdpResult> = withContext(Dispatchers.IO) {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("a6000-ssdp").apply {
            setReferenceCounted(true)
            acquire()
        }
        try {
            withTimeoutOrNull(waitMs + 3000) {
                // wlan0を明示指定（バインド中ネットワーク経由を確実にする）
                val wlan = try {
                    NetworkInterface.getNetworkInterfaces()?.toList()
                        ?.firstOrNull { it.name == "wlan0" && it.isUp }
                } catch (_: Exception) {
                    null
                }
                val socket = MulticastSocket(null).apply {
                    reuseAddress = true
                    soTimeout = 1500
                    bind(InetSocketAddress(0))
                    if (wlan != null) {
                        try {
                            setNetworkInterface(wlan)
                            joinGroup(
                                InetSocketAddress(InetAddress.getByName(MULTICAST_ADDR), MULTICAST_PORT),
                                wlan,
                            )
                        } catch (_: Exception) {
                        }
                    }
                }
                socket.use { sock ->
                    val group = InetAddress.getByName(MULTICAST_ADDR)
                    val out = buildMSearch(st)
                    repeat(3) {
                        // マルチキャスト宛
                        sock.send(DatagramPacket(out, out.size, group, MULTICAST_PORT))
                        // カメラGW候補へのユニキャスト宛（応答率向上）
                        for (target in unicastTargets) {
                            try {
                                val gw = InetAddress.getByName(target)
                                sock.send(DatagramPacket(out, out.size, gw, MULTICAST_PORT))
                            } catch (_: Exception) {
                            }
                        }
                    }
                    val found = linkedMapOf<String, SsdpResult>()
                    val buf = ByteArray(8192)
                    val deadline = System.currentTimeMillis() + waitMs
                    while (System.currentTimeMillis() < deadline) {
                        try {
                            val pkt = DatagramPacket(buf, buf.size)
                            sock.receive(pkt)
                            val text = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
                            parseResponse(text)?.let { found[it.location] = it }
                        } catch (_: java.net.SocketTimeoutException) {
                            // 継続
                        }
                    }
                    found.values.toList()
                }
            } ?: emptyList()
        } finally {
            if (lock.isHeld) lock.release()
        }
    }

    /**
     * SSDP NOTIFY等のマルチキャスト受信をwaitMsだけ待受し、生パケットを返す。
     * カメラが自発送出するannounceを捉えるためのパッシブ手法。
     */
    suspend fun listen(
        context: Context,
        waitMs: Long = 20000,
    ): List<String> = withContext(Dispatchers.IO) {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("a6000-listen").apply {
            setReferenceCounted(true)
            acquire()
        }
        try {
            withTimeoutOrNull(waitMs + 3000) {
                val wlan = try {
                    NetworkInterface.getNetworkInterfaces()?.toList()
                        ?.firstOrNull { it.name == "wlan0" && it.isUp }
                } catch (_: Exception) {
                    null
                }
                val socket = MulticastSocket(MULTICAST_PORT).apply {
                    reuseAddress = true
                    soTimeout = 1500
                    if (wlan != null) {
                        try {
                            setNetworkInterface(wlan)
                            joinGroup(
                                InetSocketAddress(InetAddress.getByName(MULTICAST_ADDR), MULTICAST_PORT),
                                wlan,
                            )
                        } catch (_: Exception) {
                        }
                    }
                }
                socket.use { sock ->
                    val out = mutableListOf<String>()
                    val buf = ByteArray(8192)
                    val deadline = System.currentTimeMillis() + waitMs
                    while (System.currentTimeMillis() < deadline) {
                        try {
                            val pkt = DatagramPacket(buf, buf.size)
                            sock.receive(pkt)
                            val text = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
                            out.add("[${pkt.address.hostAddress}:${pkt.port}]\n" + text.take(600))
                        } catch (_: java.net.SocketTimeoutException) {
                        }
                    }
                    out.toList()
                }
            } ?: emptyList()
        } finally {
            if (lock.isHeld) lock.release()
        }
    }
}

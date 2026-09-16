package jp.yuki.a6000transfer.sony

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

object PortScan {
    /** よく使う候補ポート群。Sony系＋DLNA系＋代表的エフェメラル */
    val CANDIDATES: List<Int> = (
        listOf(
            80, 443, 1900, 2869, 5000, 50001, 50002, 5080,
            8008, 8009, 8080, 8081, 8888, 10000, 15740,
            49152, 49153, 49154, 49200, 49201,
            50010, 50020, 50100, 51000, 52000, 55000, 56000, 56789,
            60000, 61000, 62000, 63000, 64000, 65000,
        ) + (1..100).toList() + (1000..1010).toList() + (32768..32780).toList() + (49160..49180).toList()
        ).distinct().sorted()

    private suspend fun isOpen(host: String, port: Int, timeoutMs: Int): Boolean =
        withContext(Dispatchers.IO) {
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress(host, port), timeoutMs)
                    true
                }
            } catch (_: Exception) {
                false
            }
        }

    suspend fun scan(host: String, ports: List<Int> = CANDIDATES, timeoutMs: Int = 1200): List<Int> =
        withContext(Dispatchers.Default) {
            ports.chunked(40).flatMap { chunk ->
                chunk.map { p -> async { if (isOpen(host, p, timeoutMs)) p else null } }.awaitAll().filterNotNull()
            }
        }
}

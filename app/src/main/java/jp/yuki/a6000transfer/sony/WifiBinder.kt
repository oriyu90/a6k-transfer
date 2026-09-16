package jp.yuki.a6000transfer.sony

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

sealed interface BindResult {
    data class Ok(val ssid: String) : BindResult
    data class Ng(val reason: String) : BindResult
}

/**
 * 指定SSIDのWi-Fiにアプリの通信をバインドする（Android 10+）。
 * カメラAPのようなインターネットなしAPでも切断されずに通信できる。
 */
object WifiBinder {
    @Volatile
    var boundSsid: String? = null
        private set

    private var requestedNetwork: Network? = null
    private var activeCallback: ConnectivityManager.NetworkCallback? = null

    /** 要求を登録し、後でunregisterするためのコールバックを返す */
    fun requestBind(
        context: Context,
        ssid: String,
        passphrase: String,
        callback: (BindResult) -> Unit,
    ): ConnectivityManager.NetworkCallback? {
        if (Build.VERSION.SDK_INT < 29) {
            callback(BindResult.Ng("Android 10未満は非対応"))
            return null
        }
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val spec = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(passphrase)
            .build()
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(spec)
            .build()
        val nc = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                requestedNetwork = network
                boundSsid = ssid
                try {
                    cm.bindProcessToNetwork(network)
                    callback(BindResult.Ok(ssid))
                } catch (e: Exception) {
                    callback(BindResult.Ng("bind失敗: ${e.message}"))
                }
            }

            override fun onUnavailable() {
                callback(BindResult.Ng("ネットワーク要求が利用不可（SSID/パスフレーズを確認）"))
            }

            override fun onLost(network: Network) {
                if (requestedNetwork == network) {
                    requestedNetwork = null
                    boundSsid = null
                }
            }
        }
        try {
            cm.requestNetwork(req, nc)
            activeCallback?.let {
                try {
                    cm.unregisterNetworkCallback(it)
                } catch (_: Exception) {
                }
            }
            activeCallback = nc
            return nc
        } catch (e: Exception) {
            callback(BindResult.Ng("request失敗: ${e.message}"))
            return null
        }
    }

    /** 45秒タイムアウト付き。タイムアウト時は要求を解除してNgを返す */
    suspend fun requestBindAwait(context: Context, ssid: String, passphrase: String): BindResult {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return try {
            kotlinx.coroutines.withTimeout(45_000) {
                suspendCancellableCoroutine { cont ->
                    val nc = requestBind(context, ssid, passphrase) {
                        if (cont.isActive) cont.resume(it)
                    }
                    cont.invokeOnCancellation {
                        nc?.let {
                            try {
                                cm.unregisterNetworkCallback(it)
                            } catch (_: Exception) {
                            }
                        }
                    }
                }
            }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            BindResult.Ng("タイムアウト（45秒）。カメラのAPが見つからないか承認待ちの可能性。解放して再試行してください")
        }
    }

    fun unbind(context: Context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.bindProcessToNetwork(null)
        activeCallback?.let {
            try {
                cm.unregisterNetworkCallback(it)
            } catch (_: Exception) {
            }
        }
        activeCallback = null
        requestedNetwork = null
        boundSsid = null
    }
}

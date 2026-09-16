package jp.yuki.a6000transfer.sony

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

sealed interface BindResult {
    data class Ok(val ssid: String) : BindResult
    data class Ng(val reason: String) : BindResult
}

/**
 * 指定SSIDのWi-Fiにアプリの通信をバインドする（Android 10+）。
 * カメラAPのようなインターネットなしAPでも切断されずに通信できる。
 *
 * 世代ガード: 要求ごとに世代番号を進め、旧い要求のコールバックは無視する。
 * これにより (1)タイムアウト後の遅延onAvailableによる意図せぬバインド、
 * (2)タイムアウト解除と承認ダイアログ表示中の競合によるシステムエラー表示、
 * をどちらも起こさない。旧コールバックは失効後に自らunregisterする。
 */
object WifiBinder {
    @Volatile
    var boundSsid: String? = null
        private set

    private var requestedNetwork: Network? = null
    private var activeCallback: ConnectivityManager.NetworkCallback? = null
    private val generation = AtomicInteger(0)

    /** バインド喪失時の通知（AppStateのみ触ること。Contextを捕まえない） */
    var onLostListener: (() -> Unit)? = null

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
        // setWpa2Passphraseは空文字・非ASCIIでIllegalArgumentExceptionを投げる。
        // 呼び出し側コルーチンをクラッシュさせないためここで検証する
        if (ssid.isBlank()) {
            callback(BindResult.Ng("SSIDが空です"))
            return null
        }
        if (passphrase.isEmpty() || passphrase.any { it.code !in 0x20..0x7E }) {
            callback(BindResult.Ng("パスフレーズはASCII 8〜63文字で入力してください"))
            return null
        }
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val spec = try {
            WifiNetworkSpecifier.Builder()
                .setSsid(ssid)
                .setWpa2Passphrase(passphrase)
                .build()
        } catch (e: IllegalArgumentException) {
            callback(BindResult.Ng("パスフレーズ不正: ${e.message}"))
            return null
        }
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(spec)
            .build()
        // 新しい要求で旧世代を失効させる
        val gen = generation.incrementAndGet()
        val nc = object : ConnectivityManager.NetworkCallback() {
            private fun isStale(): Boolean = gen != generation.get()

            private fun unregisterQuietly() {
                try {
                    cm.unregisterNetworkCallback(this)
                } catch (_: Exception) {
                }
            }

            override fun onAvailable(network: Network) {
                if (isStale()) {
                    // 失効済み要求の遅延接続。バインドせず静かに後始末する
                    unregisterQuietly()
                    return
                }
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
                if (isStale()) {
                    unregisterQuietly()
                    return
                }
                callback(BindResult.Ng("ネットワーク要求が利用不可（SSID/パスフレーズを確認）"))
            }

            override fun onLost(network: Network) {
                if (requestedNetwork == network) {
                    requestedNetwork = null
                    boundSsid = null
                    // NetworkCallbackはbinderスレッドで呼ばれる。Compose state更新のためmainへ配送
                    try {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            try {
                                onLostListener?.invoke()
                            } catch (_: Exception) {
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        }
        try {
            cm.requestNetwork(req, nc)
            // 旧コールバックの置換。接続済み（承認済み）のものだけ即時解除し、
            // 承認待ちの可能性があるものは失効扱いにして自己後始末に任せる
            // （表示中の承認ダイアログを殺すとシステムエラー表示が出るため）
            val old = activeCallback
            activeCallback = nc
            if (old != null && boundSsid != null) {
                try {
                    cm.unregisterNetworkCallback(old)
                } catch (_: Exception) {
                }
            }
            return nc
        } catch (e: Exception) {
            callback(BindResult.Ng("request失敗: ${e.message}"))
            return null
        }
    }

    /** 45秒タイムアウト付き。タイムアウト時はNgを返すだけ（解除は世代ガードに任せる） */
    suspend fun requestBindAwait(context: Context, ssid: String, passphrase: String): BindResult {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return try {
            kotlinx.coroutines.withTimeout(45_000) {
                suspendCancellableCoroutine { cont ->
                    val nc = requestBind(context, ssid, passphrase) {
                        if (cont.isActive) cont.resume(it)
                    }
                    cont.invokeOnCancellation {
                        // 失効だけ行いunregisterはしない。承認ダイアログ表示中の
                        // unregisterはシステムエラー表示を誘発するため、自己後始末に任せる
                        generation.incrementAndGet()
                        if (nc != null && nc !== activeCallback) {
                            try {
                                cm.unregisterNetworkCallback(nc)
                            } catch (_: Exception) {
                            }
                        }
                    }
                }
            }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            // 失効だけ行う。承認ダイアログ表示中のunregisterは避け、
            // 遅延onAvailable/onUnavailableは世代ガードが無害化する
            generation.incrementAndGet()
            BindResult.Ng("タイムアウト（45秒）。カメラのAPが見つからないか承認待ちの可能性。解放して再試行してください")
        }
    }

    /** 明示的な解放。承認ダイアログ表示中の呼び出しはシステムが確認表示を出す場合がある */
    fun unbind(context: Context) {
        generation.incrementAndGet()
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        try {
            cm.bindProcessToNetwork(null)
        } catch (_: Exception) {
        }
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

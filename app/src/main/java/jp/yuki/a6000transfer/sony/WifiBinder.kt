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
 *
 * 要求直列化: 同一SSIDへの要求が処理中の場合、新規登録せず待機者として合流する
 * （同一Specifierの重複requestNetworkはシステム側で無視され、コールバックが
 * 戻らず無期限待機になるため）。失効済みの処理中要求が残っている場合の再試行は、
 * 旧登録を解除して作り直す。SSID切替時は旧要求を解除する。
 */
object WifiBinder {
    @Volatile
    var boundSsid: String? = null
        private set

    private var requestedNetwork: Network? = null
    private val generation = AtomicInteger(0)
    private val binderLock = Any()

    private data class Outstanding(
        val ssid: String,
        val gen: Int,
        val callback: ConnectivityManager.NetworkCallback,
        val waiters: MutableList<(BindResult) -> Unit>,
    )

    private var outstanding: Outstanding? = null

    /** バインド喪失時の通知（AppStateのみ触ること。Contextを捕まえない） */
    var onLostListener: (() -> Unit)? = null

    private fun deliver(o: Outstanding, r: BindResult) {
        val waiters = synchronized(binderLock) {
            if (outstanding !== o) return
            outstanding = null
            o.waiters.toList().also { o.waiters.clear() }
        }
        waiters.forEach { w ->
            try {
                w(r)
            } catch (_: Exception) {
            }
        }
    }

    /** 待機解除（タイムアウト・キャンセル）。システム登録は残し後始末に任せる */
    private fun detachWaiter(waiter: (BindResult) -> Unit) {
        synchronized(binderLock) {
            outstanding?.waiters?.removeAll { it === waiter }
        }
        generation.incrementAndGet()
    }

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

            /** 失効済み・引継ぎ先なしの到着は自ら後始末する */
            private fun staleArrived() {
                synchronized(binderLock) {
                    if (outstanding?.gen == gen) outstanding = null
                }
                unregisterQuietly()
            }

            override fun onAvailable(network: Network) {
                val o = synchronized(binderLock) { outstanding }
                if (o == null || o.gen != gen || isStale()) {
                    // 失効済み要求の遅延接続。バインドせず静かに後始末する
                    staleArrived()
                    return
                }
                requestedNetwork = network
                boundSsid = ssid
                try {
                    cm.bindProcessToNetwork(network)
                    deliver(o, BindResult.Ok(ssid))
                } catch (e: Exception) {
                    deliver(o, BindResult.Ng("bind失敗: ${e.message}"))
                }
            }

            override fun onUnavailable() {
                val o = synchronized(binderLock) { outstanding }
                if (o == null || o.gen != gen || isStale()) {
                    staleArrived()
                    return
                }
                deliver(o, BindResult.Ng("ネットワーク要求が利用不可（SSID/パスフレーズを確認）"))
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
        synchronized(binderLock) {
            val o = outstanding
            if (o != null && o.ssid == ssid && o.gen == generation.get()) {
                // 処理中の同一要求へ合流（重複登録はシステムに無視され待機が戻らないため）
                o.waiters.add(callback)
                return o.callback
            }
            if (o != null) {
                // 失効済みの残骸・SSID切替の旧要求は解除して作り直す。
                // 待機者がいれば解放を通知してぶら下げない
                outstanding = null
                val dropped = o.waiters.toList().also { o.waiters.clear() }
                try {
                    cm.unregisterNetworkCallback(o.callback)
                } catch (_: Exception) {
                }
                dropped.forEach { w ->
                    try {
                        w(BindResult.Ng("接続要求を作り直しました。再試行してください"))
                    } catch (_: Exception) {
                    }
                }
            }
            outstanding = Outstanding(ssid, gen, nc, mutableListOf(callback))
        }
        try {
            cm.requestNetwork(req, nc)
            return nc
        } catch (e: Exception) {
            synchronized(binderLock) {
                if (outstanding?.gen == gen) outstanding = null
            }
            callback(BindResult.Ng("request失敗: ${e.message}"))
            return null
        }
    }

    /** 45秒タイムアウト付き。タイムアウト時はNgを返すだけ（解除は世代ガードに任せる） */
    suspend fun requestBindAwait(context: Context, ssid: String, passphrase: String): BindResult {
        return try {
            kotlinx.coroutines.withTimeout(45_000) {
                suspendCancellableCoroutine { cont ->
                    val waiter: (BindResult) -> Unit = {
                        if (cont.isActive) cont.resume(it)
                    }
                    requestBind(context, ssid, passphrase, waiter)
                    cont.invokeOnCancellation {
                        // 失効だけ行いunregisterはしない。承認ダイアログ表示中の
                        // unregisterはシステムエラー表示を誘発するため、自己後始末に任せる
                        detachWaiter(waiter)
                    }
                }
            }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            // 待機解除はinvokeOnCancellation側で実施済み。承認ダイアログ表示中の
            // unregisterは避け、遅延onAvailable/onUnavailableは世代ガードが無害化する
            // （再試行時は失効済み残骸を解除して作り直すため待機は残らない）
            BindResult.Ng("タイムアウト（45秒）。カメラのAPが見つからないか承認待ちの可能性。解放して再試行してください")
        }
    }

    /** 明示的な解放。承認ダイアログ表示中の呼び出しはシステムが確認表示を出す場合がある */
    fun unbind(context: Context) {
        generation.incrementAndGet()
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val dropped: List<(BindResult) -> Unit>
        synchronized(binderLock) {
            dropped = outstanding?.waiters?.toList().orEmpty()
            outstanding?.waiters?.clear()
            val o = outstanding
            outstanding = null
            o?.callback?.let {
                try {
                    cm.unregisterNetworkCallback(it)
                } catch (_: Exception) {
                }
            }
        }
        try {
            cm.bindProcessToNetwork(null)
        } catch (_: Exception) {
        }
        requestedNetwork = null
        boundSsid = null
        dropped.forEach { w ->
            try {
                w(BindResult.Ng("接続を解放しました"))
            } catch (_: Exception) {
            }
        }
    }
}

package jp.yuki.a6000transfer.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import jp.yuki.a6000transfer.MainActivity
import jp.yuki.a6000transfer.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** フォアグラウンド転送の進捗（プロセス内で共有。UIは購読するだけ） */
data class TransferProgress(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val currentTitle: String = "",
    /** 今回確定した転送済みID（UIのラベル反映用。累積） */
    val doneIds: Set<String> = emptySet(),
    /** 完了・中断メッセージ（表示後にconsumeする） */
    val finishedText: String? = null,
)

/** 転送キューと進捗の共有点。同一プロセスのためバインド済みネットワークは維持される */
object TransferManager {
    const val ACTION_START = "jp.yuki.a6000transfer.action.START"
    const val ACTION_CANCEL = "jp.yuki.a6000transfer.action.CANCEL"

    private val _state = MutableStateFlow(TransferProgress())
    val state: StateFlow<TransferProgress> = _state

    @Volatile
    var cancelRequested = false
        private set

    /** startService直前にUIスレッドから設定される（同一プロセス内受け渡し） */
    @Volatile
    var pending: List<Photo> = emptyList()
        private set

    fun start(context: Context, photos: List<Photo>) {
        if (_state.value.running || photos.isEmpty()) return
        pending = photos.toList()
        cancelRequested = false
        _state.value = TransferProgress(running = true, total = photos.size)
        val intent = Intent(context.applicationContext, TransferService::class.java).setAction(ACTION_START)
        try {
            ContextCompat.startForegroundService(context.applicationContext, intent)
        } catch (_: Exception) {
            pending = emptyList()
            _state.value = TransferProgress()
        }
    }

    fun cancel(context: Context) {
        cancelRequested = true
        try {
            context.applicationContext.startService(
                Intent(context.applicationContext, TransferService::class.java).setAction(ACTION_CANCEL),
            )
        } catch (_: Exception) {
        }
    }

    internal fun publish(p: TransferProgress) {
        _state.value = p
    }

    internal fun requestCancel() {
        cancelRequested = true
    }

    internal fun consumeFinished() {
        _state.value = _state.value.copy(finishedText = null)
    }

    internal fun takePending(): List<Photo> {
        val p = pending
        pending = emptyList()
        return p
    }
}

private data class BatchResult(val ok: Int, val aborted: Boolean, val cancelled: Boolean)

/**
 * バックグラウンド転送用フォアグラウンドサービス。
 * 通知プログレス＋WakeLock/WifiLockで画面OFF・アプリ切替後も転送を継続する。
 * 失敗時の3連続打ち切り・履歴追記は従来UI内転送と同一ルール。
 *
 * 終了安全性: 完了直後の二重開始はキューで直列化し、CANCELのみ受信時は自己停止する。
 * 中止はダウンロードの読み取りループ内で協調的に検出し、不完全ファイルは公開しない。
 */
class TransferService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val serviceLock = Any()
    private var transferJob: Job? = null
    private val pendingQueue: ArrayDeque<List<Photo>> = ArrayDeque()
    private val cumulativeIds = mutableSetOf<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                TransferManager.requestCancel()
                synchronized(serviceLock) { pendingQueue.clear() }
                // 実行中ジョブがなければCANCEL受信だけの起動なので残留させない
                val idle = synchronized(serviceLock) { transferJob == null }
                if (idle) stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val photos = TransferManager.takePending()
                var launchNow = false
                var first: List<Photo>? = null
                synchronized(serviceLock) {
                    if (photos.isNotEmpty()) {
                        if (transferJob == null && pendingQueue.isEmpty()) {
                            cumulativeIds.clear()
                        }
                        pendingQueue.addLast(photos)
                    }
                    if (transferJob == null && pendingQueue.isNotEmpty()) {
                        transferJob = scope.launch { drainQueue() }
                        first = pendingQueue.firstOrNull()
                        launchNow = true
                    }
                }
                if (launchNow && first != null) {
                    // startForegroundService直後に速やかにフォアグラウンド化する
                    startForeground(NOTIF_ID, buildNotification(0, first!!.size, "", true))
                    acquireLocks()
                }
                val idle = synchronized(serviceLock) { transferJob == null && pendingQueue.isEmpty() }
                if (idle) stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    /** キューが空になるまで直列に転送する。終了後は必ず状態を idle に戻す */
    private suspend fun drainQueue() {
        var totalOk = 0
        var totalAttempted = 0
        var userCancelled = false
        var aborted = false
        try {
            while (true) {
                if (TransferManager.cancelRequested) {
                    userCancelled = true
                    break
                }
                val batch = synchronized(serviceLock) { pendingQueue.removeFirstOrNull() }
                if (batch == null) {
                    // 新規投入との競合に備え、ロック内で空確認と終了を原子化する
                    var done = false
                    synchronized(serviceLock) {
                        if (pendingQueue.isEmpty()) {
                            transferJob = null
                            done = true
                        }
                    }
                    if (done) break else continue
                }
                val r = runBatch(batch)
                totalOk += r.ok
                totalAttempted += batch.size
                if (r.cancelled) {
                    userCancelled = true
                    break
                }
                if (r.aborted) {
                    aborted = true
                    break
                }
            }
        } finally {
            synchronized(serviceLock) { pendingQueue.clear() }
            releaseLocks()
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (_: Exception) {
            }
            stopSelf()
            val text = when {
                userCancelled || TransferManager.cancelRequested ->
                    getString(R.string.transfer_cancelled, totalOk, totalAttempted)
                aborted ->
                    getString(R.string.transfer_aborted, totalOk, totalAttempted)
                else ->
                    getString(R.string.transfer_done, totalOk, totalAttempted)
            }
            TransferManager.publish(
                TransferProgress(false, totalAttempted, totalAttempted, "", cumulativeIds.toSet(), text),
            )
            showFinishedNotification(text)
        }
    }

    private suspend fun runBatch(photos: List<Photo>): BatchResult {
        var ok = 0
        var consecutiveFail = 0
        photos.forEachIndexed { i, p ->
            if (TransferManager.cancelRequested) return BatchResult(ok, false, true)
            val prog = TransferProgress(true, i, photos.size, p.title, doneIds = cumulativeIds.toSet())
            TransferManager.publish(prog)
            updateNotification(i, photos.size, p.title)
            try {
                val file = DlnaRepository.downloadFull(
                    this,
                    p,
                    isCancelled = { !scope.coroutineContext.isActive || TransferManager.cancelRequested },
                ) { _, _ -> }
                // ダウンロード完了直後の中止は保存せず破棄する
                if (TransferManager.cancelRequested || !scope.coroutineContext.isActive) {
                    return BatchResult(ok, false, true)
                }
                val uri = DlnaRepository.saveMedia(this, file, p)
                if (uri != null) {
                    ok++
                    cumulativeIds.add(p.id)
                    consecutiveFail = 0
                    // 1件ごとに履歴へ追記（強制終了時も確定分を残す）
                    DlnaRepository.addDownloadHistory(this, listOf(p.title))
                } else {
                    consecutiveFail++
                }
            } catch (e: CancellationException) {
                // サービス破棄時は再送出して終了させる。ユーザー中止は中断扱い
                if (!TransferManager.cancelRequested) throw e
                return BatchResult(ok, false, true)
            } catch (_: Exception) {
                consecutiveFail++
            }
            // 変なタイミングのWi-Fi切断では全件失敗が続く。3連続失敗で打ち切り復帰する
            if (consecutiveFail >= 3) {
                return BatchResult(ok, true, false)
            }
        }
        return BatchResult(ok, false, false)
    }

    private fun acquireLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            // プロセス破棄時はシステムが自動解放するためタイムアウトなしで取得し、finally/onDestroyで必ず解放する
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "a6000:transfer").apply {
                acquire()
            }
        } catch (_: Exception) {
            wakeLock = null
        }
        try {
            @Suppress("DEPRECATION")
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "a6000:transfer").apply {
                acquire()
            }
        } catch (_: Exception) {
            wifiLock = null
        }
    }

    private fun releaseLocks() {
        try {
            wifiLock?.takeIf { it.isHeld }?.release()
        } catch (_: Exception) {
        }
        wifiLock = null
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    private fun channelId(): String = CHANNEL_ID

    private fun ensureChannel() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel_transfer),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        } catch (_: Exception) {
        }
    }

    private fun contentIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getActivity(this, 0, intent, flags)
    }

    private fun cancelIntent(): PendingIntent {
        val intent = Intent(this, TransferService::class.java).setAction(ACTION_CANCEL)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getService(this, 1, intent, flags)
    }

    private fun buildNotification(done: Int, total: Int, title: String, ongoing: Boolean): Notification {
        ensureChannel()
        val text = if (title.isBlank()) {
            getString(R.string.transfer_preparing)
        } else {
            getString(R.string.transfer_progress_text, done + 1, total, title)
        }
        val builder = Notification.Builder(this, channelId())
            .setContentTitle(getString(R.string.transfer_nofif_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(contentIntent())
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setProgress(total, done, false)
        if (ongoing) {
            builder.addAction(
                Notification.Action.Builder(
                    null,
                    getString(R.string.cancel),
                    cancelIntent(),
                ).build(),
            )
        }
        return builder.build()
    }

    private fun updateNotification(done: Int, total: Int, title: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification(done, total, title, true))
        } catch (_: Exception) {
        }
    }

    private fun showFinishedNotification(text: String) {
        try {
            ensureChannel()
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notif = Notification.Builder(this, channelId())
                .setContentTitle(getString(R.string.transfer_nofif_title))
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentIntent(contentIntent())
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIF_ID, notif)
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        scope.cancel()
        releaseLocks()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "a6000_transfer"
        private const val NOTIF_ID = 1001
        private const val ACTION_START = TransferManager.ACTION_START
        private const val ACTION_CANCEL = TransferManager.ACTION_CANCEL
    }
}

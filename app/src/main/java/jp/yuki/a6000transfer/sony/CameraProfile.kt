package jp.yuki.a6000transfer.sony

/**
 * 機種別プロファイル。α6000で実証済みのDLNAフローを基準にし、
 * α6100以降は実機未検証の予測値（コード・UI上で明示）で幅を持たせる。
 *
 * 背景の予測根拠:
 * - SonyのDIRECT-* AP＋WPA2方式はImaging Edge Mobile世代でも継続
 * - DLNA DMSはα6000実績。新型FWは階層が深い可能性があるため深さ・待機に余裕
 * - 新型の一部はScalarWebAPI(Camera Remote API)も併設するためdd.xml照会を副経路化
 * - 新型APのGWは10.0.0.1が本命、保険で192.168.122.1系も試す
 */
data class CameraProfile(
    val id: String,
    /** SSDP M-SEARCHで試すST（優先順） */
    val ssdpSt: List<String>,
    /** NOTIFY待受時間 */
    val listenMs: Long,
    /** dd.xml / Remote API照会のGW候補 */
    val gateways: List<String>,
    /** DLNAツリー走査の最大深さ */
    val maxDepth: Int,
    /** 実機検証済みか */
    val verified: Boolean,
) {
    companion object {
        const val AUTO = "auto"
        const val A6000 = "a6000"
        const val A6100 = "a6100"

        val A6000_PROFILE = CameraProfile(
            id = A6000,
            ssdpSt = listOf("urn:schemas-upnp-org:device:MediaServer:1"),
            listenMs = 20000,
            gateways = listOf("10.0.0.1"),
            maxDepth = 3,
            verified = true,
        )

        /** α6100/α6400/α7系など次世代の予測プロファイル（未検証） */
        val A6100_PROFILE = CameraProfile(
            id = A6100,
            ssdpSt = listOf(
                "urn:schemas-upnp-org:device:MediaServer:1",
                "upnp:rootdevice",
                "ssdp:all",
            ),
            listenMs = 25000,
            gateways = listOf("10.0.0.1", "192.168.122.1"),
            maxDepth = 4,
            verified = false,
        )

        fun of(id: String?): CameraProfile = when (id) {
            A6000 -> A6000_PROFILE
            A6100 -> A6100_PROFILE
            else -> A6000_PROFILE // autoの実体は探索時に両方試す。下位互換の既定はα6000
        }

        fun isAuto(id: String?): Boolean = id == null || id == AUTO
    }
}

/** 探索結果。DLNA LOCATIONが本命、remoteApiBaseは副経路の参考情報 */
data class DiscoveryResult(
    val locationUrl: String?,
    val remoteApiBase: String?,
    val triedGateways: List<String>,
)

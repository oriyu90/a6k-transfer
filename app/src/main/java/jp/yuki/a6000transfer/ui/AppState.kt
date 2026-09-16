package jp.yuki.a6000transfer.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** タブ間で共有する接続状態 */
class AppState {
    private val _boundSsid = MutableStateFlow<String?>(null)
    val boundSsid: StateFlow<String?> = _boundSsid

    private val _location = MutableStateFlow<String?>(null)
    val location: StateFlow<String?> = _location

    fun setBound(ssid: String?) {
        _boundSsid.value = ssid
    }

    fun setLocation(url: String?) {
        _location.value = url
    }

    private val _cameraModel = MutableStateFlow("auto")
    val cameraModel: StateFlow<String> = _cameraModel

    fun setModel(id: String) {
        _cameraModel.value = id
    }
}

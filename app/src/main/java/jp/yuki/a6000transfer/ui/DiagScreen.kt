package jp.yuki.a6000transfer.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jp.yuki.a6000transfer.sony.BindResult
import jp.yuki.a6000transfer.R
import jp.yuki.a6000transfer.sony.DlnaClient
import jp.yuki.a6000transfer.sony.PortScan
import jp.yuki.a6000transfer.sony.SonyClient
import jp.yuki.a6000transfer.sony.SsdpDiscovery
import jp.yuki.a6000transfer.sony.WifiBinder
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun timeNow(): String =
    SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

private fun wifiSummary(ctx: Context): String {
    return try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork
        val caps = cm.getNetworkCapabilities(net)
        val wifiInfo = caps?.transportInfo as? android.net.wifi.WifiInfo
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val legacySsid = wm.connectionInfo.ssid?.trim('"')
        val ssid = wifiInfo?.ssid?.trim('"')?.takeIf { it != "<unknown ssid>" && it.isNotEmpty() }
            ?: legacySsid?.takeIf { it != "<unknown ssid>" && it.isNotEmpty() }
            ?: "?"
        val rssi = wifiInfo?.rssi?.toString() ?: "?"
        @Suppress("DEPRECATION")
        val ipInt = wm.connectionInfo.ipAddress
        val ip = "%d.%d.%d.%d".format(ipInt and 0xff, ipInt shr 8 and 0xff, ipInt shr 16 and 0xff, ipInt shr 24 and 0xff)
        val dhcp = wm.dhcpInfo
        val gw = if (dhcp != null) {
            "%d.%d.%d.%d".format(dhcp.gateway and 0xff, dhcp.gateway shr 8 and 0xff, dhcp.gateway shr 16 and 0xff, dhcp.gateway shr 24 and 0xff)
        } else "?"
        "SSID=$ssid RSSI=$rssi IP=$ip GW=$gw"
    } catch (e: Exception) {
        ctx.getString(R.string.failed, e.message ?: "?")
    }
}

@Composable
fun DiagScreen(ctx: Context, appState: AppState) {
    val scope = rememberCoroutineScope()
    val logs = remember { mutableStateListOf<String>() }
    var busy by remember { mutableStateOf(false) }
    var baseUrl by remember { mutableStateOf(SonyClient.baseUrl) }
    var wifiInfo by remember { mutableStateOf("") }
    var svc by remember { mutableStateOf("camera") }
    var stField by remember { mutableStateOf("ssdp:all") }
    var locField by remember { mutableStateOf("http://10.0.0.1:64321/DmsDesc.xml") }
    var oidField by remember { mutableStateOf("02_00_0000644000_000000_000000_000000") }
    var bindSsid by remember { mutableStateOf("DIRECT-cNE0:a6000") }
    var bindPass by remember { mutableStateOf("") }
    var bindStatus by remember { mutableStateOf(ctx.getString(R.string.diag_not_bound)) }
    var showBindPass by remember { mutableStateOf(false) }
    val prefs = remember { ctx.getSharedPreferences("a6000diag", Context.MODE_PRIVATE) }
    LaunchedEffect(Unit) {
        bindSsid = prefs.getString("ssid", bindSsid) ?: bindSsid
        bindPass = prefs.getString("pass", "") ?: ""
    }
    var method by remember { mutableStateOf("getAvailableApiList") }
    var params by remember { mutableStateOf("[]") }
    var version by remember { mutableStateOf("1.0") }
    val clipboard = LocalClipboardManager.current

    fun log(tag: String, body: String) {
        val short = if (body.length > 1500) body.take(1500) + "\n" + ctx.getString(R.string.diag_omitted, body.length - 1500) else body
        logs.add("[${timeNow()}] $tag\n$short")
        while (logs.size > 40) logs.removeAt(0)
    }

    fun refreshWifi() {
        wifiInfo = wifiSummary(ctx)
    }

    LaunchedEffect(Unit) { refreshWifi() }

    fun runTask(tag: String, block: suspend () -> String) {
        if (busy) return
        busy = true
        log(tag, ctx.getString(R.string.diag_running))
        scope.launch {
            try {
                val res = kotlinx.coroutines.withTimeoutOrNull(100_000) { block() }
                    ?: ctx.getString(R.string.diag_timeout)
                log(tag, res)
            } catch (e: Exception) {
                log(tag, "EXCEPTION ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                busy = false
            }
        }
    }

    Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState())) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.diag_connection_status), style = MaterialTheme.typography.titleMedium)
                Text(wifiInfo.ifEmpty { stringResource(R.string.diag_not_fetched) }, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { refreshWifi() }) { Text(stringResource(R.string.diag_refresh)) }
                    OutlinedButton(onClick = {
                        clipboard.setText(AnnotatedString(logs.joinToString("\n\n")))
                    }) { Text(stringResource(R.string.diag_copy_logs)) }
                    OutlinedButton(onClick = { logs.clear() }) { Text(stringResource(R.string.clear_selection)) }
                }
            }
        }

        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it; SonyClient.baseUrl = it.trim() },
            label = { Text("Base URL") },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            singleLine = true,
        )

        Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Column(Modifier.padding(12.dp), Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.diag_ap_bind_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.status, bindStatus), fontSize = 13.sp)
                OutlinedTextField(value = bindSsid, onValueChange = { bindSsid = it }, label = { Text(stringResource(R.string.ssid)) }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii))
                OutlinedTextField(
                    value = bindPass,
                    onValueChange = { bindPass = it },
                    label = { Text(stringResource(R.string.passphrase)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = if (showBindPass) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        androidx.compose.material3.TextButton(onClick = { showBindPass = !showBindPass }) {
                            Text(stringResource(if (showBindPass) R.string.hide_password else R.string.show_password))
                        }
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        prefs.edit().putString("ssid", bindSsid.trim()).putString("pass", bindPass).apply()
                        runTask(ctx.getString(R.string.diag_bind_log)) {
                            when (val r = WifiBinder.requestBindAwait(ctx, bindSsid.trim(), bindPass)) {
                                is BindResult.Ok -> {
                                    bindStatus = ctx.getString(R.string.bound_as, r.ssid)
                                    appState.setBound(r.ssid)
                                    ctx.getString(R.string.diag_bind_success, r.ssid)
                                }
                                is BindResult.Ng -> {
                                    bindStatus = ctx.getString(R.string.failed, r.reason)
                                    appState.setBound(null)
                                    ctx.getString(R.string.diag_bind_failure, r.reason)
                                }
                            }
                        }
                    }, enabled = !busy) { Text(stringResource(R.string.connect_bind)) }
                    OutlinedButton(onClick = {
                        WifiBinder.unbind(ctx)
                        bindStatus = ctx.getString(R.string.diag_not_bound)
                        appState.setBound(null)
                    }) { Text(stringResource(R.string.release)) }
                }
            }
        }

        Text(stringResource(R.string.diag_presets), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
        OutlinedTextField(
            value = stField,
            onValueChange = { stField = it },
            label = { Text("SSDP ST") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(6.dp)) {
            val presets = listOf(
                "all" to "ssdp:all",
                "Scalar" to "urn:schemas-sony-com:service:ScalarWebAPI:1",
                "MediaServer" to "urn:schemas-upnp-org:device:MediaServer:1",
                "rootdevice" to "upnp:rootdevice",
            )
            presets.forEach { (label, v) ->
                OutlinedButton(onClick = { stField = v }) { Text(label, fontSize = 11.sp) }
            }
        }
        OutlinedTextField(
            value = locField,
            onValueChange = { locField = it },
            label = { Text("DLNA LOCATION") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = oidField,
            onValueChange = { oidField = it },
            label = { Text("ObjectID") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        val probes = listOf(
            stringResource(R.string.diag_ssdp_search) to suspend {
                val r = SsdpDiscovery.discover(ctx, stField.ifBlank { "ssdp:all" })
                if (r.isEmpty()) ctx.getString(R.string.diag_no_response)
                else r.joinToString("\n---\n") { "LOCATION=${it.location}\nST=${it.st}\nUSN=${it.usn}\nSERVER=${it.server}" }
            },
            "DLNA Browse" to suspend {
                val locs = SsdpDiscovery.discover(ctx, stField.ifBlank { "ssdp:all" })
                val loc = locs.firstOrNull { it.location.isNotEmpty() }?.location
                if (loc == null) ctx.getString(R.string.diag_no_ssdp) else DlnaClient.browseRoot(loc)
            },
            stringResource(R.string.diag_notify_listen) to suspend {
                val pkts = SsdpDiscovery.listen(ctx, 20000)
                if (pkts.isEmpty()) ctx.getString(R.string.diag_no_packets) else pkts.joinToString("\n---\n")
            },
            stringResource(R.string.diag_browse_location) to suspend {
                DlnaClient.browseRoot(
                    locField.ifBlank { "http://10.0.0.1:64321/DmsDesc.xml" },
                    oidField.ifBlank { "0" },
                )
            },
            stringResource(R.string.diag_drill) to suspend {
                DlnaClient.drillDown(
                    locField.ifBlank { "http://10.0.0.1:64321/DmsDesc.xml" },
                )
            },
            stringResource(R.string.diag_download_photo) to suspend {
                val loc = locField.ifBlank { "http://10.0.0.1:64321/DmsDesc.xml" }
                val (title, url, thumb) = DlnaClient.firstPhotoUrl(loc)
                val dir = java.io.File(ctx.filesDir, "a6000").apply { mkdirs() }
                val safe = title.ifBlank { "photo" }.replace(Regex("[^A-Za-z0-9._-]"), "_")
                val dst = java.io.File(dir, safe.take(60))
                val bytes = DlnaClient.downloadToFile(url, dst)
                "title=$title\nurl=${url.take(200)}\nthumb=${thumb.take(200)}\n${ctx.getString(R.string.diag_saved_file, dst.absolutePath)}\n${bytes} bytes"
            },
            stringResource(R.string.diag_port_scan) to suspend {
                val open = PortScan.scan("10.0.0.1")
                if (open.isEmpty()) ctx.getString(R.string.diag_no_ports) else "OPEN: " + open.joinToString(", ")
            },
            stringResource(R.string.diag_get_dd) to suspend {
                val ddl = SsdpDiscovery.discover(ctx).firstOrNull()?.location
                    ?: "$baseUrl/sony/dd.xml"
                val raw = SonyClient.get(ddl)
                val xml = raw.substringAfter("\n", raw)
                val svcs = SonyClient.parseDeviceDesc(xml)
                raw + "\n[parse] " + if (svcs.isEmpty()) ctx.getString(R.string.diag_no_services) else svcs.joinToString("; ") { "${it.type} -> ${it.actionListUrl}" }
            },
            "system/getVersions" to suspend { SonyClient.post("system", "getVersions") },
            "camera/getAvailableApiList" to suspend { SonyClient.post("camera", "getAvailableApiList") },
            "camera/getApplicationInfo" to suspend { SonyClient.post("camera", "getApplicationInfo") },
            "guide/ supportedFunc" to suspend { SonyClient.post("guide", "getSupportedCameraFunction") },
            "avContent/getSourceList" to suspend {
                SonyClient.post("avContent", "getSourceList", "[{\"scheme\":\"storage\"}]")
            },
        )
        probes.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), Arrangement.spacedBy(8.dp)) {
                row.forEach { (label, fn) ->
                    Button(onClick = { runTask(label, fn) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Text(label, fontSize = 12.sp)
                    }
                }
            }
        }

        Text(stringResource(R.string.diag_tester), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(6.dp)) {
            listOf("camera", "avContent", "system", "guide").forEach { s ->
                OutlinedButton(onClick = { svc = s }) { Text(s, fontSize = 11.sp) }
            }
        }
        Text("service=$svc", fontSize = 12.sp)
        OutlinedTextField(value = method, onValueChange = { method = it }, label = { Text("method") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(value = params, onValueChange = { params = it }, label = { Text(stringResource(R.string.diag_params)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(value = version, onValueChange = { version = it }, label = { Text("version") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Button(
            onClick = { runTask("$svc/$method") { SonyClient.post(svc, method.trim(), params.ifBlank { "[]" }, version.ifBlank { "1.0" }) } },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        ) { Text(stringResource(R.string.diag_send)) }

        Text(stringResource(R.string.diag_logs), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
        Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            logs.forEach { line ->
                Text(line, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(vertical = 4.dp))
            }
        }
    }
}

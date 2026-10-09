package jp.yuki.a6000transfer.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jp.yuki.a6000transfer.R
import jp.yuki.a6000transfer.data.DlnaRepository
import jp.yuki.a6000transfer.sony.BindResult
import jp.yuki.a6000transfer.sony.CameraProfile
import jp.yuki.a6000transfer.sony.CameraSsidResolver
import jp.yuki.a6000transfer.sony.WifiBinder
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ConnectScreen(ctx: Context, appState: AppState) {
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("a6000diag", Context.MODE_PRIVATE) }
    var ssid by remember { mutableStateOf(prefs.getString("ssid", "DIRECT-cNE0:a6000") ?: "DIRECT-cNE0:a6000") }
    var pass by remember { mutableStateOf(prefs.getString("pass", "") ?: "") }
    var scanning by rememberSaveable { mutableStateOf(false) }
    var scanned by remember { mutableStateOf(false) }
    var scanNeedsReview by remember { mutableStateOf(false) }
    var scanReviewed by remember { mutableStateOf(false) }
    var showPass by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(ctx.getString(R.string.status_not_connected)) }
    var discovering by remember { mutableStateOf(false) }
    var remoteApi by remember { mutableStateOf<String?>(null) }
    var wasBound by remember { mutableStateOf(false) }
    val bound by appState.boundSsid.collectAsState()
    val location by appState.location.collectAsState()
    val model by appState.cameraModel.collectAsState()
    val strConnecting = stringResource(R.string.connecting)
    val strDiscovering = stringResource(R.string.discovering)
    val strDiscovered = stringResource(R.string.discovered)
    val strDiscoverFail = stringResource(R.string.discover_fail)

    LaunchedEffect(Unit) {
        val saved = prefs.getString("model", CameraProfile.AUTO) ?: CameraProfile.AUTO
        appState.setModel(saved)
    }

    // バインド喪失（カメラAP切断等）をステータスに反映。成功表示中の喪失のみ戻す
    LaunchedEffect(bound) {
        if (bound == null && wasBound && !busy && !discovering) {
            wasBound = false
            status = ctx.getString(R.string.status_not_connected)
        }
    }

    if (scanning) {
        CameraScanScreen(
            onCredentials = { reading ->
                val hasLocation = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                val hasNearby = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
                val nearby = if (hasLocation && hasNearby) {
                    try {
                        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                        @Suppress("DEPRECATION")
                        wifi.scanResults.map { it.SSID }
                    } catch (_: Exception) {
                        emptyList()
                    }
                } else emptyList()
                val previouslyConnected = prefs.getString("ssid", null)
                ssid = CameraSsidResolver.resolve(reading.credentials.ssid, nearby, previouslyConnected)
                pass = reading.credentials.passphrase
                scanned = true
                scanNeedsReview = !reading.passwordConfirmed ||
                    (reading.ssidNeedsReview && ssid !in nearby)
                scanReviewed = false
                showPass = false
                scanning = false
                status = ctx.getString(if (scanNeedsReview) R.string.scan_uncertain else R.string.scan_ready)
            },
            onBack = { scanning = false },
        )
        return
    }

    suspend fun performDiscovery() {
        discovering = true
        status = strDiscovering
        remoteApi = null
        try {
            val found = kotlinx.coroutines.withTimeoutOrNull(120_000) {
                DlnaRepository.discoverCamera(ctx, model)
            }
            val loc = found?.locationUrl
            remoteApi = found?.remoteApiBase
            if (loc != null) {
                appState.setLocation(loc)
                status = strDiscovered
            } else if (remoteApi != null) {
                status = ctx.getString(R.string.remote_api_only)
            } else {
                status = strDiscoverFail
            }
        } catch (e: Exception) {
            status = ctx.getString(R.string.failed, e.message ?: "?")
        } finally {
            discovering = false
        }
    }

    fun bind() {
        if (busy) return
        busy = true
        status = strConnecting
        appState.setLocation(null)
        scope.launch {
            try {
                val res = kotlinx.coroutines.withTimeoutOrNull(100_000) {
                    WifiBinder.requestBindAwait(ctx, ssid.trim(), pass)
                } ?: BindResult.Ng(ctx.getString(R.string.bind_timeout))
                when (res) {
                    is BindResult.Ok -> {
                        prefs.edit().putString("ssid", ssid.trim()).putString("pass", pass).apply()
                        status = ctx.getString(R.string.bound_as, res.ssid)
                        appState.setBound(res.ssid)
                        wasBound = true
                        if (scanned) {
                            scanned = false
                            performDiscovery()
                        }
                    }
                    is BindResult.Ng -> {
                        status = ctx.getString(R.string.failed, res.reason)
                        appState.setBound(null)
                    }
                }
            } catch (e: Exception) {
                status = ctx.getString(R.string.failed, e.message ?: "?")
                appState.setBound(null)
            } finally {
                busy = false
            }
        }
    }

    fun discover() {
        if (discovering) return
        scope.launch { performDiscovery() }
    }

    val working = busy || discovering
    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
        // ステータスヒーロー
        Card(
            Modifier.fillMaxWidth().padding(bottom = 16.dp),
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Column(Modifier.padding(20.dp), Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.connect_step1),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(12.dp)) {
                    if (working) LoadingIndicator()
                    Text(
                        ctx.getString(R.string.status, status),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // 機種選択
        Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(20.dp), Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.model_title), style = MaterialTheme.typography.titleMedium)
                val models = listOf(
                    CameraProfile.AUTO to stringResource(R.string.model_auto),
                    CameraProfile.A6000 to "α6000",
                    CameraProfile.A6100 to "α6100+",
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    models.forEachIndexed { i, (id, label) ->
                        SegmentedButton(
                            selected = model == id,
                            onClick = {
                                appState.setModel(id)
                                prefs.edit().putString("model", id).apply()
                            },
                            shape = SegmentedButtonDefaults.itemShape(i, models.size),
                        ) { Text(label, fontSize = 13.sp, maxLines = 1) }
                    }
                }
                Text(stringResource(R.string.model_note), fontSize = 12.sp)
            }
        }

        // AP接続
        Card(Modifier.fillMaxWidth().padding(top = 16.dp), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(20.dp), Arrangement.spacedBy(12.dp)) {
                Button(onClick = { scanning = true }, enabled = !working) {
                    Text(stringResource(R.string.scan_start))
                }
                Text(stringResource(R.string.scan_entry_hint), style = MaterialTheme.typography.bodySmall)
                if (scanned) {
                    Text(
                        stringResource(if (scanNeedsReview) R.string.scan_review_uncertain else R.string.scan_review),
                        color = if (scanNeedsReview) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                    if (scanNeedsReview) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = scanReviewed,
                                onCheckedChange = { scanReviewed = it },
                                enabled = showPass,
                            )
                            Text(
                                stringResource(R.string.scan_confirm_review),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = ssid,
                    onValueChange = {
                        ssid = it
                        if (scanNeedsReview) scanReviewed = false
                    },
                    label = { Text(stringResource(R.string.ssid)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                )
                OutlinedTextField(
                    value = pass,
                    onValueChange = {
                        pass = it
                        if (scanNeedsReview) scanReviewed = false
                    },
                    label = { Text(stringResource(R.string.passphrase)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { showPass = !showPass }) {
                            Text(stringResource(if (showPass) R.string.hide_password else R.string.show_password))
                        }
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { bind() }, enabled = !working && (!scanNeedsReview || scanReviewed)) {
                        Text(stringResource(R.string.connect_bind))
                    }
                    OutlinedButton(onClick = {
                        WifiBinder.unbind(ctx)
                        appState.setBound(null)
                        wasBound = false
                        status = ctx.getString(R.string.status_not_connected)
                    }) { Text(stringResource(R.string.release)) }
                }
            }
        }

        // 探索
        Card(
            Modifier.fillMaxWidth().padding(top = 16.dp),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        ) {
            Column(Modifier.padding(20.dp), Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.connect_step2),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    stringResource(R.string.discover_hint),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Button(onClick = { discover() }, enabled = !working && bound != null) {
                    Text(
                        if (bound == null) stringResource(R.string.discover_need_bind)
                        else stringResource(R.string.discover),
                    )
                }
                Text(
                    ctx.getString(R.string.location, location ?: ctx.getString(R.string.location_unknown)),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                if (remoteApi != null) {
                    Text(
                        "Remote API: $remoteApi",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }

        // 手順
        Card(Modifier.fillMaxWidth().padding(top = 16.dp), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(20.dp), Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.howto_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.howto_1), fontSize = 13.sp)
                Text(stringResource(R.string.howto_2), fontSize = 13.sp)
                Text(stringResource(R.string.howto_3), fontSize = 13.sp)
            }
        }
    }
}

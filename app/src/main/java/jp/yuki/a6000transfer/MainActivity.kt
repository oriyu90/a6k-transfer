package jp.yuki.a6000transfer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import jp.yuki.a6000transfer.ui.AppState
import jp.yuki.a6000transfer.ui.ConnectScreen
import jp.yuki.a6000transfer.ui.DiagScreen
import jp.yuki.a6000transfer.ui.GalleryScreen
import jp.yuki.a6000transfer.ui.SettingsScreen
import jp.yuki.a6000transfer.ui.applySavedLanguage
import jp.yuki.a6000transfer.ui.theme.A6000Theme
import jp.yuki.a6000transfer.ui.theme.ThemeMode
import jp.yuki.a6000transfer.ui.theme.savedThemeMode

class MainActivity : AppCompatActivity() {

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { }

    @OptIn(ExperimentalMaterial3Api::class)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySavedLanguage(this)
        // デバッグ用: am start --es ssid ... --es pass ... で接続情報を注入できる
        val prefsDbg = getSharedPreferences("a6000diag", MODE_PRIVATE)
        val eSsid = intent?.getStringExtra("ssid")
        val ePass = intent?.getStringExtra("pass")
        if (eSsid != null || ePass != null) {
            prefsDbg.edit().apply {
                if (eSsid != null) putString("ssid", eSsid)
                if (ePass != null) putString("pass", ePass)
            }.apply()
        }
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.CHANGE_WIFI_MULTICAST_STATE,
        )
        if (Build.VERSION.SDK_INT >= 31) perms += Manifest.permission.NEARBY_WIFI_DEVICES
        if (Build.VERSION.SDK_INT >= 33) {
            perms += Manifest.permission.READ_MEDIA_IMAGES
            perms += Manifest.permission.READ_MEDIA_VIDEO
            perms += Manifest.permission.POST_NOTIFICATIONS
        } else {
            perms += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permLauncher.launch(missing.toTypedArray())
        setContent {
            val appState = remember { AppState() }
            var tab by remember { mutableIntStateOf(1) }
            var themeMode by remember { mutableStateOf(savedThemeMode(this@MainActivity)) }
            // バインド喪失は画面に依存せずAppStateへ集約（Contextを捕まえない）
            jp.yuki.a6000transfer.sony.WifiBinder.onLostListener = { appState.setBound(null) }
            A6000Theme(themeMode) {
                Surface(Modifier.fillMaxSize()) {
                    Scaffold(
                        topBar = {
                            CenterAlignedTopAppBar(
                                title = {
                                    Text(
                                        stringResource(R.string.app_name),
                                        style = MaterialTheme.typography.titleLarge,
                                    )
                                },
                            )
                        },
                        bottomBar = {
                            NavigationBar {
                                NavigationBarItem(
                                    selected = tab == 0,
                                    onClick = { tab = 0 },
                                    label = { Text(stringResource(R.string.tab_transfer)) },
                                    icon = { Text("⇩") },
                                )
                                NavigationBarItem(
                                    selected = tab == 1,
                                    onClick = { tab = 1 },
                                    label = { Text(stringResource(R.string.tab_connect)) },
                                    icon = { Text("≋") },
                                )
                                NavigationBarItem(
                                    selected = tab == 2,
                                    onClick = { tab = 2 },
                                    label = { Text(stringResource(R.string.tab_diag)) },
                                    icon = { Text("⚙") },
                                )
                                NavigationBarItem(
                                    selected = tab == 3,
                                    onClick = { tab = 3 },
                                    label = { Text(stringResource(R.string.tab_settings)) },
                                    icon = { Text("⋯") },
                                )
                            }
                        },
                    ) { inner ->
                        Box(
                            Modifier.padding(inner).fillMaxSize(),
                        ) {
                            when (tab) {
                                0 -> GalleryScreen(this@MainActivity, appState)
                                1 -> ConnectScreen(this@MainActivity, appState)
                                2 -> DiagScreen(this@MainActivity, appState)
                                else -> SettingsScreen(this@MainActivity)
                            }
                        }
                    }
                }
            }
        }
    }
}

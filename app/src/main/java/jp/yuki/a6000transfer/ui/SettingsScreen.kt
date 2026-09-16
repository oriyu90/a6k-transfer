package jp.yuki.a6000transfer.ui

import android.app.Activity
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Row
import androidx.core.os.LocaleListCompat
import jp.yuki.a6000transfer.R
import jp.yuki.a6000transfer.ui.theme.ThemeMode
import jp.yuki.a6000transfer.ui.theme.applyThemeMode
import jp.yuki.a6000transfer.ui.theme.savedThemeMode

private const val PREF_LANG = "lang"

fun savedLanguage(context: Context): String =
    context.getSharedPreferences("a6000diag", Context.MODE_PRIVATE)
        .getString(PREF_LANG, "system") ?: "system"

fun applyLanguage(context: Context, tag: String) {
    context.getSharedPreferences("a6000diag", Context.MODE_PRIVATE)
        .edit().putString(PREF_LANG, tag).apply()
    val locales = when (tag) {
        "ja" -> LocaleListCompat.forLanguageTags("ja")
        "en" -> LocaleListCompat.forLanguageTags("en")
        else -> LocaleListCompat.getEmptyLocaleList()
    }
    AppCompatDelegate.setApplicationLocales(locales)
}

/** 起動時に保存済み言語を適用（Application未使用のためActivity側から呼ぶ） */
fun applySavedLanguage(context: Context) {
    val tag = savedLanguage(context)
    if (tag != "system") {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(ctx: Context) {
    var current by remember { mutableStateOf(savedLanguage(ctx)) }
    var theme by remember { mutableStateOf(savedThemeMode(ctx)) }
    Column(
        Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(
            Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        ) {
            Column(Modifier.padding(20.dp), Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.settings_language),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                val options = listOf(
                    "system" to stringResource(R.string.lang_system),
                    "ja" to stringResource(R.string.lang_ja),
                    "en" to stringResource(R.string.lang_en),
                )
                options.forEach { (tag, label) ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            current = tag
                            applyLanguage(ctx, tag)
                            (ctx as? Activity)?.recreate()
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = current == tag,
                            onClick = {
                                current = tag
                                applyLanguage(ctx, tag)
                                (ctx as? Activity)?.recreate()
                            },
                        )
                        Text(
                            label,
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
                Text(
                    stringResource(R.string.settings_note),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }

        Card(
            Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        ) {
            Column(Modifier.padding(20.dp), Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.theme_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                val modes = listOf(
                    ThemeMode.SYSTEM to stringResource(R.string.theme_system),
                    ThemeMode.LIGHT to stringResource(R.string.theme_light),
                    ThemeMode.DARK to stringResource(R.string.theme_dark),
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    modes.forEachIndexed { i, (m, label) ->
                        SegmentedButton(
                            selected = theme == m,
                            onClick = {
                                theme = m
                                applyThemeMode(ctx, m)
                            },
                            shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                        ) { Text(label) }
                    }
                }
                Text(
                    stringResource(R.string.theme_note),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
    }
}

package jp.yuki.a6000transfer.ui.theme

import android.app.Activity
import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** アプリ内テーマモード。システム追従／ライト固定／ダーク固定 */
enum class ThemeMode(val pref: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
    ;

    companion object {
        fun of(pref: String?): ThemeMode = entries.firstOrNull { it.pref == pref } ?: SYSTEM
    }
}

private const val PREF_THEME = "theme"

fun savedThemeMode(context: Context): ThemeMode =
    ThemeMode.of(
        context.getSharedPreferences("a6000diag", Context.MODE_PRIVATE)
            .getString(PREF_THEME, ThemeMode.SYSTEM.pref),
    )

/** 保存してActivity再生成（即時反映）。言語切替と同方式 */
fun applyThemeMode(context: Context, mode: ThemeMode) {
    context.getSharedPreferences("a6000diag", Context.MODE_PRIVATE)
        .edit().putString(PREF_THEME, mode.pref).apply()
    (context as? Activity)?.recreate()
}

/** M3 Expressive寄りの大きな角。角のバリエーションで階層を付けるのがExpressiveの戦術 */
val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(24.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

private val ExpressiveLight = lightColorScheme(
    primary = Color(0xFF4A36C4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE2DBFF),
    onPrimaryContainer = Color(0xFF180096),
    secondary = Color(0xFF00696B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF6FF7FA),
    onSecondaryContainer = Color(0xFF002929),
    tertiary = Color(0xFFB02A82),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFD8EC),
    onTertiaryContainer = Color(0xFF3E0022),
    surfaceContainerLowest = Color.White,
    surfaceContainerHighest = Color(0xFFEEE9F5),
)

private val ExpressiveDark = darkColorScheme(
    primary = Color(0xFFC5B8FF),
    onPrimary = Color(0xFF27008F),
    primaryContainer = Color(0xFF3B1ED4),
    onPrimaryContainer = Color(0xFFE2DBFF),
    secondary = Color(0xFF4ED9DD),
    onSecondary = Color(0xFF003334),
    secondaryContainer = Color(0xFF004F52),
    onSecondaryContainer = Color(0xFF6FF7FA),
    tertiary = Color(0xFFFFAFD4),
    onTertiary = Color(0xFF5E0039),
    tertiaryContainer = Color(0xFF8A1A60),
    onTertiaryContainer = Color(0xFFFFD8EC),
    surfaceContainerHighest = Color(0xFF2B2833),
)

/**
 * アプリ全体テーマ。Android 12+ではダイナミックカラー（Material You）を優先し、
 * それ以外はExpressiveなビビッド配色＋大きな角でまとめる。
 * MotionScheme等の1.5.0-alpha系APIは使わずstableのみで構成。
 */
@Composable
fun A6000Theme(mode: ThemeMode, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = when (mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        dark -> ExpressiveDark
        else -> ExpressiveLight
    }
    androidx.compose.material3.MaterialTheme(
        colorScheme = scheme,
        shapes = ExpressiveShapes,
        content = content,
    )
}

package ch.ripple.app.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val colors = lightColorScheme(
    primary = Color(0xFF9B242C), onPrimary = Color.White,
    secondary = Color(0xFF242727), onSecondary = Color.White,
    secondaryContainer = Color(0xFFF1DFDB), onSecondaryContainer = Color(0xFF6D1B21),
    background = Color(0xFFFAF8F3), onBackground = Color(0xFF242727),
    surface = Color(0xFFFAF8F3), onSurface = Color(0xFF242727),
    surfaceVariant = Color(0xFFF0EDE6), onSurfaceVariant = Color(0xFF555954),
    outline = Color(0xFF777971), outlineVariant = Color(0xFFD9D6CE),
    error = Color(0xFF9B242C),
)
private val typography = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.Serif, fontSize = 62.sp, lineHeight = 70.sp),
    displaySmall = TextStyle(fontFamily = FontFamily.Serif, fontSize = 40.sp, lineHeight = 48.sp),
    headlineLarge = TextStyle(fontFamily = FontFamily.Serif, fontSize = 34.sp, lineHeight = 42.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.Serif, fontSize = 28.sp, lineHeight = 36.sp),
    titleLarge = TextStyle(fontSize = 23.sp, lineHeight = 31.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 20.sp, lineHeight = 30.sp),
    bodyMedium = TextStyle(fontSize = 18.sp, lineHeight = 27.sp),
    bodySmall = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    labelLarge = TextStyle(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium),
)
@Composable
fun RippleTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}

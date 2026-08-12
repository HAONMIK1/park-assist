package com.parkassist.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * 다크 테마 고정.
 *
 * 라이트 테마와 Dynamic Color를 쓰지 않는 이유: 경보 색(초록/주황/빨강)이 화면마다
 * 달라지면 안 되기 때문이다. 색이 곧 안전 정보다.
 */
private val DarkColors = darkColorScheme(
    primary = RingAccent,
    onPrimary = Background,
    secondary = LevelCaution,
    background = Background,
    onBackground = OnBackground,
    surface = Surface,
    onSurface = OnBackground,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = OnSurfaceMuted,
    outline = Outline,
    error = LevelDanger,
    onError = OnBackground,
)

@Composable
fun ParkAssistTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = ParkAssistTypography,
        content = content,
    )
}

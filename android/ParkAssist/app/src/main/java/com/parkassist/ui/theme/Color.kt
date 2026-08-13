package com.parkassist.ui.theme

import androidx.compose.ui.graphics.Color
import com.parkassist.protocol.ProximityLevel

// 배경/표면 — 차 안에서 밤에 봐도 눈부시지 않게 아주 어둡게.
val Background = Color(0xFF070A0F)
val Surface = Color(0xFF141922)
val SurfaceVariant = Color(0xFF1E2530)
val Outline = Color(0xFF39424F)

// 글자 — 대비를 충분히 준다(대상 사용자 60대).
val OnBackground = Color(0xFFF2F5FA)
val OnSurfaceMuted = Color(0xFFA7B2C2)

/** 계기판 링 색. 레퍼런스의 파란 테두리. */
val RingAccent = Color(0xFF2E9BFF)

// 경보 단계 색 — 요구사항의 회색 / 초록 / 주황 / 빨강.
val LevelNone = Color(0xFF4A5462)
val LevelCaution = Color(0xFF2FD46B)
val LevelWarn = Color(0xFFFFA318)
val LevelDanger = Color(0xFFFF3B30)
val LevelCritical = Color(0xFFFF1F14)

/** 아직 켜지지 않은 부채꼴. 배경보다 아주 조금 밝게. */
val BandIdle = Color(0xFF232B36)

val CarBody = Color(0xFFD8DEE7)

/** 차체 위에 얹는 캐빈. 알파 0x56(34%)이라 차체 색이 비쳐 보인다. */
val CarGlass = Color(0x56617180)

val GuideLine = Color(0xFF3A4552)

fun ProximityLevel.color(): Color = when (this) {
    ProximityLevel.NONE -> LevelNone
    ProximityLevel.CAUTION -> LevelCaution
    ProximityLevel.WARN -> LevelWarn
    ProximityLevel.DANGER -> LevelDanger
    ProximityLevel.CRITICAL -> LevelCritical
}

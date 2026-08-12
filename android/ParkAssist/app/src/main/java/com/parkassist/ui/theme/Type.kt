package com.parkassist.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 대상 사용자가 60대다. Material 기본값보다 한 단계씩 키우고 굵기를 올렸다.
 * 시스템 글꼴 확대 설정도 그대로 따라가도록 sp를 쓴다.
 */
val ParkAssistTypography = Typography(
    displayLarge = TextStyle(fontSize = 72.sp, lineHeight = 76.sp, fontWeight = FontWeight.Bold),
    headlineLarge = TextStyle(fontSize = 40.sp, lineHeight = 46.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 22.sp, lineHeight = 30.sp),
    bodyMedium = TextStyle(fontSize = 20.sp, lineHeight = 28.sp),
    labelLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
)

/** 최단 거리 숫자 전용. 화면에서 가장 큰 글자다. */
val DistanceDisplay = TextStyle(
    fontSize = 120.sp,
    lineHeight = 124.sp,
    fontWeight = FontWeight.Bold,
)

/** "매우 가까움"처럼 숫자 대신 들어가는 문구. */
val DistanceDisplayText = TextStyle(
    fontSize = 64.sp,
    lineHeight = 70.sp,
    fontWeight = FontWeight.Bold,
)

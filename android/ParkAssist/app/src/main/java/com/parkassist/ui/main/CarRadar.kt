package com.parkassist.ui.main

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.parkassist.protocol.ChannelState
import com.parkassist.protocol.ProximityLevel
import com.parkassist.protocol.SensorZone
import com.parkassist.ui.theme.BandIdle
import com.parkassist.ui.theme.CarBody
import com.parkassist.ui.theme.CarGlass
import com.parkassist.ui.theme.GuideLine
import com.parkassist.ui.theme.RingAccent
import com.parkassist.ui.theme.color
import kotlin.math.min

/**
 * 계기판식 전후방 표시.
 *
 * 위에서 본 차량의 **앞뒤 양쪽**에 부채꼴 4개씩(센서 CH1~CH4, 좌 → 우)을 그린다.
 * 앞은 PARK-02, 뒤는 PARK-01이 보내는 값이다.
 *
 * 각 부채꼴은 3단 밴드로 나뉘고, 가까워질수록 **바깥쪽부터 안쪽으로 차오르면서 색이
 * 뜨거워진다.** 위험 단계에서는 차체 바로 옆까지 빨강이 채워진다.
 *
 * - 기준 밖: 회색(꺼짐)
 * - 주의: 초록 1칸
 * - 경고: 주황 2칸
 * - 위험: 빨강 3칸
 * - 사각지대(측정 실패인데 직전까지 가까웠음): 빨강 3칸 + 깜빡임
 */
@Composable
fun CarRadar(
    rear: List<ChannelState>,
    front: List<ChannelState>,
    modifier: Modifier = Modifier,
) {
    val pulse by rememberInfiniteTransition(label = "critical").animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(420), RepeatMode.Reverse),
        label = "criticalAlpha",
    )

    Canvas(modifier = modifier) {
        val radius = min(size.width, size.height) / 2f
        val center = Offset(size.width / 2f, size.height / 2f)

        drawRing(center, radius)

        val carWidth = radius * CAR_WIDTH_RATIO
        val carHeight = radius * CAR_HEIGHT_RATIO
        val carTop = center.y - carHeight / 2f
        val carRear = Offset(center.x, center.y + carHeight / 2f)
        val carFront = Offset(center.x, carTop)

        drawGuideLines(carRear, carWidth, radius)

        drawBands(carRear, radius, rear, SensorZone.REAR, pulse)
        drawBands(carFront, radius, front, SensorZone.FRONT, pulse)

        drawCar(center.x, carTop, carWidth, carHeight)
    }
}

private fun DrawScope.drawRing(center: Offset, radius: Float) {
    drawCircle(
        color = RingAccent.copy(alpha = 0.45f),
        radius = radius - radius * 0.012f,
        center = center,
        style = Stroke(width = radius * 0.024f),
    )
    drawCircle(
        color = RingAccent.copy(alpha = 0.10f),
        radius = radius * 0.93f,
        center = center,
        style = Stroke(width = radius * 0.008f),
    )
}

/** 후진 가이드선. 레퍼런스 계기판의 바닥 유도선 느낌만 낸다. */
private fun DrawScope.drawGuideLines(origin: Offset, carWidth: Float, radius: Float) {
    val halfCar = carWidth / 2f
    val drop = radius * 0.34f
    val spread = carWidth * 0.34f
    val stroke = radius * 0.010f

    listOf(-1f, 1f).forEach { side ->
        drawLine(
            color = GuideLine,
            start = Offset(origin.x + side * halfCar, origin.y),
            end = Offset(origin.x + side * (halfCar + spread), origin.y + drop),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

private fun DrawScope.drawBands(
    origin: Offset,
    radius: Float,
    channels: List<ChannelState>,
    zone: SensorZone,
    pulseAlpha: Float,
) {
    val bandStroke = radius * BAND_STROKE_RATIO
    val sectorSweep = TOTAL_SWEEP / BAND_SECTORS

    channels.forEachIndexed { index, channel ->
        val litCount = channel.level.litBands()
        val isCritical = channel.level == ProximityLevel.CRITICAL

        // 캔버스 각도는 0°가 3시 방향, 시계 방향이 +다.
        // 뒤쪽(15°~165°)은 각도가 커질수록 왼쪽, 앞쪽(195°~345°)은 반대다.
        // 어느 쪽이든 index 0 = CH1 = 차량 왼쪽이 되도록 맞춘다.
        val sectorStart = when (zone) {
            SensorZone.REAR -> REAR_START_ANGLE + (BAND_SECTORS - 1 - index) * sectorSweep
            SensorZone.FRONT -> FRONT_START_ANGLE + index * sectorSweep
        }

        for (band in 0 until BAND_COUNT) {
            // 바깥쪽(band = BAND_COUNT-1)부터 차오른다.
            val lit = band >= BAND_COUNT - litCount
            val bandRadius = radius * (FIRST_BAND_RATIO + band * BAND_SPACING_RATIO)

            val color: Color = when {
                !lit -> BandIdle
                isCritical -> channel.level.color().copy(alpha = pulseAlpha)
                else -> channel.level.color()
            }

            drawArc(
                color = color,
                startAngle = sectorStart + SECTOR_GAP / 2f,
                sweepAngle = sectorSweep - SECTOR_GAP,
                useCenter = false,
                topLeft = Offset(origin.x - bandRadius, origin.y - bandRadius),
                size = Size(bandRadius * 2f, bandRadius * 2f),
                style = Stroke(width = bandStroke, cap = StrokeCap.Round),
            )
        }
    }
}

/** 위에서 본 차. 방향이 헷갈리지 않도록 앞유리를 위쪽에 둔다. */
private fun DrawScope.drawCar(centerX: Float, top: Float, width: Float, height: Float) {
    val left = centerX - width / 2f
    val corner = CornerRadius(width * 0.32f, width * 0.32f)

    drawRoundRect(
        color = CarBody,
        topLeft = Offset(left, top),
        size = Size(width, height),
        cornerRadius = corner,
    )

    // 캐빈 — 차 앞쪽(위)에 치우치게 둬서 앞뒤를 구분한다.
    val cabinWidth = width * 0.72f
    drawRoundRect(
        color = CarGlass,
        topLeft = Offset(centerX - cabinWidth / 2f, top + height * 0.20f),
        size = Size(cabinWidth, height * 0.38f),
        cornerRadius = CornerRadius(width * 0.18f, width * 0.18f),
    )

    // 사이드미러
    val mirrorWidth = width * 0.13f
    val mirrorHeight = height * 0.07f
    val mirrorY = top + height * 0.24f
    listOf(left - mirrorWidth * 0.7f, left + width - mirrorWidth * 0.3f).forEach { x ->
        drawRoundRect(
            color = CarBody,
            topLeft = Offset(x, mirrorY),
            size = Size(mirrorWidth, mirrorHeight),
            cornerRadius = CornerRadius(mirrorWidth / 2f, mirrorWidth / 2f),
        )
    }
}

private fun ProximityLevel.litBands(): Int = when (this) {
    ProximityLevel.NONE -> 0
    ProximityLevel.CAUTION -> 1
    ProximityLevel.WARN -> 2
    ProximityLevel.DANGER, ProximityLevel.CRITICAL -> 3
}

// 부채꼴 배치 — 0°가 3시 방향, 시계 방향이 +.
private const val REAR_START_ANGLE = 15f
private const val FRONT_START_ANGLE = 195f
private const val TOTAL_SWEEP = 150f
private const val BAND_SECTORS = 4
private const val SECTOR_GAP = 3.5f

// 아래 비율은 전부 반지름(radius) 기준이다. 바깥쪽 끝이 링에 닿지 않도록 잡혀 있다.
//
//   차 절반          0.310
// + 가장 바깥 밴드   0.490  (0.20 + 2 × 0.145)
// + 밴드 두께 절반   0.043
// ─────────────────────────
//   최대 반경        0.843   ← 링 안쪽(약 0.976)까지 여유 0.13
//
// 대상 사용자가 60대라 크게 보이는 쪽이 낫다. 이 값을 키울 때는 위 합이 0.95를
// 넘지 않는지 확인할 것 — 넘으면 부채꼴이 링을 뚫고 나간다.
private const val BAND_COUNT = 3
private const val FIRST_BAND_RATIO = 0.20f
private const val BAND_SPACING_RATIO = 0.145f
private const val BAND_STROKE_RATIO = 0.085f

private const val CAR_WIDTH_RATIO = 0.32f
private const val CAR_HEIGHT_RATIO = 0.62f

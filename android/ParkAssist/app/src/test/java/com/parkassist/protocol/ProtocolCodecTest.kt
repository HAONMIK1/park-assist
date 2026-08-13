package com.parkassist.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * docs/ble-protocol.md 의 예시를 그대로 고정한다.
 * 이 테스트가 깨지면 코드가 아니라 문서와 어긋난 것이므로 양쪽을 같이 확인해야 한다.
 */
class ProtocolCodecTest {

    @Test
    fun `문서의 거리기준 예시와 바이트열이 일치한다`() {
        val packet = ProtocolCodec.encodeThresholds(Thresholds(nearCm = 25, midCm = 50, farCm = 100))

        assertArrayEquals(
            byteArrayOf(0xBB.toByte(), 25, 50, 100, 0xF4.toByte()),
            packet,
        )
    }

    @Test
    fun `문서의 측정값 예시를 파싱한다`() {
        val bytes = byteArrayOf(
            0xAA.toByte(), 45, 120, 0xFF.toByte(), 200.toByte(), 0xC8.toByte()
        )

        val frame = ProtocolCodec.parseTelemetry(bytes)

        requireNotNull(frame)
        assertEquals(listOf(45, 120, null, 200), frame.distances.map { it.cm })
    }

    @Test
    fun `0xFF는 255cm가 아니라 측정 실패다`() {
        val bytes = telemetry(0xFF, 0xFF, 0xFF, 0xFF)

        val frame = ProtocolCodec.parseTelemetry(bytes)

        requireNotNull(frame)
        assertEquals(listOf(false, false, false, false), frame.distances.map { it.isValid })
        // 전부 실패면 "가장 가까운 거리"는 255cm가 아니라 없음이어야 한다.
        assertNull(frame.nearestCm)
    }

    @Test
    fun `측정 실패 채널은 최단거리 계산에서 빠진다`() {
        val frame = ProtocolCodec.parseTelemetry(telemetry(200, 0xFF, 45, 0xFF))

        assertEquals(45, frame?.nearestCm)
    }

    @Test
    fun `체크섬이 틀리면 버린다`() {
        val bytes = telemetry(45, 120, 200, 30)
        bytes[5] = (bytes[5] + 1).toByte()

        assertNull(ProtocolCodec.parseTelemetry(bytes))
    }

    @Test
    fun `길이가 다르면 버린다`() {
        assertNull(ProtocolCodec.parseTelemetry(byteArrayOf(0xAA.toByte(), 1, 2, 3, 4)))
        assertNull(ProtocolCodec.parseTelemetry(byteArrayOf(0xAA.toByte(), 1, 2, 3, 4, 0, 0)))
        assertNull(ProtocolCodec.parseTelemetry(ByteArray(0)))
    }

    @Test
    fun `패킷 타입이 다르면 버린다`() {
        val bytes = telemetry(45, 120, 200, 30)
        bytes[0] = 0xAB.toByte()
        bytes[5] = ProtocolCodec.checksum(bytes, 5)

        assertNull(ProtocolCodec.parseTelemetry(bytes))
    }

    @Test
    fun `전원 명령을 조립한다`() {
        // 0xCC ^ 0x01 = 0xCD
        assertArrayEquals(
            byteArrayOf(0xCC.toByte(), 0x01, 0xCD.toByte()),
            ProtocolCodec.encodePower(active = true),
        )
        // 0xCC ^ 0x00 = 0xCC
        assertArrayEquals(
            byteArrayOf(0xCC.toByte(), 0x00, 0xCC.toByte()),
            ProtocolCodec.encodePower(active = false),
        )
    }

    @Test
    fun `순서가 뒤집힌 기준은 전송하지 않는다`() {
        assertNull(ProtocolCodec.encodeThresholds(Thresholds(nearCm = 60, midCm = 30, farCm = 120)))
        assertNull(ProtocolCodec.encodeThresholds(Thresholds(nearCm = 30, midCm = 30, farCm = 120)))
        assertNull(ProtocolCodec.encodeThresholds(Thresholds(nearCm = 0, midCm = 60, farCm = 120)))
    }

    @Test
    fun `조립한 패킷은 스스로의 체크섬 검증을 통과한다`() {
        val packet = ProtocolCodec.encodeThresholds(Thresholds.Default)

        requireNotNull(packet)
        assertEquals(packet[4], ProtocolCodec.checksum(packet, 4))
    }

    /** 체크섬을 자동으로 채운 유효한 0xAA 패킷. */
    private fun telemetry(d1: Int, d2: Int, d3: Int, d4: Int): ByteArray {
        val bytes = byteArrayOf(
            0xAA.toByte(), d1.toByte(), d2.toByte(), d3.toByte(), d4.toByte(), 0,
        )
        bytes[5] = ProtocolCodec.checksum(bytes, 5)
        return bytes
    }
}

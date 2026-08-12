package com.parkassist.protocol

/**
 * 경보 단계. 선언 순서가 곧 심각도 순서다(ordinal 비교로 최악값을 고른다).
 */
enum class ProximityLevel {
    /** 기준 밖 — 회색 */
    NONE,

    /** 주의 — 초록 */
    CAUTION,

    /** 경고 — 주황 */
    WARN,

    /** 위험 — 빨강 */
    DANGER,

    /**
     * 사각지대 진입 — 빨강 + "매우 가까움".
     *
     * JSN-SR04T는 약 25cm 이내를 못 읽어 `0xFF`를 올린다. 가까워지다가 갑자기 측정
     * 실패로 바뀐 경우는 멀어진 게 아니라 사각지대에 들어간 것이므로 최고 단계로 본다.
     */
    CRITICAL,
    ;

    val isAlarming: Boolean get() = this != NONE
}

package com.parkassist.protocol

/**
 * 사용 모드.
 *
 * 두 모드는 **경보 규칙이 완전히 다르다.** 주차용 규칙을 주행 중에 그대로 쓰면
 * 뒤차가 1~2m에 붙어 있는 정상 주행 상황에서 쉬지 않고 울린다. 반대로 주행용 규칙을
 * 주차에 쓰면 천천히 다가가는 벽을 놓친다.
 */
enum class DriveMode {

    /**
     * 주차 — 거리 자체가 경보 기준.
     *
     * 거리에 따라 연속적으로 세지는 경고음([AlarmCurve])을 낸다.
     */
    PARKING,

    /**
     * 주행 — **급접근**이 경보 기준.
     *
     * 뒤차가 가까이 있는 것 자체는 정상이므로 울리지 않는다. 빠르게 좁혀 올 때만
     * ([ApproachDetector]) 크게 울린다. 내비게이션 음성 위로 들리도록 오디오 포커스를
     * 잠깐 가져간다.
     */
    DRIVING,
    ;

    /**
     * 이 모드에서 기기 부저가 쓸 기본 거리 기준.
     *
     * 주행 모드에서는 기기 부저까지 계속 울리면 못 견디므로 훨씬 좁게 잡는다.
     * 정말 닿기 직전(50cm 이내)에만 부저가 반응한다.
     */
    val defaultThresholds: Thresholds
        get() = when (this) {
            PARKING -> Thresholds(nearCm = 30, midCm = 60, farCm = 120)
            DRIVING -> Thresholds(nearCm = 20, midCm = 35, farCm = 50)
        }
}

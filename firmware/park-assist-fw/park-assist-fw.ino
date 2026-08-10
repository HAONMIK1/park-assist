/*
 * park-assist-fw — ESP32 4채널 초음파 주차 센서 펌웨어
 *
 * ⚠️ 스켈레톤이다. 구조와 핀맵/패킷 규격만 잡혀 있고 BLE 부분은 TODO다.
 *    실기기에서 컴파일·실측 검증되지 않았다.
 *
 * 보드: ESP32 Dev Module (DevKitC 30핀)
 * 규격: ../../docs/ble-protocol.md
 * 배선: ../../docs/hardware.md
 *
 * 설계 원칙 (CLAUDE.md):
 *   1. 부저는 BLE와 독립. 폰이 없어도 울린다.        → ADR 0001
 *   2. 센서는 순차 발사. 동시 발사 시 상호간섭.       → ADR 0002
 *   3. loop()에서 delay() 금지. millis() 논블로킹.
 *   4. 앱은 보조 표시 장치. 경보 판정은 여기서 한다.
 */

#include <Arduino.h>

// ───────────────────────────────────────────────
// 핀맵 (docs/hardware.md)
// ───────────────────────────────────────────────
static const uint8_t TRIG_PINS[4] = {13, 25, 26, 27};
static const uint8_t ECHO_PINS[4] = {34, 35, 36, 39};  // 모두 입력 전용 핀
static const uint8_t BUZZER_PIN   = 4;

static const uint8_t NUM_CH = 4;

// ───────────────────────────────────────────────
// 타이밍 (docs/hardware.md "타이밍 예산")
// ───────────────────────────────────────────────
static const uint32_t SLOT_MS         = 25;     // 채널당 슬롯 → 4ch × 25ms = 10Hz
static const uint32_t ECHO_TIMEOUT_US = 20000;  // 약 340cm
static const uint32_t TRIG_PULSE_US   = 10;
static const uint32_t US_PER_CM       = 58;     // 왕복 기준 (음속 34300cm/s)

// 사각지대 유지: 가까운 값을 보다가 측정 실패로 바뀌면 멀어진 게 아니라
// 사각지대(약 25cm 이내)에 들어간 것으로 보고 최고 경보를 유지한다.
static const uint32_t BLIND_HOLD_MS = 2000;
static const uint8_t  BLIND_MARGIN_CM = 10;

// ───────────────────────────────────────────────
// 프로토콜 (docs/ble-protocol.md)
// ───────────────────────────────────────────────
static const uint8_t PKT_TELEMETRY = 0xAA;
static const uint8_t PKT_THRESHOLD = 0xBB;
static const uint8_t PKT_POWER     = 0xCC;

static const uint8_t DIST_INVALID  = 0xFF;  // 측정 실패 / 미연결
static const uint8_t DIST_MAX      = 254;   // 0xFF가 예약되어 있으므로

static const uint8_t POWER_STANDBY = 0x00;
static const uint8_t POWER_ACTIVE  = 0x01;

// ───────────────────────────────────────────────
// 설정값 (0xBB / 0xCC로 변경, NVS에 저장)
// ───────────────────────────────────────────────
struct Settings {
  uint8_t near = 30;   // 위험
  uint8_t mid  = 60;   // 경고
  uint8_t far  = 120;  // 주의
  // 폰이 없어도 동작해야 하므로 부팅 기본값은 항상 작동이다 (ADR 0001).
  uint8_t power = POWER_ACTIVE;
};
static Settings g_settings;

// ───────────────────────────────────────────────
// 측정 상태
// ───────────────────────────────────────────────
static uint8_t  g_distCm[NUM_CH]      = {DIST_INVALID, DIST_INVALID, DIST_INVALID, DIST_INVALID};
static uint8_t  g_lastValidCm[NUM_CH] = {DIST_INVALID, DIST_INVALID, DIST_INVALID, DIST_INVALID};
static uint32_t g_lastValidMs[NUM_CH] = {0, 0, 0, 0};

static volatile uint32_t g_activeCh   = 0;
static volatile uint32_t g_echoStart  = 0;
static volatile uint32_t g_echoEnd    = 0;
static volatile bool     g_echoDone   = false;

static uint32_t g_slotStartMs = 0;
static uint32_t g_slotStartUs = 0;

// ───────────────────────────────────────────────
// 에코 인터럽트
//
// 한 번에 한 채널만 발사하므로(ADR 0002) 활성 채널 외의 엣지는 잔향/노이즈로 보고
// 버린다. digitalRead() 없이 "첫 엣지 = 상승, 두 번째 엣지 = 하강"으로 판별한다.
// ───────────────────────────────────────────────
static void IRAM_ATTR onEchoEdge(void *arg) {
  uint32_t ch = (uint32_t)(uintptr_t)arg;
  if (ch != g_activeCh) return;

  if (g_echoStart == 0) {
    g_echoStart = micros();
  } else if (!g_echoDone) {
    g_echoEnd  = micros();
    g_echoDone = true;
  }
}

// ───────────────────────────────────────────────
// 체크섬 — 마지막 바이트를 제외한 전체 XOR
// ───────────────────────────────────────────────
static uint8_t checksum(const uint8_t *buf, size_t len) {
  uint8_t x = 0;
  for (size_t i = 0; i < len; i++) x ^= buf[i];
  return x;
}

// ───────────────────────────────────────────────
// 측정 슬롯
// ───────────────────────────────────────────────
static void fireChannel(uint32_t ch) {
  g_activeCh  = ch;
  g_echoStart = 0;
  g_echoEnd   = 0;
  g_echoDone  = false;

  digitalWrite(TRIG_PINS[ch], LOW);
  delayMicroseconds(2);
  digitalWrite(TRIG_PINS[ch], HIGH);
  delayMicroseconds(TRIG_PULSE_US);  // 10µs — loop 예산에 영향 없는 수준
  digitalWrite(TRIG_PINS[ch], LOW);

  g_slotStartUs = micros();
  g_slotStartMs = millis();
}

// 슬롯이 끝났을 때 결과를 확정한다. 에코가 없으면 DIST_INVALID.
static void finishChannel(uint32_t ch) {
  uint8_t cm = DIST_INVALID;

  if (g_echoDone && g_echoEnd > g_echoStart) {
    uint32_t widthUs = g_echoEnd - g_echoStart;
    if (widthUs < ECHO_TIMEOUT_US) {
      uint32_t v = widthUs / US_PER_CM;
      cm = (v >= DIST_MAX) ? DIST_MAX : (uint8_t)v;
    }
  }

  g_distCm[ch] = cm;
  if (cm != DIST_INVALID) {
    g_lastValidCm[ch] = cm;
    g_lastValidMs[ch] = millis();
  }
}

// 사각지대 보정: 경보 판정에만 쓴다. BLE로는 규격대로 0xFF를 그대로 내보낸다.
static uint8_t effectiveCmForAlarm(uint32_t ch) {
  if (g_distCm[ch] != DIST_INVALID) return g_distCm[ch];

  uint8_t last = g_lastValidCm[ch];
  if (last == DIST_INVALID) return DIST_INVALID;

  bool wasClose = last <= (uint16_t)g_settings.near + BLIND_MARGIN_CM;
  bool recent   = (millis() - g_lastValidMs[ch]) < BLIND_HOLD_MS;
  return (wasClose && recent) ? 0 : DIST_INVALID;
}

// ───────────────────────────────────────────────
// 경보 (부저) — BLE 상태를 절대 참조하지 않는다 (ADR 0001)
// ───────────────────────────────────────────────
enum AlarmLevel : uint8_t { ALARM_OFF = 0, ALARM_CAUTION, ALARM_WARN, ALARM_DANGER };

static AlarmLevel currentAlarmLevel() {
  if (g_settings.power == POWER_STANDBY) return ALARM_OFF;

  uint16_t minCm = 0xFFFF;
  for (uint32_t ch = 0; ch < NUM_CH; ch++) {
    uint8_t cm = effectiveCmForAlarm(ch);
    if (cm != DIST_INVALID && cm < minCm) minCm = cm;
  }

  if (minCm == 0xFFFF)          return ALARM_OFF;
  if (minCm <= g_settings.near) return ALARM_DANGER;
  if (minCm <= g_settings.mid)  return ALARM_WARN;
  if (minCm <= g_settings.far)  return ALARM_CAUTION;
  return ALARM_OFF;
}

// 액티브 부저라 음색을 못 바꾼다. 단계는 울림 간격으로만 표현한다.
static uint16_t beepPeriodMs(AlarmLevel lv) {
  switch (lv) {
    case ALARM_CAUTION: return 800;
    case ALARM_WARN:    return 300;
    case ALARM_DANGER:  return 0;  // 연속음
    default:            return 0;
  }
}

static void updateBuzzer() {
  static uint32_t lastToggleMs = 0;
  static bool     buzzerOn     = false;

  AlarmLevel lv = currentAlarmLevel();

  if (lv == ALARM_OFF) {
    if (buzzerOn) { digitalWrite(BUZZER_PIN, LOW); buzzerOn = false; }
    return;
  }
  if (lv == ALARM_DANGER) {
    if (!buzzerOn) { digitalWrite(BUZZER_PIN, HIGH); buzzerOn = true; }
    return;
  }

  uint32_t now    = millis();
  uint16_t period = beepPeriodMs(lv);
  if (now - lastToggleMs >= (uint32_t)(period / 2)) {
    buzzerOn = !buzzerOn;
    digitalWrite(BUZZER_PIN, buzzerOn ? HIGH : LOW);
    lastToggleMs = now;
  }
}

// ───────────────────────────────────────────────
// BLE
// ───────────────────────────────────────────────
// TODO: BLEDevice로 PARK-01 광고 + NUS 계열 서비스/캐릭터리스틱 구성.
//       UUID는 docs/ble-protocol.md 참조.
static void bleSetup() {
  // TODO
}

static void bleNotifyTelemetry() {
  uint8_t pkt[6];
  pkt[0] = PKT_TELEMETRY;
  for (uint32_t ch = 0; ch < NUM_CH; ch++) pkt[1 + ch] = g_distCm[ch];
  pkt[5] = checksum(pkt, 5);

  // TODO: TX 캐릭터리스틱으로 notify.
  (void)pkt;
}

// RX write 콜백에서 호출한다. 체크섬이 틀리면 조용히 버린다.
static void bleHandleCommand(const uint8_t *data, size_t len) {
  if (len < 3) return;
  if (checksum(data, len - 1) != data[len - 1]) return;

  switch (data[0]) {
    case PKT_THRESHOLD: {
      if (len != 5) return;
      uint8_t n = data[1], m = data[2], f = data[3];
      if (!(n > 0 && n < m && m < f && f <= DIST_MAX)) return;  // 순서 위반은 거부
      g_settings.near = n;
      g_settings.mid  = m;
      g_settings.far  = f;
      // TODO: NVS 저장
      break;
    }
    case PKT_POWER: {
      if (len != 3) return;
      if (data[1] != POWER_STANDBY && data[1] != POWER_ACTIVE) return;
      g_settings.power = data[1];
      if (g_settings.power == POWER_STANDBY) digitalWrite(BUZZER_PIN, LOW);
      // TODO: NVS 저장
      break;
    }
    default:
      break;  // 모르는 패킷 타입은 무시
  }
}

// ───────────────────────────────────────────────
// setup / loop
// ───────────────────────────────────────────────
void setup() {
  Serial.begin(115200);

  pinMode(BUZZER_PIN, OUTPUT);
  digitalWrite(BUZZER_PIN, LOW);

  for (uint32_t ch = 0; ch < NUM_CH; ch++) {
    pinMode(TRIG_PINS[ch], OUTPUT);
    digitalWrite(TRIG_PINS[ch], LOW);
    pinMode(ECHO_PINS[ch], INPUT);  // 34/35/36/39는 내부 풀업 없음
    attachInterruptArg(digitalPinToInterrupt(ECHO_PINS[ch]),
                       onEchoEdge, (void *)(uintptr_t)ch, CHANGE);
  }

  // TODO: NVS에서 g_settings 복원

  bleSetup();
  fireChannel(0);
}

void loop() {
  uint32_t now = millis();

  // 슬롯 만료 → 결과 확정 후 다음 채널 발사 (delay() 없음)
  if (now - g_slotStartMs >= SLOT_MS) {
    uint32_t ch = g_activeCh;
    finishChannel(ch);

    uint32_t next = (ch + 1) % NUM_CH;
    if (next == 0) {
      bleNotifyTelemetry();  // 한 바퀴(=100ms)마다 1패킷 → 10Hz
    }

    if (g_settings.power == POWER_ACTIVE) {
      fireChannel(next);
    } else {
      // 대기 모드에서도 슬롯 타이머는 돌려서 재개 시 바로 이어가게 한다.
      g_activeCh    = next;
      g_slotStartMs = now;
    }
  }

  updateBuzzer();
}

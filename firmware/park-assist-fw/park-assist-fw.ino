/*
 * park-assist-fw — ESP32 4채널 초음파 주차 센서 펌웨어
 *
 * 보드: ESP32 Dev Module (DevKitC 30핀)
 * 규격: ../../docs/ble-protocol.md
 * 배선: ../../docs/hardware.md
 *
 * ⚠️ 실기기에서 컴파일·실측 검증되지 않았다.
 *
 * ── 기기 두 대 ─────────────────────────────────────────────────
 * 같은 펌웨어를 앞뒤 범퍼 유닛에 올린다. 아래 ZONE_IS_REAR 만 바꿔서 굽는다.
 *   후방 유닛 → PARK-01 (ZONE_IS_REAR 1)
 *   전방 유닛 → PARK-02 (ZONE_IS_REAR 0)
 * 두 유닛은 서비스/캐릭터리스틱 UUID가 같고 광고 이름만 다르다 (ADR 0004).
 *
 * ── 설계 원칙 (CLAUDE.md) ──────────────────────────────────────
 *   1. 부저는 BLE와 독립. 폰이 없어도 울린다.        → ADR 0001
 *   2. 센서는 순차 발사. 동시 발사 시 상호간섭.       → ADR 0002
 *   3. loop()에서 delay() 금지. millis() 논블로킹.
 *   4. 앱은 보조 표시 장치. 경보 판정은 여기서 한다.
 */

#include <Arduino.h>
#include <math.h>

#include <BLE2902.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <Preferences.h>

// ───────────────────────────────────────────────
// 이 유닛이 앞이냐 뒤냐 — 굽기 전에 여기만 바꾼다
// ───────────────────────────────────────────────
#define ZONE_IS_REAR 1

#if ZONE_IS_REAR
static const char *DEVICE_NAME = "PARK-01";
#else
static const char *DEVICE_NAME = "PARK-02";
#endif

// ───────────────────────────────────────────────
// 핀맵 (docs/hardware.md) — 앞뒤 유닛 동일
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
static const uint32_t BLIND_HOLD_MS   = 2000;
static const uint8_t  BLIND_MARGIN_CM = 10;

// 끊긴 직후 바로 광고를 켜면 스택이 불안정할 수 있어 조금 띄운다.
static const uint32_t ADV_RESTART_DELAY_MS = 500;

// ───────────────────────────────────────────────
// 프로토콜 (docs/ble-protocol.md)
// ───────────────────────────────────────────────
static const char *SERVICE_UUID = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E";
static const char *TX_CHAR_UUID = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E";  // notify
static const char *RX_CHAR_UUID = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E";  // write

static const uint8_t PKT_TELEMETRY = 0xAA;
static const uint8_t PKT_THRESHOLD = 0xBB;
static const uint8_t PKT_POWER     = 0xCC;

static const uint8_t DIST_INVALID  = 0xFF;  // 측정 실패 / 미연결
static const uint8_t DIST_MAX      = 254;   // 0xFF가 예약되어 있으므로

static const uint8_t POWER_STANDBY = 0x00;
static const uint8_t POWER_ACTIVE  = 0x01;

static const char *NVS_NAMESPACE = "parkassist";

// ───────────────────────────────────────────────
// 경보 곡선 — 앱의 AlarmCurve.kt 와 같은 모양이어야 한다
//
// 단계가 3개뿐이면 기준선을 넘는 순간에만 소리가 바뀌어서 그 사이에서 얼마나
// 가까워지는지 알 수 없다. 그래서 거리에 따라 연속적으로 촘촘해진다.
// 액티브 부저라 음색을 못 바꾸므로 표현 수단은 "울림 간격" 하나뿐이다.
// (앱은 여기에 더해 음량·음높이도 같이 올린다)
// ───────────────────────────────────────────────
static const float INTENSITY_AT_MID  = 0.34f;
static const float INTENSITY_AT_NEAR = 0.67f;
static const float CONTINUOUS_FROM   = 0.93f;

static const float INTERVAL_MAX_MS = 1000.0f;
static const float INTERVAL_MIN_MS = 80.0f;

// ───────────────────────────────────────────────
// 설정값 (0xBB / 0xCC로 변경, NVS에 저장)
// ───────────────────────────────────────────────
struct Settings {
  uint8_t nearCm = 30;   // 위험
  uint8_t midCm  = 60;   // 경고
  uint8_t farCm  = 120;  // 주의
  // 폰이 없어도 동작해야 하므로 부팅 기본값은 항상 작동이다 (ADR 0001).
  uint8_t power = POWER_ACTIVE;
};
static Settings g_settings;

static Preferences g_prefs;

// 플래시 쓰기는 BLE 콜백이 아니라 loop()에서 한다.
static volatile bool g_settingsDirty = false;

// ───────────────────────────────────────────────
// 측정 상태
// ───────────────────────────────────────────────
static uint8_t  g_distCm[NUM_CH]      = {DIST_INVALID, DIST_INVALID, DIST_INVALID, DIST_INVALID};
static uint8_t  g_lastValidCm[NUM_CH] = {DIST_INVALID, DIST_INVALID, DIST_INVALID, DIST_INVALID};
static uint32_t g_lastValidMs[NUM_CH] = {0, 0, 0, 0};

static volatile uint32_t g_activeCh  = 0;
static volatile uint32_t g_echoStart = 0;
static volatile uint32_t g_echoEnd   = 0;
static volatile bool     g_echoDone  = false;

static uint32_t g_slotStartMs = 0;

// ───────────────────────────────────────────────
// BLE 상태
// ───────────────────────────────────────────────
static BLECharacteristic *g_txChar = nullptr;

static volatile bool     g_clientConnected   = false;
static volatile bool     g_restartAdvPending = false;
static volatile uint32_t g_restartAdvAtMs    = 0;

// .ino 자동 프로토타입 생성에 기대지 않도록 직접 선언해 둔다.
static void bleHandleCommand(const uint8_t *data, size_t len);
static void saveSettings();

// ───────────────────────────────────────────────
// NVS
// ───────────────────────────────────────────────
static void loadSettings() {
  g_prefs.begin(NVS_NAMESPACE, true);  // 읽기 전용
  Settings stored;
  stored.nearCm = g_prefs.getUChar("near", g_settings.nearCm);
  stored.midCm  = g_prefs.getUChar("mid", g_settings.midCm);
  stored.farCm  = g_prefs.getUChar("far", g_settings.farCm);
  stored.power  = g_prefs.getUChar("power", g_settings.power);
  g_prefs.end();

  // 저장된 값이 깨졌으면 그냥 기본값을 쓴다. 잘못된 기준으로 경보를 내느니 낫다.
  bool ordered = stored.nearCm > 0 && stored.nearCm < stored.midCm &&
                 stored.midCm < stored.farCm && stored.farCm <= DIST_MAX;
  if (!ordered) return;

  if (stored.power != POWER_STANDBY && stored.power != POWER_ACTIVE) {
    stored.power = POWER_ACTIVE;
  }
  g_settings = stored;
}

static void saveSettings() {
  g_prefs.begin(NVS_NAMESPACE, false);
  g_prefs.putUChar("near", g_settings.nearCm);
  g_prefs.putUChar("mid", g_settings.midCm);
  g_prefs.putUChar("far", g_settings.farCm);
  g_prefs.putUChar("power", g_settings.power);
  g_prefs.end();
}

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

  bool wasClose = last <= (uint16_t)g_settings.nearCm + BLIND_MARGIN_CM;
  bool recent   = (millis() - g_lastValidMs[ch]) < BLIND_HOLD_MS;
  return (wasClose && recent) ? 0 : DIST_INVALID;
}

// ───────────────────────────────────────────────
// 경보 (부저) — BLE 상태를 절대 참조하지 않는다 (ADR 0001)
// ───────────────────────────────────────────────

// xFar에서 yLo, xNear에서 yHi가 되는 선형 보간.
static float segment(uint16_t cm, uint16_t xFar, uint16_t xNear, float yLo, float yHi) {
  if (xFar <= xNear) return yHi;
  float t = (float)(xFar - cm) / (float)(xFar - xNear);
  if (t < 0.0f) t = 0.0f;
  if (t > 1.0f) t = 1.0f;
  return yLo + (yHi - yLo) * t;
}

// 거리 → 강도 0..1. 기준 밖이면 0. (앱 AlarmCurve.intensity 와 동일)
//
// g_settings 는 BLE 태스크가 쓰고 이 함수는 loop()에서 읽는다. 바이트 4개라 찢겨 읽힐
// 수 있지만 잠금을 걸지 않는다. segment()가 xFar <= xNear 일 때 더 심각한 쪽(yHi)을
// 돌려주므로, 어긋난 순간의 결과는 항상 "더 크게 울리는" 쪽으로 치우친다. 다음 100ms에
// 정상값으로 돌아온다. 경보가 조용해지는 방향으로는 틀리지 않는다.
static float alarmIntensity(uint16_t cm) {
  if (cm >= g_settings.farCm) return 0.0f;
  if (cm >= g_settings.midCm) {
    return segment(cm, g_settings.farCm, g_settings.midCm, 0.0f, INTENSITY_AT_MID);
  }
  if (cm >= g_settings.nearCm) {
    return segment(cm, g_settings.midCm, g_settings.nearCm, INTENSITY_AT_MID, INTENSITY_AT_NEAR);
  }
  return segment(cm, g_settings.nearCm, 0, INTENSITY_AT_NEAR, 1.0f);
}

// 지금 울려야 할 강도. 울릴 필요가 없으면 0.
static float currentIntensity() {
  if (g_settings.power == POWER_STANDBY) return 0.0f;

  uint16_t minCm = 0xFFFF;
  for (uint32_t ch = 0; ch < NUM_CH; ch++) {
    uint8_t cm = effectiveCmForAlarm(ch);
    if (cm != DIST_INVALID && cm < minCm) minCm = cm;
  }
  if (minCm == 0xFFFF) return 0.0f;
  return alarmIntensity(minCm);
}

// 등비 보간 — 간격이 절반씩 줄어드는 느낌이라 선형보다 자연스럽게 들린다.
static uint32_t beepIntervalMs(float u) {
  return (uint32_t)(INTERVAL_MAX_MS * powf(INTERVAL_MIN_MS / INTERVAL_MAX_MS, u));
}

static void updateBuzzer() {
  static uint32_t lastEdgeMs = 0;
  static bool     buzzerOn   = false;

  float    u   = currentIntensity();
  uint32_t now = millis();

  if (u <= 0.0f) {
    if (buzzerOn) {
      digitalWrite(BUZZER_PIN, LOW);
      buzzerOn = false;
    }
    return;
  }
  if (u >= CONTINUOUS_FROM) {
    if (!buzzerOn) {
      digitalWrite(BUZZER_PIN, HIGH);
      buzzerOn   = true;
      lastEdgeMs = now;
    }
    return;
  }

  uint32_t interval = beepIntervalMs(u);
  uint32_t beepMs   = interval * 45 / 100;
  if (beepMs < 45) beepMs = 45;

  uint32_t elapsed = now - lastEdgeMs;
  if (buzzerOn && elapsed >= beepMs) {
    digitalWrite(BUZZER_PIN, LOW);
    buzzerOn   = false;
    lastEdgeMs = now;
  } else if (!buzzerOn && elapsed >= (interval - beepMs)) {
    digitalWrite(BUZZER_PIN, HIGH);
    buzzerOn   = true;
    lastEdgeMs = now;
  }
}

// ───────────────────────────────────────────────
// BLE
// ───────────────────────────────────────────────
class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *server) override {
    g_clientConnected = true;
  }

  void onDisconnect(BLEServer *server) override {
    g_clientConnected = false;
    // 광고를 다시 켜지 않으면 앱이 영영 재연결하지 못한다.
    // 콜백 안에서 바로 켜지 않고 loop()로 넘긴다.
    g_restartAdvAtMs    = millis() + ADV_RESTART_DELAY_MS;
    g_restartAdvPending = true;
  }
};

class RxCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *characteristic) override {
    bleHandleCommand(characteristic->getData(), characteristic->getLength());
  }
};

static void bleSetup() {
  BLEDevice::init(DEVICE_NAME);

  BLEServer *server = BLEDevice::createServer();
  server->setCallbacks(new ServerCallbacks());

  BLEService *service = server->createService(SERVICE_UUID);

  g_txChar = service->createCharacteristic(TX_CHAR_UUID, BLECharacteristic::PROPERTY_NOTIFY);
  // 이 디스크립터가 CCCD(0x2902)다. 이게 없으면 앱이 알림을 켤 방법이 없다.
  g_txChar->addDescriptor(new BLE2902());

  BLECharacteristic *rxChar = service->createCharacteristic(
      RX_CHAR_UUID,
      BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);
  rxChar->setCallbacks(new RxCallbacks());

  service->start();

  // ⚠️ 앞뒤 유닛이 같은 서비스 UUID를 쓰므로 앱은 **이름으로** 둘을 가려낸다.
  //    이름이 광고에 실리지 않으면 두 연결이 같은 기기를 잡는다 (ADR 0004).
  BLEAdvertising *advertising = BLEDevice::getAdvertising();

  BLEAdvertisementData advertisementData;
  advertisementData.setFlags(0x06);  // LE General Discoverable + BR/EDR 미지원
  advertisementData.setName(DEVICE_NAME);
  advertisementData.setCompleteServices(BLEUUID(SERVICE_UUID));
  // flags 3 + 이름 9 + 128비트 UUID 18 = 30바이트. 한계가 31바이트라 여유가 1바이트뿐이다.
  // 여기에 뭘 더 넣으면 조용히 잘리므로, 추가할 게 생기면 스캔 응답 쪽에 넣을 것.
  advertising->setAdvertisementData(advertisementData);

  // 스캔 응답에도 이름을 실어 둔다. 광고 패킷이 잘리는 환경에서도 앱이 구분할 수 있다.
  BLEAdvertisementData scanResponseData;
  scanResponseData.setName(DEVICE_NAME);
  advertising->setScanResponseData(scanResponseData);
  advertising->setScanResponse(true);

  BLEDevice::startAdvertising();

  // TODO: 연결 간격 조정. 6바이트 × 10Hz면 데이터량 자체는 무시할 만하지만, 중앙이
  //       잡은 간격이 100ms를 넘으면 패킷이 뭉쳐서 도착해 접근 속도 계산이 흔들린다.
  //       실측 후 필요하면 onConnect(BLEServer*, esp_ble_gatts_cb_param_t*) 오버로드에서
  //       server->updateConnParams(param->connect.remote_bda, 12, 24, 0, 400) 로 조인다.
}

static void bleNotifyTelemetry() {
  if (!g_clientConnected || g_txChar == nullptr) return;

  uint8_t pkt[6];
  pkt[0] = PKT_TELEMETRY;
  for (uint32_t ch = 0; ch < NUM_CH; ch++) pkt[1 + ch] = g_distCm[ch];
  pkt[5] = checksum(pkt, 5);

  g_txChar->setValue(pkt, sizeof(pkt));
  g_txChar->notify();
}

// RX write 콜백에서 호출한다. 체크섬이 틀리면 조용히 버린다.
static void bleHandleCommand(const uint8_t *data, size_t len) {
  if (data == nullptr || len < 3) return;
  if (checksum(data, len - 1) != data[len - 1]) return;

  switch (data[0]) {
    case PKT_THRESHOLD: {
      if (len != 5) return;
      uint8_t n = data[1], m = data[2], f = data[3];
      if (!(n > 0 && n < m && m < f && f <= DIST_MAX)) return;  // 순서 위반은 거부

      // 앱은 연결될 때마다 같은 값을 다시 밀어 넣는다. 매번 플래시에 쓰면 수명이 닳는다.
      if (n == g_settings.nearCm && m == g_settings.midCm && f == g_settings.farCm) return;

      g_settings.nearCm = n;
      g_settings.midCm  = m;
      g_settings.farCm  = f;
      g_settingsDirty   = true;
      break;
    }

    case PKT_POWER: {
      if (len != 3) return;
      if (data[1] != POWER_STANDBY && data[1] != POWER_ACTIVE) return;
      if (data[1] == g_settings.power) return;

      g_settings.power = data[1];
      if (g_settings.power == POWER_STANDBY) digitalWrite(BUZZER_PIN, LOW);
      g_settingsDirty = true;
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
    attachInterruptArg(digitalPinToInterrupt(ECHO_PINS[ch]), onEchoEdge,
                       (void *)(uintptr_t)ch, CHANGE);
  }

  loadSettings();
  Serial.printf("park-assist-fw: %s  기준 %u/%u/%u  전원 %u\n", DEVICE_NAME,
                g_settings.nearCm, g_settings.midCm, g_settings.farCm, g_settings.power);

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

  // 부저가 먼저다. 아래 BLE/플래시 작업이 밀려도 경보는 제때 나가야 한다.
  updateBuzzer();

  // 연결이 끊긴 뒤 광고 재개
  if (g_restartAdvPending && (int32_t)(now - g_restartAdvAtMs) >= 0) {
    g_restartAdvPending = false;
    BLEDevice::startAdvertising();
  }

  // 플래시 쓰기는 BLE 콜백이 아니라 여기서 한다 (콜백을 오래 잡고 있으면 안 된다)
  if (g_settingsDirty) {
    g_settingsDirty = false;
    saveSettings();
  }
}

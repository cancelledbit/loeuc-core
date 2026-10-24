// The hardware half of the HUD bridge example: an ESP32 that LoEUC's extension feeds with
// telemetry over BLE, and whose button becomes the wheel's horn and headlight.
//
// Arduino IDE with the ESP32 core 3.x (it ships the BLE library used here). Board: any ESP32 with
// BLE. As written it prints to the serial monitor at 115200 and uses the BOOT button on GPIO 0;
// drawing on a display is a matter of replacing showTelemetry().
//
// The wire format is defined once, in HudPacket.kt of the Android example. Keep the two in step.

#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

#define SERVICE_UUID   "9b3c0001-4c6f-4555-4300-00000000a001"
#define TELEMETRY_UUID "9b3c0001-4c6f-4555-4300-00000000a002"
#define EVENTS_UUID    "9b3c0001-4c6f-4555-4300-00000000a003"
#define CONFIG_UUID    "9b3c0001-4c6f-4555-4300-00000000a004"

static const int BUTTON_PIN = 0;            // BOOT button, active low
static const unsigned long LONG_PRESS_MS = 800;
static const int BATTERY_PIN = -1;          // ADC pin of a battery divider, or -1 for none
static const unsigned long BATTERY_EVERY_MS = 30000;

static BLECharacteristic *events = nullptr;
static bool connected = false;
static int brightness = 70;

static const int16_t ABSENT_I16 = 0x7FFF;
static const uint16_t ABSENT_U16 = 0xFFFF;
static const uint32_t ABSENT_U32 = 0xFFFFFFFF;

struct Telemetry {
  bool playback;
  int16_t speed10, pwm10, temperature10, powerW;
  uint16_t battery10, voltage10;
  uint32_t tripMetres;
};

static int16_t i16(const uint8_t *p) { return (int16_t)(p[0] | (p[1] << 8)); }
static uint16_t u16(const uint8_t *p) { return (uint16_t)(p[0] | (p[1] << 8)); }
static uint32_t u32(const uint8_t *p) {
  return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}

static void printTenths(const char *label, long value, bool absent, const char *unit) {
  Serial.print(label);
  if (absent) {
    Serial.print("-");
  } else {
    Serial.print(value / 10.0, 1);
  }
  Serial.print(unit);
}

// Replace with your display code. Absent fields are the wheel not reporting that reading:
// show a dash, never a zero.
static void showTelemetry(const Telemetry &t) {
  printTenths("speed ", t.speed10, t.speed10 == ABSENT_I16, " km/h  ");
  printTenths("pwm ", t.pwm10, t.pwm10 == ABSENT_I16, " %  ");
  printTenths("bat ", t.battery10, t.battery10 == ABSENT_U16, " %  ");
  printTenths("", t.voltage10, t.voltage10 == ABSENT_U16, " V  ");
  printTenths("", t.temperature10, t.temperature10 == ABSENT_I16, " C  ");
  Serial.print(t.powerW == ABSENT_I16 ? String("-") : String(t.powerW));
  Serial.print(" W  trip ");
  Serial.print(t.tripMetres == ABSENT_U32 ? String("-") : String(t.tripMetres / 1000.0, 2));
  Serial.println(t.playback ? " km  [replay]" : " km");
}

class TelemetryWrites : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *c) override {
    String value = c->getValue();
    if (value.length() < 20) return;
    const uint8_t *p = (const uint8_t *)value.c_str();
    if (p[0] != 0x4C || p[1] != 1) return;  // not our packet, or a version this sketch does not know
    Telemetry t;
    t.playback = p[2] & 0x01;
    t.speed10 = i16(p + 4);
    t.pwm10 = i16(p + 6);
    t.battery10 = u16(p + 8);
    t.voltage10 = u16(p + 10);
    t.temperature10 = i16(p + 12);
    t.powerW = i16(p + 14);
    t.tripMetres = u32(p + 16);
    showTelemetry(t);
  }
};

class ConfigWrites : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *c) override {
    String value = c->getValue();
    if (value.length() < 1) return;
    brightness = (uint8_t)value[0];
    Serial.printf("brightness %d%%\n", brightness);
  }
};

class Connections : public BLEServerCallbacks {
  void onConnect(BLEServer *) override {
    connected = true;
    Serial.println("phone connected");
  }
  void onDisconnect(BLEServer *server) override {
    connected = false;
    Serial.println("phone disconnected, advertising again");
    server->getAdvertising()->start();
  }
};

static void sendEvent(uint8_t type, uint8_t value) {
  if (!connected || events == nullptr) return;
  uint8_t bytes[2] = {type, value};
  events->setValue(bytes, 2);
  events->notify();
}

void setup() {
  Serial.begin(115200);
  pinMode(BUTTON_PIN, INPUT_PULLUP);

  BLEDevice::init("LoEUC HUD");
  BLEServer *server = BLEDevice::createServer();
  server->setCallbacks(new Connections());
  BLEService *service = server->createService(SERVICE_UUID);

  BLECharacteristic *telemetry = service->createCharacteristic(
      TELEMETRY_UUID, BLECharacteristic::PROPERTY_WRITE_NR | BLECharacteristic::PROPERTY_WRITE);
  telemetry->setCallbacks(new TelemetryWrites());

  events = service->createCharacteristic(EVENTS_UUID, BLECharacteristic::PROPERTY_NOTIFY);
  events->addDescriptor(new BLE2902());

  BLECharacteristic *config = service->createCharacteristic(CONFIG_UUID, BLECharacteristic::PROPERTY_WRITE);
  config->setCallbacks(new ConfigWrites());

  service->start();
  BLEAdvertising *advertising = BLEDevice::getAdvertising();
  advertising->addServiceUUID(SERVICE_UUID);  // the extension scans for exactly this
  advertising->setScanResponse(true);
  BLEDevice::startAdvertising();
  Serial.println("advertising as LoEUC HUD");
}

void loop() {
  // Button: short press -> horn, long press -> headlight. The phone decides whether the wheel
  // gets it; a refusal (no wheel, a replay, a wheel without a horn) shows in LoEUC's journal.
  static bool wasDown = false;
  static unsigned long downAt = 0;
  bool down = digitalRead(BUTTON_PIN) == LOW;
  if (down && !wasDown) downAt = millis();
  if (!down && wasDown) {
    unsigned long held = millis() - downAt;
    if (held > 30) sendEvent(0x01, held >= LONG_PRESS_MS ? 2 : 1);
  }
  wasDown = down;

  static unsigned long batteryAt = 0;
  if (BATTERY_PIN >= 0 && connected && millis() - batteryAt > BATTERY_EVERY_MS) {
    batteryAt = millis();
    // A 1:2 divider on a single Li-ion cell: 3.3 V empty, 4.2 V full. Adjust to your wiring.
    float volts = analogReadMilliVolts(BATTERY_PIN) * 2 / 1000.0;
    int percent = constrain((int)((volts - 3.3) / (4.2 - 3.3) * 100), 0, 100);
    sendEvent(0x02, (uint8_t)percent);
  }
  delay(10);
}

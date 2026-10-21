#include <Arduino.h>
#include <esp_bt.h>

#include "candles.h"
#include "show.h"
#include "web.h"

// Pins
const uint8_t IR_TX_PIN = 27;                         // IR LED (D27)
const uint8_t IR_TX_MIRROR_PINS[] = {25, 26, 32};     // more IR LEDs, same signal (D25, D26, D32)
const uint8_t LIGHT_PIN = 33;         // Floodlight MOSFET gate (D33)

// mDNS name: http://bloodymary.local
const char *HOSTNAME = "bloodymary";

bool lightOn = false;

void lightSet(bool on) {
  lightOn = on;
  digitalWrite(LIGHT_PIN, on ? HIGH : LOW);
}

bool lightIsOn() {
  return lightOn;
}

void showReset() {
  lightSet(false);
  candlesOnBurst();
}

void handleSerial() {
  if (!Serial.available()) return;

  switch (Serial.read()) {
    case '1':
      Serial.println("Candles ON");
      candlesOnBurst();
      break;
    case '0':
      Serial.println("Candles OFF");
      candlesOffBurst();
      break;
    case 'l':
      lightSet(!lightOn);
      Serial.println(lightOn ? "Light ON" : "Light OFF");
      break;
    case 'x':
      Serial.println("Reset");
      showReset();
      break;
  }
}

void setup() {
  Serial.begin(115200);
  delay(500);

  pinMode(LIGHT_PIN, OUTPUT);
  lightSet(false);

  candlesBegin(IR_TX_PIN, IR_TX_MIRROR_PINS, sizeof(IR_TX_MIRROR_PINS));

  Serial.println();
  Serial.println("=== Bloody Mary ===");
  Serial.println("Serial keys: 1/0 candles on/off, l toggle light, x reset");
  Serial.println();

  // No Bluetooth here (the phone drives the amp), so give its memory back
  esp_bt_mem_release(ESP_BT_MODE_BTDM);
  webBegin(HOSTNAME);
  Serial.printf("Free memory: %u bytes\n", (unsigned)ESP.getFreeHeap());
}

void loop() {
  handleSerial();
  webLoop();
}

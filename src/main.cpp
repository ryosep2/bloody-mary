#include <Arduino.h>

#include "amp_audio.h"
#include "candles.h"
#include "show.h"
#include "web.h"

// Pins
const uint8_t IR_TX_PIN = 27;         // IR LED for one half of the candles (D27)
const uint8_t IR_TX_MIRROR_PIN = 25;  // IR LED for the other half (D25), same signal
const uint8_t LIGHT_PIN = 33;         // Floodlight MOSFET gate (D33)

// Bluetooth name (or the start of it) of the amplifier. If it doesn't connect,
// check the "[amp] found ..." lines in the serial monitor for the real name.
const char *AMP_BT_NAME = "F900S";

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
  ampStop();
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
    case 'a':
      Serial.println("Playing break-in audio");
      ampPlay();
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

  candlesBegin(IR_TX_PIN, IR_TX_MIRROR_PIN);

  Serial.println();
  Serial.println("=== Bloody Mary ===");
  Serial.println("Serial keys: 1/0 candles on/off, a play audio, l toggle light, x reset");
  Serial.println();

  ampBegin(AMP_BT_NAME);
  webBegin(HOSTNAME);
}

void loop() {
  handleSerial();
  webLoop();
}

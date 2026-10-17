#include <Arduino.h>
#include <IRremote.hpp>

#include "amp_audio.h"
#include "candles.h"
#include "show.h"
#include "web.h"

// Pins
const uint8_t IR_RECEIVE_PIN = 25;  // IR receiver data pin (D25)
const uint8_t IR_TX_PIN = 27;       // IR LED (D27)
const uint8_t LIGHT_PIN = 33;       // Floodlight MOSFET gate (D33)

// Bluetooth name (or the start of it) of the amplifier. If it doesn't connect,
// check the "[amp] found ..." lines in the serial monitor for the real name.
const char *AMP_BT_NAME = "F900S";

// mDNS name: http://bloodymary.local
const char *HOSTNAME = "bloodymary";

// Last captured code, kept so it can be replayed with 'r'
IRData lastData;
bool haveLast = false;

// Raw timings, used when the protocol is not recognized
uint8_t lastRaw[RAW_BUFFER_LENGTH];
uint16_t lastRawLen = 0;

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

void handleSniffer() {
  if (!IrReceiver.decode()) return;

  IRData &d = IrReceiver.decodedIRData;

  // Holding a button sends repeat frames, so ignore those
  if (!(d.flags & IRDATA_FLAGS_IS_REPEAT)) {
    IrReceiver.printIRResultShort(&Serial);
    IrReceiver.printIRSendUsage(&Serial);

    if (d.protocol == UNKNOWN) {
      // Unknown protocol: dump raw mark/space timings so it can be sent with sendRaw
      IrReceiver.printIRResultRawFormatted(&Serial, true);
      IrReceiver.compensateAndStoreIRResultInArray(lastRaw);
      lastRawLen = d.rawlen - 1;  // first entry is the gap before the signal, not stored
    } else {
      lastRawLen = 0;
    }

    lastData = d;
    haveLast = true;
    Serial.println();
  }

  IrReceiver.resume();
}

void replayLast() {
  if (!haveLast) {
    Serial.println("Nothing captured yet.");
  } else if (lastData.protocol == UNKNOWN) {
    if (lastRawLen > 0) {
      Serial.println("Replaying raw code...");
      IrSender.sendRaw(lastRaw, lastRawLen, 38);  // 38 kHz carrier
    }
  } else {
    Serial.print("Replaying ");
    Serial.print(getProtocolString(lastData.protocol));
    Serial.print(" address 0x");
    Serial.print(lastData.address, HEX);
    Serial.print(" command 0x");
    Serial.println(lastData.command, HEX);
    IrSender.write(&lastData, 0);
  }
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
    case 'r':
    case 'R':
      replayLast();
      break;
  }
}

void setup() {
  Serial.begin(115200);
  delay(500);

  pinMode(LIGHT_PIN, OUTPUT);
  lightSet(false);

  IrReceiver.begin(IR_RECEIVE_PIN, ENABLE_LED_FEEDBACK);  // onboard LED blinks on receive
  candlesBegin(IR_TX_PIN);

  Serial.println();
  Serial.println("=== Bloody Mary ===");
  Serial.println("Serial keys: 1/0 candles on/off, a play audio, l toggle light,");
  Serial.println("             x reset, r replay last captured IR code");
  Serial.println();

  ampBegin(AMP_BT_NAME);
  webBegin(HOSTNAME);
}

void loop() {
  handleSniffer();
  handleSerial();
  webLoop();
}

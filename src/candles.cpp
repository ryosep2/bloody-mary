#include "candles.h"

#define DISABLE_CODE_FOR_RECEIVER  // send only
#include <IRremote.hpp>

#if !defined(SEND_LEDC_CHANNEL)
#define SEND_LEDC_CHANNEL 0  // IRremote's PWM channel on Arduino core 2.x
#endif

// Candle remote codes (NEC, captured with the IRremote ReceiveDump example)
const uint16_t CANDLE_ADDRESS = 0x0;
const uint8_t CANDLE_CMD_ON = 0x1;
const uint8_t CANDLE_CMD_OFF = 0x0;

const uint8_t BURST_COUNT = 3;
const uint16_t BURST_GAP_MS = 100;

static void sendBurst(uint8_t command) {
  for (uint8_t i = 0; i < BURST_COUNT; i++) {
    if (i > 0) delay(BURST_GAP_MS);
    IrSender.sendNEC(CANDLE_ADDRESS, command, 0);
  }
}

void candlesBegin(uint8_t txPin, const uint8_t *mirrorPins, size_t mirrorCount) {
  IrSender.begin(txPin);
  // Set up the 38 kHz PWM channel now (IRremote otherwise does it on the first
  // send), then route the same channel to the other LEDs so they flash in sync
  IrSender.enableIROut(38);
  for (size_t i = 0; i < mirrorCount; i++) ledcAttachPin(mirrorPins[i], SEND_LEDC_CHANNEL);
}

void candlesOnBurst() {
  sendBurst(CANDLE_CMD_ON);
}

void candlesOffBurst() {
  sendBurst(CANDLE_CMD_OFF);
}

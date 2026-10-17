#include "candles.h"

// Declarations only: main.cpp includes IRremote.hpp with the implementation
#define USE_IRREMOTE_HPP_AS_PLAIN_INCLUDE
#include <IRremote.hpp>

// Candle remote codes (NEC, captured with the sniffer)
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

void candlesBegin(uint8_t txPin) {
  IrSender.begin(txPin);
}

void candlesOnBurst() {
  sendBurst(CANDLE_CMD_ON);
}

void candlesOffBurst() {
  sendBurst(CANDLE_CMD_OFF);
}

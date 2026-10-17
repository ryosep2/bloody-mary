#pragma once

#include <Arduino.h>

// IR control of the remote-controlled candles

void candlesBegin(uint8_t txPin);

// Each burst sends the code 3 times, 100 ms apart, so every candle catches one
void candlesOnBurst();
void candlesOffBurst();

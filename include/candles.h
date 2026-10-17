#pragma once

#include <Arduino.h>

// IR control of the remote-controlled candles

// txPin and mirrorPin carry the identical IR signal, one LED per half of the
// candle array
void candlesBegin(uint8_t txPin, uint8_t mirrorPin);

// Each burst sends the code 3 times, 100 ms apart, so every candle catches one
void candlesOnBurst();
void candlesOffBurst();

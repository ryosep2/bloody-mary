#pragma once

#include <Arduino.h>

// IR control of the remote-controlled candles

// txPin and every mirror pin carry the identical IR signal, so LEDs can be
// spread out to cover the whole candle array
void candlesBegin(uint8_t txPin, const uint8_t *mirrorPins, size_t mirrorCount);

// Each burst sends the code 3 times, 100 ms apart, so every candle catches one
void candlesOnBurst();
void candlesOffBurst();

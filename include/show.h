#pragma once

// Show actions implemented in main.cpp, shared by the serial and HTTP controls

void lightSet(bool on);
bool lightIsOn();

// Light off, audio stopped, candles back on: ready for the next guest
void showReset();

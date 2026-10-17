#pragma once

// Streams /breakin.wav from LittleFS to the Bluetooth amplifier (A2DP source).
// File format: 16-bit PCM WAV, mono or stereo, 22050 or 44100 Hz.
// 22050 Hz mono is smallest (~43 s fits): see tools/convert_audio.py.

// Mounts LittleFS and starts searching for a Bluetooth speaker whose name
// starts with ampName. Every device found is printed to Serial.
void ampBegin(const char *ampName);

// Starts the break-in audio from the beginning (restarts it if already playing)
void ampPlay();
void ampStop();

bool ampIsPlaying();
bool ampIsConnected();

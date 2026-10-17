#pragma once

// Phone control over home Wi-Fi.
//   UDP port 4210, "<id> <command>": candles_off, audio_play, light_on, reset
//     (low latency, used by the app for show triggers)
//   HTTP GET /candles/off  /audio/play  /light/on  /reset  /status
//     (each returns the current status as JSON, for the app's status line and testing)

// Connects to Wi-Fi (credentials in secrets.h) and starts mDNS, HTTP and UDP
void webBegin(const char *hostname);

// Call every loop() to handle requests
void webLoop();

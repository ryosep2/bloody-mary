#pragma once

// HTTP control for the phone app, over home Wi-Fi.
//   GET /candles/off  /audio/play  /light/on  /reset  /status
// Each returns the current status as JSON.

// Connects to Wi-Fi (credentials in secrets.h) and starts mDNS + the server
void webBegin(const char *hostname);

// Call every loop() to serve requests
void webLoop();

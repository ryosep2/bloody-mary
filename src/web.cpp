#include "web.h"

#include <ESPmDNS.h>
#include <WebServer.h>
#include <WiFi.h>

#include "amp_audio.h"
#include "candles.h"
#include "secrets.h"
#include "show.h"

const uint32_t WIFI_TIMEOUT_MS = 15000;

static WebServer server(80);

static void sendStatus() {
  String json = "{\"ip\":\"" + WiFi.localIP().toString() + "\"";
  json += ",\"amp_connected\":" + String(ampIsConnected() ? "true" : "false");
  json += ",\"playing\":" + String(ampIsPlaying() ? "true" : "false");
  json += ",\"light\":" + String(lightIsOn() ? "true" : "false");
  json += "}";
  server.send(200, "application/json", json);
}

// Registers an endpoint that runs action, logs it, then replies with the status
static void route(const char *path, void (*action)()) {
  server.on(path, HTTP_GET, [path, action]() {
    Serial.printf("[web] %s\n", path);
    action();
    sendStatus();
  });
}

void webBegin(const char *hostname) {
  WiFi.mode(WIFI_STA);
  WiFi.setSleep(true);  // modem sleep is required while Bluetooth is running
  WiFi.setAutoReconnect(true);
  WiFi.begin(WIFI_SSID, WIFI_PASS);

  Serial.printf("[web] connecting to %s", WIFI_SSID);
  uint32_t start = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - start < WIFI_TIMEOUT_MS) {
    delay(250);
    Serial.print('.');
  }
  Serial.println();
  if (WiFi.status() == WL_CONNECTED) {
    Serial.printf("[web] connected, IP %s\n", WiFi.localIP().toString().c_str());
  } else {
    Serial.println("[web] not connected yet, will keep retrying in the background");
  }

  if (MDNS.begin(hostname)) {
    MDNS.addService("http", "tcp", 80);
    Serial.printf("[web] http://%s.local/status\n", hostname);
  }

  route("/candles/off", candlesOffBurst);
  route("/audio/play", ampPlay);
  route("/light/on", []() { lightSet(true); });
  route("/reset", showReset);
  server.on("/status", HTTP_GET, sendStatus);
  server.onNotFound([]() { server.send(404, "text/plain", "not found"); });
  server.begin();
}

void webLoop() {
  server.handleClient();
}

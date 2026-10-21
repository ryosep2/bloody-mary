#include "web.h"

#include <ArduinoOTA.h>
#include <ESPmDNS.h>
#include <WebServer.h>
#include <WiFi.h>
#include <WiFiUdp.h>

#include "candles.h"
#include "secrets.h"
#include "show.h"

#if !defined(OTA_PASSWORD)
#error "Add #define OTA_PASSWORD \"...\" to include/secrets.h (see secrets.example.h)"
#endif

const uint32_t WIFI_TIMEOUT_MS = 15000;
const uint16_t UDP_PORT = 4210;

static WebServer server(80);
static WiFiUDP udp;

struct Command {
  const char *name;  // UDP command
  const char *path;  // HTTP endpoint
  void (*action)();
};

static const Command COMMANDS[] = {
    {"candles_off", "/candles/off", candlesOffBurst},
    {"light_on", "/light/on", []() { lightSet(true); }},
    {"reset", "/reset", showReset},
};

static void sendStatus() {
  String json = "{\"ip\":\"" + WiFi.localIP().toString() + "\"";
  json += ",\"light\":" + String(lightIsOn() ? "true" : "false");
  json += ",\"free_heap\":" + String(ESP.getFreeHeap());
  json += "}";
  server.send(200, "application/json", json);
}

// Triggers come in as UDP packets "<id> <command>": one packet is faster than
// an HTTP request's several round trips. The phone sends each packet a few
// times in case one is lost; copies share an id and only the first one runs.
static void handleUdp() {
  static uint32_t lastId = 0;

  int len = udp.parsePacket();
  if (len <= 0) return;

  char buf[48];
  len = udp.read(buf, sizeof(buf) - 1);
  buf[len > 0 ? len : 0] = '\0';

  char *space = strchr(buf, ' ');
  if (!space) return;
  *space = '\0';
  uint32_t id = strtoul(buf, nullptr, 10);
  const char *name = space + 1;
  if (id == lastId) return;

  for (const Command &c : COMMANDS) {
    if (strcmp(name, c.name) == 0) {
      lastId = id;
      Serial.printf("[udp] %s\n", name);
      c.action();
      return;
    }
  }
  Serial.printf("[udp] unknown command \"%s\"\n", name);
}

void webBegin(const char *hostname) {
  WiFi.mode(WIFI_STA);
  WiFi.setSleep(false);  // radio always awake, so triggers arrive in milliseconds
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

  for (const Command &c : COMMANDS) {
    server.on(c.path, HTTP_GET, [&c]() {
      Serial.printf("[web] %s\n", c.path);
      c.action();
      sendStatus();
    });
  }
  server.on("/status", HTTP_GET, sendStatus);
  server.onNotFound([]() { server.send(404, "text/plain", "not found"); });
  server.begin();

  udp.begin(UDP_PORT);
  Serial.printf("[web] UDP commands on port %u\n", UDP_PORT);

  // Wireless firmware updates: pio run -e project_ota -t upload
  ArduinoOTA.setHostname(hostname);
  ArduinoOTA.setPassword(OTA_PASSWORD);
  ArduinoOTA.setMdnsEnabled(false);  // already advertised above
  ArduinoOTA.onStart([]() { Serial.println("[ota] update starting"); });
  ArduinoOTA.onEnd([]() { Serial.println("[ota] done, restarting"); });
  ArduinoOTA.onError([](ota_error_t e) { Serial.printf("[ota] error %u\n", e); });
  ArduinoOTA.begin();
}

void webLoop() {
  handleUdp();
  server.handleClient();
  ArduinoOTA.handle();
}

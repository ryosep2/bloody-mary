#include "amp_audio.h"

#include <Arduino.h>
#include <BluetoothA2DPSource.h>
#include <LittleFS.h>

static const char *AUDIO_PATH = "/breakin.wav";
static const size_t READ_SAMPLES = 256;

static BluetoothA2DPSource a2dp;
static const char *targetName = "";

// Set once by openWav(), before Bluetooth starts
static File audioFile;
static uint32_t dataStart = 0;  // offset of the first sample
static uint32_t dataBytes = 0;  // length of the sample data
static uint16_t channels = 0;
static bool halfRate = false;   // 22050 Hz: each source frame is output twice

// Owned by the Bluetooth task (getFrames); the main loop only sets the flags
static volatile bool playing = false;
static volatile bool restartRequested = false;
static uint32_t bytesLeft = 0;
static int16_t prevSample = 0;

static uint32_t readLE(File &f, uint8_t n) {
  uint8_t b[4] = {0};
  f.read(b, n);
  return b[0] | (b[1] << 8) | (b[2] << 16) | ((uint32_t)b[3] << 24);
}

// Walks the RIFF chunks (there can be LIST/metadata chunks anywhere) to find
// the format and the sample data. Only 16-bit PCM at 22050 or 44100 Hz works.
static bool openWav() {
  audioFile = LittleFS.open(AUDIO_PATH, "r");
  if (!audioFile) {
    Serial.printf("[amp] %s missing (run: pio run -t uploadfs)\n", AUDIO_PATH);
    return false;
  }

  char id[5] = {0};
  audioFile.read((uint8_t *)id, 4);
  readLE(audioFile, 4);
  char wave[5] = {0};
  audioFile.read((uint8_t *)wave, 4);
  if (strcmp(id, "RIFF") != 0 || strcmp(wave, "WAVE") != 0) {
    Serial.printf("[amp] %s is not a WAV file\n", AUDIO_PATH);
    return false;
  }

  uint16_t format = 0, bits = 0;
  uint32_t rate = 0;
  while (audioFile.available() >= 8) {
    audioFile.read((uint8_t *)id, 4);
    uint32_t size = readLE(audioFile, 4);
    uint32_t next = audioFile.position() + size + (size & 1);  // chunks are word-aligned

    if (strcmp(id, "fmt ") == 0) {
      format = readLE(audioFile, 2);
      channels = readLE(audioFile, 2);
      rate = readLE(audioFile, 4);
      readLE(audioFile, 4);  // byte rate
      readLE(audioFile, 2);  // block align
      bits = readLE(audioFile, 2);
    } else if (strcmp(id, "data") == 0) {
      dataStart = audioFile.position();
      dataBytes = size;
      break;
    }
    audioFile.seek(next);
  }

  if (dataBytes == 0 || format != 1 || bits != 16 || channels < 1 || channels > 2 ||
      (rate != 22050 && rate != 44100)) {
    Serial.printf("[amp] %s unsupported: format %u, %u-bit, %u ch, %u Hz "
                  "(need 16-bit PCM, mono/stereo, 22050/44100 Hz)\n",
                  AUDIO_PATH, format, bits, channels, (unsigned)rate);
    dataBytes = 0;
    return false;
  }

  halfRate = rate == 22050;
  Serial.printf("[amp] %s: %u ch, %u Hz, %.1f s\n", AUDIO_PATH, channels, (unsigned)rate,
                dataBytes / (2.0f * channels * rate));
  return true;
}

static bool isAmp(const char *ssid, esp_bd_addr_t address, int rssi) {
  bool match = strncmp(ssid, targetName, strlen(targetName)) == 0;
  Serial.printf("[amp] found \"%s\" (rssi %d)%s\n", ssid, rssi, match ? " <- connecting" : "");
  return match;
}

// A2DP wants 44.1 kHz stereo. Stereo files are mixed to mono (it's a single
// speaker), and 22.05 kHz files output each sample twice: first the midpoint
// from the previous sample, then the sample.
static int32_t getFrames(Frame *frames, int32_t count) {
  if (restartRequested) {
    restartRequested = false;
    prevSample = 0;
    bytesLeft = dataBytes;
    audioFile.seek(dataStart);
  }

  const uint8_t framesPerSample = halfRate ? 2 : 1;
  const uint32_t bytesPerSample = 2 * channels;
  int32_t produced = 0;
  int16_t buf[READ_SAMPLES * 2];

  while (playing && produced + framesPerSample <= count) {
    size_t want = min((size_t)(count - produced) / framesPerSample, READ_SAMPLES);
    want = min(want, (size_t)(bytesLeft / bytesPerSample));
    size_t got = want ? audioFile.read((uint8_t *)buf, want * bytesPerSample) / bytesPerSample : 0;
    if (got == 0) {
      playing = false;
      Serial.println("[amp] break-in audio finished");
      break;
    }
    bytesLeft -= got * bytesPerSample;

    for (size_t i = 0; i < got; i++) {
      int16_t s = channels == 2 ? ((int32_t)buf[2 * i] + buf[2 * i + 1]) / 2 : buf[i];
      if (halfRate) frames[produced++] = Frame(((int32_t)prevSample + s) / 2);
      frames[produced++] = Frame(s);
      prevSample = s;
    }
  }

  // Silence for the rest, so the link stays up and ready
  while (produced < count) frames[produced++] = Frame(0);
  return count;
}

void ampBegin(const char *ampName) {
  targetName = ampName;

  if (!LittleFS.begin()) {
    Serial.println("[amp] LittleFS mount failed (run: pio run -t uploadfs)");
  } else {
    openWav();
  }

  a2dp.set_ssid_callback(isAmp);
  a2dp.set_auto_reconnect(true);
  a2dp.set_on_connection_state_changed([](esp_a2d_connection_state_t state, void *) {
    Serial.printf("[amp] %s\n", state == ESP_A2D_CONNECTION_STATE_CONNECTED ? "connected" : "not connected");
  });
  a2dp.start(getFrames);  // no name list: isAmp() picks the device
  Serial.printf("[amp] searching for \"%s\"...\n", targetName);
}

void ampPlay() {
  if (dataBytes == 0) {
    Serial.println("[amp] no playable audio file");
    return;
  }
  restartRequested = true;
  playing = true;
}

void ampStop() {
  playing = false;
}

bool ampIsPlaying() {
  return playing;
}

bool ampIsConnected() {
  return a2dp.is_connected();
}

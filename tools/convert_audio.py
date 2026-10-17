#!/usr/bin/env python3
"""Shrink a 16-bit 44.1 kHz WAV to 22.05 kHz mono so it fits in the ESP32's flash.

Usage: python3 tools/convert_audio.py input.wav data/breakin.wav
"""
import array
import os
import sys
import wave


def main(src, dst):
    with wave.open(src) as w:
        channels, width, rate = w.getnchannels(), w.getsampwidth(), w.getframerate()
        if width != 2 or rate not in (22050, 44100):
            sys.exit(f"{src}: need 16-bit audio at 44100 or 22050 Hz (got {8 * width}-bit, {rate} Hz). "
                     "Export it from Audacity as 16-bit PCM WAV first.")
        samples = array.array("h", w.readframes(w.getnframes()))
    if sys.byteorder == "big":
        samples.byteswap()

    # Mix to mono (the amp drives a single speaker)
    if channels == 2:
        samples = array.array("h", ((samples[i] + samples[i + 1]) // 2 for i in range(0, len(samples) - 1, 2)))

    # Halve the rate, averaging each pair as a simple low-pass filter
    if rate == 44100:
        samples = array.array("h", ((samples[i] + samples[i + 1]) // 2 for i in range(0, len(samples) - 1, 2)))

    if sys.byteorder == "big":
        samples.byteswap()
    os.makedirs(os.path.dirname(dst) or ".", exist_ok=True)
    with wave.open(dst, "wb") as out:
        out.setnchannels(1)
        out.setsampwidth(2)
        out.setframerate(22050)
        out.writeframes(samples.tobytes())

    size = 44 + 2 * len(samples)
    print(f"{dst}: {len(samples) / 22050:.1f} s, {size / 1024:.0f} KB (limit ~1900 KB)")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])

#!/usr/bin/env python3
"""
ELM327 emulator over a serial / Bluetooth-SPP COM port, for testing FuelRoute's
real BluetoothClassicTransport path from a PC.

This is the "real" ELM327 emulator counterpart to the in-app SimulatedObdTransport:
it serves actual ELM327 text over a stream, so the phone connects over Bluetooth
and drives it exactly like a real dongle.

Setup (Windows):
  1. Install pyserial:  pip install pyserial
  2. Pair the phone with this PC (Settings -> Bluetooth & devices).
  3. Enable an incoming SPP COM port: Settings -> Bluetooth & devices ->
     "More Bluetooth options" -> "COM Ports" tab -> "Add..." -> Incoming.
     Note the COM number, e.g. COM5.
  4. Run:  python tools/elm327_emulator.py COM5
  5. In FuelRoute -> Stats, the PC shows as a bonded Bluetooth device; tap it.

The script answers AT init commands and the PIDs FuelRoute polls (010D/010C/0110/
015E/0105) with a time-varying simulated drive cycle.
"""

import argparse
import sys
import time

try:
    import serial
except ImportError:
    print("pyserial is required: pip install pyserial")
    sys.exit(1)


def speed_at(t):
    cycle = 72.0
    t %= cycle
    if t < 5:
        return 0
    if t < 20:
        return int((t - 5) / 15.0 * 50)
    if t < 30:
        return 50
    if t < 42:
        return 50 + int((t - 30) / 12.0 * 50)
    if t < 47:
        return 100
    if t < 57:
        return int(100 * (1 - (t - 47) / 10.0))
    return 0


def fuel_rate_lph(speed):
    if speed < 1:
        return 0.85
    l100 = 6.0 + 0.0008 * (speed - 75.0) ** 2
    return l100 * speed / 100.0


def two_byte(pid, value):
    v = max(0, min(value, 0xFFFF))
    return "41 %s %02X %02X" % (pid, (v >> 8) & 0xFF, v & 0xFF)


def response_for(command, t):
    cmd = command.strip().upper()
    speed = speed_at(t)
    rpm = 800 if speed < 1 else min(800 + speed * 28, 3200)
    coolant = int(min(92, max(25, 30 + 60 * (t / 15.0))))
    fuel_rate = fuel_rate_lph(speed)
    maf = fuel_rate * 3.04

    table = {
        "010D": "41 0D %02X" % speed,
        "010C": two_byte("0C", rpm * 4),
        "0105": "41 05 %02X" % (coolant + 40),
        "0110": two_byte("10", int(maf * 100)),
        "015E": two_byte("5E", int(fuel_rate * 20)),
        "0100": "41 00 BE 3F A8 13",
        "ATZ": "ELM327 v1.5",
        "ATE0": "OK",
        "ATL0": "OK",
        "ATS0": "OK",
        "ATH0": "OK",
        "ATSP0": "OK",
    }
    return table.get(cmd, "NO DATA")


def main():
    parser = argparse.ArgumentParser(description="ELM327 emulator over a COM/serial port")
    parser.add_argument("port", help="serial/COM port to listen on, e.g. COM5")
    parser.add_argument("--baud", type=int, default=38400)
    args = parser.parse_args()

    ser = serial.Serial(args.port, args.baud, timeout=0.1)
    print("ELM327 emulator listening on %s (Ctrl-C to stop)" % args.port, flush=True)

    start = time.time()
    buf = b""
    while True:
        chunk = ser.read(256)
        if chunk:
            buf += chunk
            while b"\r" in buf or b"\n" in buf:
                line, sep, buf = buf.partition(b"\r")
                if sep == b"":
                    line, sep, buf = buf.partition(b"\n")
                line = line.replace(b"\n", b"").decode("ascii", "ignore").strip()
                if line:
                    resp = response_for(line, time.time() - start)
                    ser.write((resp + "\r>").encode("ascii"))
        time.sleep(0.01)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(0)
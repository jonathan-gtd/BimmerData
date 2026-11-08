# Bluetooth adapters (Deep OBD firmware)

BimmerData talks to the car over Bluetooth through an adapter running the **Deep OBD firmware**
by Ulrich Holeschak (part of [EdiabasLib](https://github.com/uholeschak/ediabaslib), GPL-3.0).
Everything else works as with the K+DCAN cable: dashboard, Android Auto, recording, faults,
Tool32 and the REST API share the one connection.

## Compatible adapters

| Adapter | Works |
|---|---|
| ELM327 (PIC18F25K80) with the [replacement firmware](https://github.com/uholeschak/ediabaslib/blob/master/docs/Replacement_firmware_for_ELM327.md), Bluetooth classic (BC417 / HC-04/05, BK3231, YC1021, SPP_UART) | yes |
| Ready-made "Deep OBD" Bluetooth adapters | yes |
| ELM327 with its **stock** firmware | no: BimmerData says "Standard ELM327 firmware" and refuses it |
| Bluetooth LE only adapters (HC-06 LE variant) | not yet |
| Wi-Fi adapters (ESP8266 variant, ENET) | not yet |

The stock ELM327 firmware only speaks OBD-II and, at best, D-CAN: it cannot run the BMW-FAST
K-line protocol of the E-series before 03/2007. The replacement firmware adds the K-line.

## Which cars

| Car | Line | Adapter setting |
|---|---|---|
| E-series up to 03/2007 (E90/E91 early, E60, E83…) with BMW-FAST | K-line (OBD pin 7) | CAN mode **Automatic** or **K-line only** |
| E-series from 03/2007 | D-CAN (pins 6/14) | D-CAN, not supported by BimmerData yet |
| DS2 cars (E36, E38, E39, E46, E52, E53, E83, E85, E86) | K-line + L-line | needs a pin 7 + 8 bridge in the adapter; DS2 not supported by BimmerData yet |

**K-line on the board:** the ELM327 board must have its K-line driver populated (most PIC18F25K80
boards with "ISO 9141 / KWP2000" support do). Without it the adapter connects, reports its firmware,
but no ECU answers.

**CAN mode:** the adapter stores a CAN mode in its memory. *Automatic* (factory default of the
firmware) first probes D-CAN, sees no CAN bus on a K-line car and falls back to the K-line.
*D-CAN 500 only* never uses the K-line: a pre-2007 car is then unreachable. BimmerData shows the
mode in **Settings › Connection › Bluetooth adapter** and can change it, after a confirmation
(the setting is kept for every app, Deep OBD included).

## Pairing

1. Plug the adapter into the OBD socket and switch the ignition on (the adapter only has power from the car).
2. On the phone: Android **Settings › Bluetooth › Pair new device**, pick the adapter (often
   "Deep OBD BMW", "OBDII" or "OBD2"), PIN **1234** unless you changed it.
3. In BimmerData: **Settings › Connection › Bluetooth adapter › Choose**, pick it. The first time,
   Android asks for the *Nearby devices* permission (Bluetooth connect). BimmerData does not scan
   and does not need location.
4. Tap **Connect** on the dashboard and choose **Bluetooth adapter (Deep OBD)**. Next time
   **Connect** reuses the last mode.

Optional: **Connect to the adapter when the app starts**, for an adapter that stays in the car.

## How it works

- RFCOMM socket, Serial Port Profile (UUID `00001101-…`), like Deep OBD: secure socket for a
  paired device, a second attempt, then the hidden `createRfcommSocket(1)`.
- On connect, BimmerData identifies the adapter with its internal telegrams (`82 F1 F1 FE FE`,
  `FD FD` firmware, `FB FB` id, `FC FC` voltage, CAN mode) — the same sequence as EdiabasLib's
  `EdCustomAdapterCommon.UpdateAdapterInfo`.
- BMW-FAST telegrams then go to the adapter unchanged at 115200 baud; the adapter sends them on
  the K-line, echoes them and returns the ECU answers, exactly like the K+DCAN cable. The fast
  dashboard path (define the `2C F0` block once, then only `21 F0`) works the same.
- Jobs cannot change the adapter: internal telegrams that write its settings are refused.
- There is no ignition line over Bluetooth: Tool32 shows the ignition as unknown.

## Speed

Bluetooth adds a round trip to every telegram. In the simulator (MSV70, 10 values on the fast
path) the dashboard reads **≈ 23 cycles/s without latency and ≈ 14 cycles/s with a 25 ms
Bluetooth round trip**; the K+DCAN cable reaches ≈ 22. Expect 10–15 cycles/s in the car,
depending on the Bluetooth module and the phone.

## Limits

- Only BMW-FAST (concept 0x010F) at 115200 baud. The other K-line rates (DS2, KWP2000\*) need the
  adapter's own telegram format, not ported yet; D-CAN and ENET are not supported yet.
- Bluetooth LE and Wi-Fi adapters are not supported yet.
- The "escape mode" of some car radios (MTC) is not used.

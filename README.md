# BimmerData

Open-source Android app for BMW diagnostics and live data: a customizable card dashboard on the
phone and in **Android Auto**, a CSV datalogger, and fault memory read/clear for every ECU.

BimmerData runs the EDIABAS ECU description files (`.PRG` / `.GRP`) itself, like Tool32 or
Deep OBD do, so it works with any car those files describe. **ECU files are not included**:
each user imports their own `EDIABAS\Ecu` folder.

> Status: early development. A "mini Tool32" screen runs any job of any imported SGBD over a
> K+DCAN cable (BMW-FAST on K-line) or against a built-in simulated ECU.

## Hardware

- K+DCAN USB cable (FTDI) over USB-C OTG — first target (E-series, K-line, BMW-FAST).
- Bluetooth adapter with the [Deep OBD firmware](https://github.com/uholeschak/ediabaslib) — planned.

Supported EDIABAS concepts so far: BMW-FAST (0x010F). DS2, KWP2000\* and D-CAN are planned.

## Mini Tool32

1. Copy your `EDIABAS\Ecu` folder to the phone, then **Import Ecu folder** and pick it
   (only `.prg` / `.grp` are copied into app storage; a re-import replaces them).
2. Choose **USB cable** (K+DCAN plugged via OTG, ignition on) or **Simulation**, then **Connect**.
3. Pick an SGBD (e.g. `msv70.prg`; for a `.grp` use **Identify variant**), pick a job, enter
   arguments separated by `;` (e.g. `STATUS_MESSWERTBLOCK_LESEN` with `3;0x4402;0x4300`), **Run**.
4. Results show every result set, JOB_STATUS, the job time and the telegram count; **Show raw
   frames** lists the telegrams sent / echoed / received with timestamps; **Repeat** runs the job
   N times and shows average, min and max time.

Safety: the interface refuses flashing, coding writes, security access and the programming
session before anything is sent to the car. Fault memory clear is allowed.

## Android Auto

The car pages of the dashboard (edited on the phone: **Edit** › **Android Auto**) are shown in
Android Auto with the same card renderer as the phone, full screen, next to the media card, or as
a small card next to another app (cards then reflow on two columns). Tap the screen to show the
buttons: previous / next page and **Connect** (USB cable or Simulation).

BimmerData uses the Car App Library as a *navigation* app, because that is the only category that
lets an app draw its own screen; it never starts route guidance. If a head unit refuses to hand
over the drawing surface, the same cards are shown as a grid template, refreshed every second.

Live data is shared with the phone: what is visible on the car screen and on the phone screen is
read together; a phone in the pocket (screen off) does not slow down the car screen.

Since the APK is not installed from Google Play, Android Auto only lists it with its developer
settings enabled: on the phone, **Settings › Connected devices › Connection preferences › Android
Auto**, tap **Version** ten times, then **⋮ › Developer settings** › tick **Unknown sources**.

## Building

Requirements: JDK 21 and the Android SDK (`ANDROID_HOME` set).

```sh
./gradlew :ediabas:test :app:assembleDebug
```

Tests that need real ECU files read them from `BIMMERDATA_ECU_DIR` (your own `EDIABAS\Ecu`
folder) and are skipped when it is not set.

Inspect an ECU file from the command line:

```sh
./gradlew :ediabas:prgDump --args="/path/to/Ecu/MSV70.PRG"              # jobs and tables
./gradlew :ediabas:prgDump --args="/path/to/Ecu/MSV70.PRG MESSWERTETAB" # one table
```

## Credits

The EDIABAS file handling and job interpreter are a Kotlin port of
[EdiabasLib](https://github.com/uholeschak/ediabaslib) by Ulrich Holeschak (GPL-3.0).

The dashboard uses the [Barlow](https://github.com/jpt/barlow) and Barlow Semi Condensed fonts
by Jeremy Tribby (SIL Open Font License 1.1, copied from
[google/fonts](https://github.com/google/fonts); license texts in `app/src/main/assets/licenses/`).

BMW, EDIABAS and related names are trademarks of their respective owners. This project is not
affiliated with or endorsed by BMW.

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).

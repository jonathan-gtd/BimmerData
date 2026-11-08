# BimmerData

Open-source Android app for BMW diagnostics and live data: a customizable card dashboard on the
phone and in **Android Auto**, a CSV datalogger, and fault memory read/clear for every ECU.

BimmerData runs the EDIABAS ECU description files (`.PRG` / `.GRP`) itself, like Tool32 or
Deep OBD do, so it works with any car those files describe. **ECU files are not included**:
each user imports their own `EDIABAS\Ecu` folder.

> Status: early development. Nothing usable yet.

## Hardware

- K+DCAN USB cable (FTDI) over USB-C OTG — first target (E-series, K-line).
- Bluetooth adapter with the [Deep OBD firmware](https://github.com/uholeschak/ediabaslib) — planned.

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

BMW, EDIABAS and related names are trademarks of their respective owners. This project is not
affiliated with or endorsed by BMW.

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).

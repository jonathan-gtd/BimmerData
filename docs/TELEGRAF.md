# Export to Telegraf, TimescaleDB and Grafana

BimmerData can send the live values it reads to your own server, to look at your trips in
Grafana:

```
phone (BimmerData) --HTTP, line protocol--> Telegraf --postgresql output--> TimescaleDB <-- Grafana
```

- **Telegraf** receives the points over HTTP and writes them to PostgreSQL / TimescaleDB with its
  `postgresql` output, which creates the table by itself (as a TimescaleDB hypertable with the
  template in [deploy/telegraf/telegraf.conf](../deploy/telegraf/telegraf.conf)).
- The phone never talks to the database: no PostgreSQL port to expose, no database driver on
  Android.
- The wire format is the text "line protocol" (one point per line, with its time). It is only a
  format: **no InfluxDB is involved**.

Everything needed is in [deploy/telegraf/](../deploy/telegraf/):

| File | What |
|---|---|
| `telegraf.conf` | `influxdb_v2_listener` input (token) → buffer → `postgresql` output (hypertable) |
| `timescaledb.sql` | Optional: database, user and table by hand |
| `docker-compose.yml`, `.env.example` | Telegraf + TimescaleDB + Grafana, for a fresh start |
| `grafana/bimmerdata-dashboard.json` | Grafana dashboard to import (curves, table, map of the GPS track) |
| `grafana/provisioning/` | Grafana data source for the compose stack |

## Quick start with Docker

```sh
cd deploy/telegraf
cp .env.example .env        # change the three secrets
docker compose up -d
```

Then on the phone, **Settings › Telegraf export**:

1. **URL**: `http://<server>:8186/api/v2/write`
2. **Authentication**: Token, the `BIMMERDATA_TOKEN` of `.env`
3. **Car name**: e.g. `My car` (tag `car`, to tell several cars apart)
4. **Test**, then enable **Export to Telegraf**.

In Grafana (`http://<server>:3000`, user `admin`), import
`grafana/bimmerdata-dashboard.json` and pick the **BimmerData** data source.

## Existing Telegraf / TimescaleDB

Add the `[[inputs.influxdb_v2_listener]]` and `[[outputs.postgresql]]` sections of
`telegraf.conf` to your configuration (keep `namepass = ["bimmerdata"]` so other metrics do not
land in that database), with the environment variables `BIMMERDATA_TOKEN` and `PG_CONNECTION`.
Check `metric_buffer_limit` in `[agent]` (see [No data lost](#no-data-lost)), and read the
note on the disk buffer there before turning it on.
The PostgreSQL user needs the right to create tables in the schema, and the database needs the
`timescaledb` extension (`CREATE EXTENSION timescaledb;`). Without TimescaleDB, remove the
`create_hypertable` line: it then works with plain PostgreSQL.

Basic auth instead of a token: use the commented `http_listener_v2` section and choose
**Authentication › Password** in the app.

## What is sent

One table, `bimmerdata`:

| Column | Example | From |
|---|---|---|
| `time` | `2026-11-08 18:00:00.25+00` | when the value was read in the car (not when it was uploaded), UTC |
| `car` | `My car` | the selected car's name (My cars), else Settings › Telegraf export › Car name |
| `profile` | `My car · Lambda` | the active profile (left out when there is none) |
| `ecu` | `<sgbd>`, `gps` | SGBD of the measurement; `gps` for the phone's position |
| `id` | `0x4402`, `STATUS_OEL/STAT_PHY_NIVOEL_WERT`, `speed` | measurement key (same as the dashboard) |
| `label` | `Oil temperature` | label shown in the app |
| `unit` | `°C` | unit (left empty when there is none) |
| `value` | `87.5` | numeric value |
| `text` | `LSH_OFF` | text / state results (column created on the first one) |

Only the measurements the app is reading are exported (what the phone or Android Auto shows,
what is recorded to CSV, what the API asks for), and **every reading is exported**, with its own
time, at the read rate: Grafana gets the same data as the CSV. There is no points-per-second
setting and no decimation; a value that was not read again is not sent twice.

Load: at most 8 reads/s per ECU (see the README, "What is read") × N measurements. With 16
measurements that is about 128 points/s, ≈ 14 KB/s of line protocol, ≈ 1–2 KB/s once
gzip-compressed on the phone (≈ 6 MB per hour of driving). Telegraf and TimescaleDB take that easily; batches of about 256 KB (raw)
are closed and sent every ten seconds or so.

**Read statistics** of the app, every 5 s while reading: `ecu = 'bimmerdata'` with the ids
`stat_reads_per_s`, `stat_values_per_block` (biggest block), `stat_read_interval_ms` (start to
start between two reads of one ECU: 125, more after an automatic slow-down), `stat_slowdowns`,
`stat_defines`, `stat_timeouts` (reads nobody answered: an ECU that reboots answers nothing for a
second or two), `stat_errors`, `stat_slow_cycles` (> 150 ms), `stat_median_cycle_ms` and
`stat_p90_cycle_ms`. Counters count from the connection. Example:

```sql
SELECT time, id, value FROM bimmerdata
WHERE ecu = 'bimmerdata' AND id IN ('stat_timeouts', 'stat_slowdowns', 'stat_read_interval_ms')
  AND time > now() - interval '1 hour' ORDER BY time;
```

**GPS position** (optional, Settings › GPS position, off by default): `ecu = 'gps'` with the ids
`latitude`, `longitude` (degrees), `altitude` (m), `speed` (km/h), `bearing` (degrees, 0 = north)
and `accuracy` (m). They are exported whenever the option is on, even without a card showing
them. The example dashboard draws them on a Geomap panel.

### Times and time zones

- The phone sends each point with its time as **UTC epoch nanoseconds** (line protocol); the
  `time` column is `timestamp with time zone`. There is no local time anywhere in the export, so
  a daylight saving change (e.g. Europe/Paris, +01:00 / +02:00) can neither duplicate nor shift
  points.
- Grafana converts to a time zone only for display: the example dashboard uses
  `"timezone": "browser"`, i.e. the time zone of the computer looking at it. Change it in the
  dashboard settings (e.g. `Europe/Paris`) if the browser is elsewhere.
- In SQL, convert explicitly: `time AT TIME ZONE 'Europe/Paris'`.
- The CSV recordings on the phone use local time **with its offset**
  (`2026-11-08T21:45:00.123+01:00`), for the same reason.

Example query (5-second averages of the oil temperature):

```sql
SELECT time_bucket('5 seconds', time) AS t, avg(value)
FROM bimmerdata
WHERE car = 'My car' AND ecu = '<sgbd>' AND id = '<id>' AND time > now() - interval '1 day'
GROUP BY 1 ORDER BY 1;
```

## When the points are sent

- **After the trip** (default): the points wait on the phone and are uploaded once it is on an
  unmetered network (home Wi-Fi), with their original times. Android starts the upload even if
  the app was closed in the meantime.
- **Live**: uploaded every few seconds on any network, mobile data included. The server must be
  reachable from the road: prefer a VPN (WireGuard, Tailscale…) to an open port, and HTTPS
  (`tls_cert` / `tls_key` in the listener, or a reverse proxy).

**Android 17 and later:** a Telegraf server on the local network (private address such as
`192.168.x.x`, or a name such as `nas` / `server.local`) can only be reached once the
**local network** permission is granted (`ACCESS_LOCAL_NETWORK`, under *Nearby devices*).
BimmerData asks for it when you enable the export or press **Test** with such a URL, and shows a
red banner while it is missing. Points stay in the queue meanwhile: nothing is lost, they leave
once the permission is granted. A server on the internet, or reached through a VPN address
outside the private ranges, does not need it.

The Settings page shows the state, the points waiting, their size on the phone, the free space
and the room left before the reserve, the last error, and the batches refused by the server.

## No data lost

Goal: a point read in the car reaches TimescaleDB, or stays somewhere you can see and recover it.

**On the phone** (queue in the app's private storage, `files/telegraf_queue/`):

- **Nothing is ever deleted automatically.** A point leaves the phone only once Telegraf answered
  **HTTP 2xx** for its batch.
- Network errors, timeouts, 5xx, 429, **404** and other temporary answers: the batch stays and is
  retried (2 s up to 5 min between tries). 401/403: it waits until the token or password is fixed.
- **Few requests.** Live, a request carries at least 100 points, never more than one every 2 s
  (so about 200 points at 100 values per second), and a point never waits more than 30 s (a slow
  source, the end of a session). A backlog (after the trip, after an outage) goes batch after
  batch without waiting.
- **A slow server gets smaller batches.** A timeout, 413, 502, 503 or 504 (typically Telegraf's
  listener taking more than its 10 s behind a reverse proxy) halves the batch size, down to 50
  lines; only the lines of a batch that got a 2xx leave the phone (the rest of the batch file is
  rewritten first, synced, then the old file is deleted). The size grows again after 10 batches
  in a row went through, never back to a size that failed (tried again after 100 batches).
- A batch Telegraf refuses as invalid (**400**, 422) is moved to `telegraf_queue/rejected/`,
  out of the upload loop, and **kept**: the page shows how many, with **Retry** (put them back in
  the queue, e.g. after fixing the server), **Export** (one `.lp.gz` file: `zcat` gives the line
  protocol back) and **Delete** (only on your request, with a confirmation).
- No size limit: the queue may use all the free space of the phone **except a fixed reserve of
  100 MB** kept for the phone (not a setting). When the reserve is reached, new points are **not queued**
  (and counted) instead of deleting old ones; a notification and a banner say so. A notification
  also tells when the queue grows past 100 MB, 500 MB, 1 GB…
- Closed batches are **gzip-compressed** on the phone (line protocol compresses about 10×, much
  more for steady values) and sent as-is with `Content-Encoding: gzip`, which both
  `influxdb_v2_listener` and `http_listener_v2` accept. A batch file is written to a temporary
  file, synced to disk (fsync), then renamed: after a crash or a phone reboot, the queue reloads
  every batch, and a batch cut in the middle of a line loses only that last partial line.
- **Clear queue** deletes the waiting points, only on your request, with a confirmation.
- Independently, the **CSV recording** is a complete local copy of what was recorded.

**On the server**, Telegraf answers 204 as soon as the points are in its buffer, before they are
in the database. The example `telegraf.conf` keeps Telegraf's default **memory buffer**: the
points are written every 10 s, and at a normal stop. They are lost only if Telegraf crashes
(`kill -9`, power cut) before that write, or is restarted while the database is down, or if more
than `metric_buffer_limit` points pile up during an outage (Telegraf then drops the oldest and
logs it).

```toml
[agent]
  metric_buffer_limit = 1000000                  # about 1 KB of memory per point

[[outputs.postgresql]]
  startup_error_behavior = "retry"               # start even if the database is down
```

**Disk buffer (optional).** `buffer_strategy = "disk"` with a persistent `buffer_directory` makes
the points survive a crash and a restart during an outage too, but Telegraf 1.40 syncs every
point to disk, once per output that receives it. Measured on a SATA SSD: about 9 ms per point
with two outputs, so batches of 1000 points took more than the listener's 10 s and came back as
502 through the reverse proxy. The app copes (smaller batches), but uploads are limited to about
100-200 points per second. If you turn it on, make sure only the `bimmerdata` output receives
these points (`namepass`), and raise `read_timeout` / `write_timeout` of the listener. In Docker
the directory is the `telegraf-buffer` volume of the compose file. The Docker stack itself was
not run here (only Telegraf, PostgreSQL and TimescaleDB on Debian).

The token or password is stored encrypted with the Android Keystore and is never returned by the
REST API. Everything else can also be read and changed through the API
(`/api/v1/export/telegraf`, scope `config`, see [API.md](API.md)).

## Checked with

Telegraf 1.40.2, PostgreSQL 17 with TimescaleDB 2.31 (Debian, not Docker):

- A simulated 30 s session of 3 measurements (450 points) queued "after the trip", uploaded on
  Wi-Fi in gzip batches, lands in the hypertable with the exact sample times
  (`live/src/test/.../TelegrafEndToEndTest.kt`, run with `BIMMERDATA_TELEGRAF_URL` and
  `BIMMERDATA_TELEGRAF_TOKEN` set).
- Database stopped → 1 000 points posted (gzip, 204) → Telegraf killed with `kill -9` and
  restarted → database started: all 1 000 points in the table.
- Telegraf started while the database is down: the listener accepts points at once; they reach
  the table once the database is back.
- JVM tests (`QueueSafetyTest`): no deletion without a 2xx, 404 retried, 400 kept in `rejected/`
  and sent again by Retry, reserve reached → queuing stops without deleting, gzip round trip,
  crash recovery.

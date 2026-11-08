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
| `telegraf.conf` | `influxdb_v2_listener` input (token) → disk buffer → `postgresql` output (hypertable) |
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
3. **Car name**: e.g. `E90 330i` (tag `car`, to tell several cars apart)
4. **Test**, then enable **Export to Telegraf**.

In Grafana (`http://<server>:3000`, user `admin`), import
`grafana/bimmerdata-dashboard.json` and pick the **BimmerData** data source.

## Existing Telegraf / TimescaleDB

Add the `[[inputs.influxdb_v2_listener]]` and `[[outputs.postgresql]]` sections of
`telegraf.conf` to your configuration (keep `namepass = ["bimmerdata"]` so other metrics do not
land in that database), with the environment variables `BIMMERDATA_TOKEN` and `PG_CONNECTION`.
Also copy the `buffer_strategy`, `buffer_directory` and `metric_buffer_limit` lines of `[agent]`
(see [No data lost](#no-data-lost)): the directory must exist, be writable by the `telegraf`
user, and survive restarts (e.g. `/var/lib/telegraf/buffer`).
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
| `car` | `E90 330i` | Settings › Telegraf export › Car name |
| `ecu` | `msv70`, `gps` | SGBD of the measurement; `gps` for the phone's position |
| `id` | `0x4402`, `STATUS_OEL/STAT_PHY_NIVOEL_WERT`, `speed` | measurement key (same as the dashboard) |
| `label` | `Oil temperature` | label shown in the app |
| `unit` | `°C` | unit (left empty when there is none) |
| `value` | `87.5` | numeric value |
| `text` | `LSH_OFF` | text / state results (column created on the first one) |

Only the measurements the app is reading are exported (what the phone or Android Auto shows,
what is recorded to CSV, what the API asks for). **Points per second** (1–5) limits each
measurement, whatever the read rate.

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
WHERE car = 'E90 330i' AND ecu = 'msv70' AND id = '0x4402' AND time > now() - interval '1 day'
GROUP BY 1 ORDER BY 1;
```

## When the points are sent

- **After the trip** (default): the points wait on the phone and are uploaded once it is on an
  unmetered network (home Wi-Fi), with their original times. Android starts the upload even if
  the app was closed in the meantime.
- **Live**: uploaded every few seconds on any network, mobile data included. The server must be
  reachable from the road: prefer a VPN (WireGuard, Tailscale…) to an open port, and HTTPS
  (`tls_cert` / `tls_key` in the listener, or a reverse proxy).

The Settings page shows the state, the points waiting, their size on the phone, the free space
and the room left before the reserve, the last error, and the batches refused by the server.

## No data lost

Goal: a point read in the car reaches TimescaleDB, or stays somewhere you can see and recover it.

**On the phone** (queue in the app's private storage, `files/telegraf_queue/`):

- **Nothing is ever deleted automatically.** A point leaves the phone only once Telegraf answered
  **HTTP 2xx** for its batch.
- Network errors, timeouts, 5xx, 429, **404** and other temporary answers: the batch stays and is
  retried (2 s up to 5 min between tries). 401/403: it waits until the token or password is fixed.
- A batch Telegraf refuses as invalid (**400**, 413, 422) is moved to `telegraf_queue/rejected/`,
  out of the upload loop, and **kept**: the page shows how many, with **Retry** (put them back in
  the queue, e.g. after fixing the server), **Export** (one `.lp.gz` file: `zcat` gives the line
  protocol back) and **Delete** (only on your request, with a confirmation).
- No size limit: the queue may use all the free space of the phone **except a reserve**
  (500 MB by default; 200 MB to 2 GB). When the reserve is reached, new points are **not queued**
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
in the database. With the default memory buffer, a database outage plus a Telegraf restart (or a
crash) would lose them. The example `telegraf.conf` therefore sets:

```toml
[agent]
  buffer_strategy = "disk"                       # points wait on disk, not only in memory
  buffer_directory = "/var/lib/telegraf/buffer"  # persistent, writable by telegraf
  metric_buffer_limit = 20000000                 # room for days of driving

[[outputs.postgresql]]
  startup_error_behavior = "retry"               # start even if the database is down
```

The limit of the guarantee: a 204 means "received by Telegraf". With the disk buffer, the points
then survive a Telegraf restart, a crash (`kill -9`) and a database outage. They are lost only if
the server's disk itself is lost, or if more than `metric_buffer_limit` points pile up while the
database is down (Telegraf then drops the oldest and logs it). In Docker the buffer is the
`telegraf-buffer` volume of the compose file; if Telegraf cannot write there, it says so in
`docker compose logs telegraf` (fix the owner of the volume to the `telegraf` user). The Docker
stack itself was not run here (only Telegraf, PostgreSQL and TimescaleDB on Debian).

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

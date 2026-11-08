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
| `telegraf.conf` | `influxdb_v2_listener` input (token) → `postgresql` output (hypertable) |
| `timescaledb.sql` | Optional: database, user and table by hand |
| `docker-compose.yml`, `.env.example` | Telegraf + TimescaleDB + Grafana, for a fresh start |
| `grafana/bimmerdata-dashboard.json` | Grafana dashboard to import |
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
The PostgreSQL user needs the right to create tables in the schema, and the database needs the
`timescaledb` extension (`CREATE EXTENSION timescaledb;`). Without TimescaleDB, remove the
`create_hypertable` line: it then works with plain PostgreSQL.

Basic auth instead of a token: use the commented `http_listener_v2` section and choose
**Authentication › Password** in the app.

## What is sent

One table, `bimmerdata`:

| Column | Example | From |
|---|---|---|
| `time` | `2026-11-08 18:00:00.25+00` | when the value was read in the car (not when it was uploaded) |
| `car` | `E90 330i` | Settings › Telegraf export › Car name |
| `ecu` | `msv70` | SGBD of the measurement |
| `id` | `0x4402`, `STATUS_OEL/STAT_PHY_NIVOEL_WERT` | measurement key (same as the dashboard) |
| `label` | `Oil temperature` | label shown in the app |
| `unit` | `°C` | unit (left empty when there is none) |
| `value` | `87.5` | numeric value |
| `text` | `LSH_OFF` | text / state results (column created on the first one) |

Only the measurements the app is reading are exported (what the phone or Android Auto shows,
what is recorded to CSV, what the API asks for). **Points per second** (1–5) limits each
measurement, whatever the read rate.

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

The queue lives in the app's private storage (50 MB at most, about 500 000 points). When it is
full, the oldest points are dropped first. Network errors and server errors are retried (2 s up
to 5 min between tries). A wrong token stops the upload until the settings change; the Settings
screen shows the state, the number of waiting points and the last error. **Clear queue** deletes
the waiting points.

The token or password is stored encrypted with the Android Keystore and is never returned by the
REST API. Everything else can also be read and changed through the API
(`/api/v1/export/telegraf`, scope `config`, see [API.md](API.md)).

## Checked with

Telegraf 1.40, PostgreSQL 17 with TimescaleDB 2.31: a simulated 30 s session of 3 measurements
(450 points) queued "after the trip", uploaded on Wi-Fi, lands in the hypertable with the exact
sample times (`live/src/test/.../TelegrafEndToEndTest.kt`, run with `BIMMERDATA_TELEGRAF_URL` and
`BIMMERDATA_TELEGRAF_TOKEN` set).

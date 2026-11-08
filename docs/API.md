# BimmerData REST API

BimmerData can run a small HTTP/JSON API on the phone so that scripts and AI agents on the same
local network can read live values, build dashboard layouts (phone and Android Auto) and manage
the configuration. It is **off by default**.

- Base URL: `http://<phone-ip>:<port>/api/v1` (default port 8765), or `https://…` when HTTPS is
  switched on (see [HTTPS](#https))
- Authentication: `Authorization: Bearer <token>` on every request
- Machine-readable spec: `GET /api/v1/openapi.json` (OpenAPI 3.1, no token needed)
- Interactive documentation: `GET /api/v1/docs` in a browser (Swagger UI embedded in the app, no
  token needed to read it; **Authorize** with the token to try the operations). The same page,
  read-only, is in the app: **Settings › Local API › View the API documentation**.

The OpenAPI document is the reference: every operation has a description, its parameters, the
request / response schemas, examples and its error codes. This page is the human overview.

## Enabling it

1. On the phone: **Settings › Local API › Enable the REST API**.
2. Check the **Permissions** (scopes) you want to grant, see below. Default: Read + Dashboard layout.
3. The screen shows the address(es) the server listens on (e.g. `http://192.168.1.20:8765/api/v1`),
   the token (Show / Copy / Share / Regenerate) and a ready-made line for an AI agent:

   ```
   BimmerData REST API: http://192.168.1.20:8765/api/v1, token: bd_… (send the header "Authorization: Bearer <token>"), OpenAPI spec: http://192.168.1.20:8765/api/v1/openapi.json
   ```

The phone and the computer must be on the same network: the same Wi-Fi, or the computer
connected to the phone's hotspot (handy in the car). The server never listens on mobile data or
VPN interfaces. It also listens on `127.0.0.1`, so with a USB cable you can use
`adb forward tcp:8765 tcp:8765` and call `http://127.0.0.1:8765/api/v1` from the computer.

## Using it from an AI agent

No plug-in is needed: an agent that can run `curl` (Claude Code, other coding agents) or that
imports OpenAPI documents as tools can use the API directly.

**Claude Code** (or any shell-capable agent): paste the instruction line from Settings into the
conversation, for example:

> BimmerData REST API: http://192.168.1.20:8765/api/v1, token: bd_xxx (send the header
> "Authorization: Bearer <token>"), OpenAPI spec: http://192.168.1.20:8765/api/v1/openapi.json.
> Read the spec, then build me an Android Auto page with oil temperature, coolant temperature and
> both fuel trims, and show me a picture of it.

The agent fetches the spec with `curl`, then calls the operations. Tip: keep the token out of
files that you commit; an environment variable works well:

```sh
export BD=http://192.168.1.20:8765/api/v1
export BD_TOKEN=bd_xxx
```

Fetch tools that cannot send an `Authorization` header can still read `openapi.json` (public),
but every other call needs the header.

**OpenAPI-aware tools** (agent frameworks that turn an OpenAPI document into tools, HTTP clients
such as Postman / Bruno / Insomnia, code generators): import
`http://<phone-ip>:<port>/api/v1/openapi.json`; the document's `servers` entry already points at
the phone, and the security scheme is HTTP bearer.

A typical agent session:

1. `GET /api/v1` – check the token, see the granted scopes.
2. `GET /api/v1/status` – connected? If not, `POST /api/v1/connect` (`{"mode":"simulation"}` needs no car).
   `GET /api/v1/profiles` – which profile (car, main ECUs, pages) is active; a fresh install has
   none: `POST /api/v1/profiles` creates one. The dashboard routes act on the active profile.
3. `GET /api/v1/ecus`, `GET /api/v1/ecus/<sgbd>/measurements?q=temp` – find measurement ids.
4. `GET /api/v1/card-types` – what a card can be (a page is a `columns × rows` grid).
5. `POST /api/v1/dashboard/car/pages`, `POST …/pages/{page}/cards` – build.
6. `GET /api/v1/dashboard/car/pages/{page}/render.png` – look at the result, adjust with `PATCH`.

## curl examples

```sh
H="Authorization: Bearer $BD_TOKEN"

# Discovery
curl -s "$BD/openapi.json" | head
curl -s -H "$H" "$BD"
curl -s -H "$H" "$BD/status"

# Connect to the built-in simulated ECU (scope config)
curl -s -H "$H" -X POST "$BD/connect" -d '{"mode":"simulation"}'

# Measurements of the DME, filtered
curl -s -H "$H" "$BD/ecus/<sgbd>/measurements?q=temp"

# Read two values now (waits for fresh samples)
curl -s -H "$H" -X POST "$BD/values/read" -d '{"ids":["<sgbd>/<id>","<sgbd>/<id2>"]}'

# Sample for 10 s: series with min / max / mean
curl -s -H "$H" -X POST "$BD/values/read" -d '{"ids":["<sgbd>/<id>"],"seconds":10}'

# Keep reading for 5 minutes, poll the latest values, get the last minute of history
curl -s -H "$H" -X POST "$BD/values/watch" -d '{"ids":["<sgbd>/<id>"],"seconds":300}'
curl -s -H "$H" "$BD/values?ids=<sgbd>/<id>"
curl -s -H "$H" "$BD/history?ids=<sgbd>/<id>&seconds=60"

# Live stream (Server-Sent Events)
curl -N -H "$H" "$BD/stream?ids=<sgbd>/<id>,<sgbd>/<id2>&intervalMs=250"

# Layout: whole dashboard, a new car page, a card, a change, a picture (scope layout for writes)
curl -s -H "$H" "$BD/dashboard" > dashboard-backup.json
curl -s -H "$H" -X POST "$BD/dashboard/car/pages" -d '{"name":"Temps","columns":4,"rows":1}'
curl -s -H "$H" -X POST "$BD/dashboard/car/pages/1/cards" \
     -d '{"type":"Dial","measurementIds":["<sgbd>/<id>"],"title":"Oil","warnAbove":120}'
curl -s -H "$H" -X PATCH "$BD/dashboard/car/pages/1/cards/<cardId>" -d '{"warnAbove":125}'
curl -s -H "$H" "$BD/dashboard/car/pages/1/render.png" -o page.png

# Validate, then replace the whole layout
curl -s -H "$H" -X POST "$BD/dashboard/validate" --data-binary @dashboard.json
curl -s -H "$H" -X PUT  "$BD/dashboard" --data-binary @dashboard.json

# Recording (scope config) and download
curl -s -H "$H" -X POST "$BD/recording/start"
curl -s -H "$H" -X POST "$BD/recording/markers" -d '{"label":"full throttle"}'
curl -s -H "$H" -X POST "$BD/recording/stop"
curl -s -H "$H" "$BD/recordings"
curl -s -H "$H" "$BD/recordings/bimmerdata_2026-11-08_14-03-12.csv" -o run.csv
```

## Operations

`{target}` is `phone` or `car` (Android Auto). `{page}` is a page id or its 0-based index.

| Method | Path | Scope | What |
|---|---|---|---|
| GET | `/api/v1/openapi.json` | none | OpenAPI 3.1 document |
| GET | `/api/v1/docs`, `/api/v1/docs/{file}` | none | Swagger UI (embedded, Apache-2.0) |
| GET | `/api/v1` | any token | Version and granted scopes |
| GET | `/api/v1/status` | read | Connection, reads/s, what is read, recording |
| GET | `/api/v1/ecus` | read | SGBDs with measurements (detected ECUs first, once available) |
| GET | `/api/v1/ecus/{sgbd}/measurements?q=&limit=&offset=` | read | Measurements of one ECU |
| GET | `/api/v1/measurements?q=` | read | Search across all ECUs |
| GET | `/api/v1/values?ids=` | read | Latest values, no waiting |
| POST | `/api/v1/values/read` | read | Read now (wait for fresh values), optional sampling series |
| POST / DELETE | `/api/v1/values/watch` | read | Keep measurements read for a while / stop |
| GET | `/api/v1/history?ids=&seconds=` | read | Samples recorded by the API (last 10 min) |
| GET | `/api/v1/stream?ids=&intervalMs=` | read | Server-Sent Events of live values |
| GET | `/api/v1/dashboard` | read | Whole layout |
| PUT | `/api/v1/dashboard[?dryRun=true]` | layout | Replace the layout (strict validation) |
| POST | `/api/v1/dashboard/validate` | read | Validate a layout without saving |
| POST | `/api/v1/dashboard/reset`, `/api/v1/dashboard/{target}/reset` | layout | Empty both sides / one side |
| GET / POST | `/api/v1/dashboard/{target}/pages` | read / layout | List / add pages |
| GET / PATCH / DELETE | `/api/v1/dashboard/{target}/pages/{page}` | read / layout | One page: get, rename / resize / move, delete |
| POST | `/api/v1/dashboard/{target}/pages/{page}/cards` | layout | Add a card (exact cell or first free slot) |
| PATCH / DELETE | `/api/v1/dashboard/{target}/pages/{page}/cards/{card}` | layout | Change (JSON merge patch) / delete a card |
| GET | `/api/v1/dashboard/{target}/pages/{page}/render.png` | read | PNG of a saved page |
| POST | `/api/v1/dashboard/{target}/render` | read | PNG of a draft page (body = page) |
| GET | `/api/v1/card-types` | read | Card types and measurement counts |
| GET / POST | `/api/v1/profiles` | read / layout | Profiles and the active one / create a profile (optionally copying another's pages) |
| GET / PUT / DELETE | `/api/v1/profiles/{profile}` | read / layout | One profile with its layout / change name, car, ECUs or layout / delete |
| POST | `/api/v1/profiles/{profile}/activate` | layout | Show this profile on the phone and in Android Auto |
| GET | `/api/v1/profiles/{profile}/export` | read | The shareable profile file (never the VIN link) |
| POST | `/api/v1/connect` | config | Connect (`usb` / `simulation`) |
| POST | `/api/v1/disconnect` | config | Disconnect |
| GET | `/api/v1/recording` | read | Recording state |
| POST | `/api/v1/recording/start`, `/stop`, `/markers` | config | Control the CSV recording |
| GET | `/api/v1/recordings`, `/api/v1/recordings/{name}` | read | List / download CSV files |
| GET / PATCH | `/api/v1/settings` | read / config | App settings (API settings are read-only) |
| GET / PATCH | `/api/v1/export/telegraf` | config | Telegraf export settings and queue (token / password never returned, changed on the phone only) |
| POST | `/api/v1/export/telegraf/test`, `/send` | config | Test the listener / upload the waiting points now |
| GET | `/api/v1/diag/vehicle` | diag | Last detected vehicle, its ECUs and their last fault read (no car traffic, never the VIN) |
| GET | `/api/v1/diag/faults` | diag | Read the fault memory from the car (`?ecu=0x12` for one ECU, `?cached=true` for the last read) |
| POST | `/api/v1/diag/faults/clear` | diag | **Writes.** Clear one ECU (`{"ecu":"0x12"}`) or all (`{}`), after a confirmation on the phone |
| POST | `/api/v1/diag/jobs` | diag | **May write.** Run one EDIABAS job (`{"sgbd","job","args"}`), after a confirmation on the phone |

### Layout rules

- Phone pages: up to 4 columns × 8 rows, 32 cards. Android Auto pages: up to 4 × 2, 4 cards.
- A card covers `colSpan × rowSpan` cells from (`col`, `row`), 0-based; cards may not overlap
  or leave the grid.
- Card types: `Dial`, `BipolarBar` (1–4 measurements), `BigNumber`, `Trend`, `MultiValue`
  (2–3 measurements), `Status`; the others take exactly one measurement.
- Unknown JSON fields are rejected (400), so typos do not silently vanish.
- Measurement ids that are not in the imported ECU folder are accepted but reported as
  `warnings` (the card shows "--" until the SGBD is imported).

### Errors

Every error has the same shape and a meaningful status:

```json
{"error":{"code":"card_overlap","message":"card 'k3j4…' (col 0, row 1, 1×1) overlaps card 'a1b2…' (col 0, row 0, 1×2); free cells: (1,0) (1,1) (0,2) (1,2)"}}
```

`400` bad input · `401` missing / wrong token · `403` scope not enabled (or API settings,
which are phone-only) · `404` unknown id · `405` wrong method · `409` conflict (overlap, page
full, last page, not connected) · `413` body over 1 MB · `422` invalid layout (`details` lists
each problem with its JSON path) · `429` rate limited (`Retry-After`). Diagnostics add `403 refused_on_phone`,
`408 confirmation_timeout`, `409 vehicle_moving` and `503 confirmation_unavailable` / `not_connected`.

## HTTPS

Off by default, so existing clients keep working. **Settings › Local API › HTTPS** switches the
server to HTTPS only, on the same port: `http://` requests then fail the TLS handshake and get no
answer. The token and the scopes are unchanged.

There is no domain name on a home network, so no public certificate authority can issue a
certificate for the phone. The phone therefore has **its own small certificate authority**,
created the first time HTTPS is switched on:

| | What it is | Shown on the phone as |
|---|---|---|
| BimmerData CA certificate | EC P-256, valid 20 years. The file a client trusts. Its critical *name constraints* limit it to private IPv4 addresses (10/8, 172.16/12, 192.168/16, 127/8) and `localhost`: even installed as a trusted root it cannot vouch for an internet site. | **Certificate fingerprint (SHA-256)** `AB:CD:…`, **Export certificate** (`bimmerdata-api-ca.pem`) |
| Server certificate | EC P-256, valid 10 years, signed by the CA, SAN = `localhost`, `127.0.0.1` and the phone's local addresses. When the phone gets a new address it is re-issued **with the same key**. | **Public key pin** `sha256//…` |

Both the CA fingerprint and the public-key pin stay the same when the phone changes address.
They change only with **Regenerate certificate**, after which every client must trust / pin
again. Only TLS 1.3 and TLS 1.2 are offered.

A client does **one** of these:

**Pin the public key** (nothing to install, works with any address):

```sh
export BIMMERDATA_TOKEN=bd_xxx
curl --pinnedpubkey 'sha256//<pin shown on the phone>' -k \
  -H "Authorization: Bearer $BIMMERDATA_TOKEN" https://192.168.1.20:8765/api/v1/status
```

`-k` only skips the certificate-authority check; curl still refuses the connection unless the
server's key matches the pin.

**Trust the exported certificate** (full verification, address included). Copy
`bimmerdata-api-ca.pem` from the phone (Export certificate, or Share), then check that it is the
right one before trusting it:

```sh
openssl x509 -in bimmerdata-api-ca.pem -noout -fingerprint -sha256   # must match the phone
curl --cacert bimmerdata-api-ca.pem -H "Authorization: Bearer $BIMMERDATA_TOKEN" \
  https://192.168.1.20:8765/api/v1/status
```

```python
import os, requests
r = requests.get("https://192.168.1.20:8765/api/v1/status",
    headers={"Authorization": "Bearer " + os.environ["BIMMERDATA_TOKEN"]},
    verify="bimmerdata-api-ca.pem")
print(r.json())
```

With the Python standard library: `ssl.create_default_context(cafile="bimmerdata-api-ca.pem")`.
The certificates pass Python 3.13's strict X.509 checks and `openssl verify -x509_strict`.

The same fingerprints are readable with `GET /api/v1/settings` (`api.https`,
`api.certificateSha256`, `api.publicKeyPin`); `openapi.json` announces `https://` while HTTPS is on.
The in-app documentation does not go through the network, so it is unaffected.

**Where the keys live**: both private keys are stored in one file in the app's private storage
(`files/api_tls.bin`), encrypted with AES-256-GCM by a key that never leaves the Android Keystore.
The keys themselves are ordinary software keys, not Keystore keys: a Keystore-generated
certificate cannot carry the subject alternative names a client checks. The file is not backed
up. If it cannot be read (for instance after the phone was restored), a new identity is created
and the fingerprints change. If no identity can be prepared, the server stays off rather than
fall back to plain HTTP.

## Security model

- **Off by default.** Nothing listens until the switch is on; switching it off closes the sockets.
- **Token**: 256 random bits (`bd_` + 43 characters), generated on the phone, compared in constant
  time, never logged, never returned by the API, excluded from backups (`allowBackup=false`).
  Regenerating it immediately locks out every client using the old one.
- **Scopes**: each operation needs one scope, granted by toggles on the phone:
  `read` (values, ECUs, layout, recordings, pictures), `layout` (edit pages and cards),
  `config` (connect / disconnect, recordings, connection settings), `diag` (fault memory;
  clearing faults and running jobs, each confirmed on the phone, see [Diagnostics](#diagnostics)).
  The API settings themselves (enable, port, token, scopes) can only be changed on the phone.
- **Local network only**: the server binds the phone's private IPv4 addresses on Wi-Fi,
  Ethernet, USB tethering and its own hotspot (plus 127.0.0.1), never mobile data or VPN
  interfaces, and drops connections from non-private addresses.
- **Limits**: about 20 requests/s per client (bursts of 60), lock-out for 5 minutes after 10
  wrong tokens, 16 connections, 4 event streams, 1 MB request bodies, 16 KB headers.
- **Encryption**: plain HTTP by default. On a shared or public Wi-Fi, anyone sniffing can then
  read the token: switch [HTTPS](#https) on, or use the API on networks you trust (home Wi-Fi,
  the phone's own hotspot), over a VPN or over `adb forward`.
- **Writes to ECUs only with the user's consent, per call.** Without the `diag` scope the API
  reads live values and changes app data only. With it, clearing faults and running jobs wait
  for the user's confirmation on the phone, every single time, and are refused while the car is
  moving; see [Diagnostics](#diagnostics).

## Lifetime on Android

The server lives in the app process:

- it answers while BimmerData is open (any screen);
- in the background it keeps answering while the acquisition foreground service runs (USB
  connection to the car, with its ongoing notification);
- otherwise (app in the background, simulation mode or not connected) Android may freeze or kill
  the cached process after a while; the API then stops answering until the app is opened again.
  This is deliberate: a dedicated foreground service only for the API would need a
  `specialUse` justification and a permanent notification.

With the screen off, Wi-Fi power saving can add latency to the first request.

## Diagnostics

Scope `diag` (off by default; Settings › Local API › Permissions › Diagnostics). Routes under
`/api/v1/diag/`, in `app/.../api/DiagApi.kt`:

- **Reading** the fault memory (`GET /diag/faults`, `GET /diag/vehicle`) answers directly. A read
  from the car takes about 1 s per ECU; the live reading goes on between the jobs. The vehicle
  must have been scanned once (Faults tab). Fault texts are the ECU file's (German).
- **Clearing faults** (`POST /diag/faults/clear`) and **running a job** (`POST /diag/jobs`) are
  confirmed on the phone **for every single call**, nothing is remembered:
  1. if the car is moving (above 3 km/h, from a speed measurement the app reads or the phone
     GPS), the call is refused at once: `409 vehicle_moving`; unknown speed is not a refusal;
  2. the phone shows a dialog over any screen and a high-priority notification with **Allow** /
     **Refuse** ("A client of the local API wants to clear the fault memory of DME (<sgbd>)");
  3. the HTTP call **blocks** until the answer, at most 60 s: `200` with the result once allowed,
     `403 refused_on_phone`, `408 confirmation_timeout` when nobody answered (refused),
     `503 confirmation_unavailable` when the phone cannot ask.
  Use a client timeout above 60 s plus the job time (e.g. `curl -m 180`).
- Jobs run like the in-app Tool32 (any job of an imported SGBD, `.grp` resolved with the ECU),
  with the same safety guard at the interface: flashing, coding writes, security access and the
  programming session are blocked whatever the job. `FS_LOESCHEN*` are refused on `/diag/jobs`:
  use `/diag/faults/clear`.
- ECUs are named by address (`0x12`), SGBD (`<sgbd>`) or name (`DME`), as listed by
  `GET /diag/vehicle`.

```sh
curl -s -H "Authorization: Bearer $T" "$B/diag/faults?ecu=0x12"
curl -s -m 180 -X POST -H "Authorization: Bearer $T" -d '{"ecu":"0x12"}' "$B/diag/faults/clear"
curl -s -m 180 -X POST -H "Authorization: Bearer $T" -d '{"sgbd":"<sgbd>","job":"STATUS_OEL"}' "$B/diag/jobs"
```

## Implementation notes

- The HTTP server is a small dependency-free HTTP/1.1 implementation on `java.net` sockets
  (`app/.../api/http/HttpServer.kt`), so the same code runs on Android and in the JVM tests.
  HTTPS uses the platform TLS stack (Conscrypt on Android, JSSE in the tests); the certificates
  are written by a few lines of DER code (`app/.../api/tls/`), without any extra library.
- `openapi.json` is generated from the same route table that dispatches requests, so the spec
  and the behaviour cannot drift apart.
- Layouts belong to profiles (`files/profiles.json`, `ProfileStore`); the dashboard routes go
  through `DashboardStore`, i.e. the active profile. The phone and Android Auto follow every
  change live; a change made while the phone editor is open is offered to the user, not forced.
- Before profiles the layout was `files/dashboard.json`: on the first start of a version with
  profiles it becomes the first profile (the old file is left in place, unused).

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

**Android 17 and later: local network permission.** Android 17 protects the local network: an
app may only talk to, or be reached by, the other devices of the Wi-Fi network once the user has
granted the **local network** permission (`android.permission.ACCESS_LOCAL_NETWORK`, shown under
*Nearby devices*). BimmerData asks for it when you enable the API, and again when you open the
Local API page while it is missing. Without it the server still runs, but connections from other
devices **time out** (only `127.0.0.1` / `adb forward` work): the page then shows a red banner
with **Allow** and **App settings**. `GET /api/v1/settings` reports it in
`api.localNetworkAccess` (`granted`, `denied`, or `notRequired` before Android 17).

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
   `GET /api/v1/cars` – the user's cars and the selected one; `GET /api/v1/profiles` – the
   selected car's profiles (main ECUs, pages) and the active one. A fresh install has neither, and a
   profile belongs to a car: add the car first (`POST /api/v1/cars`, see 6; connecting to the car
   adds it too, from its VIN), then `POST /api/v1/profiles` (409 `no_car` without car). The dashboard routes act on the
   active profile.
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

# Cars: add one by hand, show it, its profiles, copy a profile onto it (scope layout for writes)
curl -s -H "$H" "$BD/cars"
curl -s -H "$H" -X POST "$BD/cars" -d '{"label":"Track car","series":"<series>","select":true}'
curl -s -H "$H" "$BD/profiles?car=all"
curl -s -H "$H" -X POST "$BD/profiles/<profileId>/copy" -d '{"car":"<carId>"}'
# Detect the ECUs of a car (scope diag; asynchronous), then follow it
curl -s -H "$H" -X POST "$BD/cars/<carId>/detect"
curl -s -H "$H" "$BD/cars/<carId>/detect"

# Full backup (scope config): download it, check it, put it back (replaces everything)
curl -s -H "$H" "$BD/backup" -o bimmerdata-backup.json
curl -s -H "$H" -X POST --data-binary @bimmerdata-backup.json "$BD/backup/restore?dryRun=true"
curl -s -H "$H" -X POST --data-binary @bimmerdata-backup.json "$BD/backup/restore?confirm=true"

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
| POST | `/api/v1/dashboard/car/from-phone` | layout | Rebuild the Android Auto pages from the phone's cards (layout rule: same cards on both) |
| GET / POST | `/api/v1/dashboard/{target}/pages` | read / layout | List / add pages |
| GET / PATCH / DELETE | `/api/v1/dashboard/{target}/pages/{page}` | read / layout | One page: get, rename / resize / move, delete |
| POST | `/api/v1/dashboard/{target}/pages/{page}/cards` | layout | Add a card (exact cell or first free slot) |
| PATCH / DELETE | `/api/v1/dashboard/{target}/pages/{page}/cards/{card}` | layout | Change (JSON merge patch) / delete a card |
| GET | `/api/v1/dashboard/{target}/pages/{page}/render.png` | read | PNG of a saved page |
| POST | `/api/v1/dashboard/{target}/render` | read | PNG of a draft page (body = page) |
| GET | `/api/v1/card-types` | read | Card types and measurement counts |
| GET / POST | `/api/v1/cars` | read / layout | The user's cars (never the VIN, only `vinKnown`) and the selected one / add a car by hand |
| GET / PUT / DELETE | `/api/v1/cars/{car}` | read / layout | One car with its profiles / change name, series, model, ECU list (`removeCards`) / delete it **with its profiles** and fault read |
| POST | `/api/v1/cars/{car}/select` | layout | Show this car: its last used profile becomes active |
| POST / GET | `/api/v1/cars/{car}/detect` | diag | Detect the car's ECUs (full scan, asynchronous, read-only jobs) / its progress and result |
| GET / POST | `/api/v1/profiles[?car=<id>\|all]` | read / layout | The selected car's profiles (or car `car`'s, or all) and the active one / create a profile on the selected car or on `car` (optionally copying another's pages) |
| GET / PUT / DELETE | `/api/v1/profiles/{profile}` | read / layout | One profile with its layout / change name, series / model, ECUs (`removeCards`) or layout / delete |
| POST | `/api/v1/profiles/{profile}/activate` | layout | Show this profile (and select its car) on the phone and in Android Auto |
| POST | `/api/v1/profiles/{profile}/copy` | layout | Copy the profile onto another car (`{"car":"<id>"}`) |
| GET | `/api/v1/profiles/{profile}/export` | read | The shareable profile file (never the VIN nor the user's car) |
| POST | `/api/v1/connect` | config | Connect (`usb` / `simulation`) |
| POST | `/api/v1/disconnect` | config | Disconnect |
| GET | `/api/v1/recording` | read | Recording state |
| POST | `/api/v1/recording/start`, `/stop`, `/markers` | config | Control the CSV recording |
| GET | `/api/v1/recordings`, `/api/v1/recordings/{name}` | read | List / download CSV files |
| GET / PATCH | `/api/v1/settings` | read / config | App settings: auto-connect on the cable, Bluetooth connect-on-start (API settings are read-only) |
| GET / PATCH | `/api/v1/settings/gps` | read / config | GPS position: switch, interval, permission and state |
| GET / PATCH | `/api/v1/settings/recording` | read / config | CSV recording: row rate, file prefix, back to the default folder |
| GET / PATCH | `/api/v1/settings/translations` | read / config | Translation of the ECU texts: language, source URL, token (write-only), ECU selection |
| POST | `/api/v1/settings/translations/update` | config | Download the translation files again (background) |
| GET | `/api/v1/backup` | config | Full backup file: cars **with VIN**, ECU lists, last detection and fault read, profiles with pages and graphs, settings, cached translations; no token or password |
| POST | `/api/v1/backup/restore?confirm=true[&dryRun=true]` | config | Put a backup back: replaces everything, all or nothing (Settings › Local API stays as it is) |
| GET | `/api/v1/permissions` | read | Permissions and switches the app needs: granted / missing, what for, what to tap |
| GET | `/api/v1/ecu-files[?files=true]` | read | Imported ECU files, free space, device import folder and import progress |
| PUT / DELETE | `/api/v1/ecu-files/{name}` | config | Upload one `.prg` / `.grp` file (streamed, ≤ 64 MB) / delete it |
| DELETE | `/api/v1/ecu-files` | config | Delete every ECU file |
| POST / DELETE | `/api/v1/ecu-files/import-from-device` | config | Import the ECU files put in the app's folder on the phone (background) / stop it |
| GET | `/api/v1/bluetooth` | read | Bluetooth permission, on / off, chosen adapter, connect-on-start, adapter state |
| GET | `/api/v1/bluetooth/devices` | config | Paired devices (likely OBD adapters first) |
| PUT / DELETE | `/api/v1/bluetooth/adapter` | config | Choose a paired device as the adapter (and connect-on-start) / forget it |
| POST | `/api/v1/bluetooth/adapter/read` | config | Read type, firmware, voltage and CAN mode (connected over Bluetooth) |
| PUT | `/api/v1/bluetooth/adapter/can-mode` | config | **Writes to the adapter.** Change its CAN mode, after a confirmation on the phone |
| GET / PATCH | `/api/v1/export/telegraf` | config | Telegraf export settings and queue (token / password never returned) |
| PUT | `/api/v1/export/telegraf/auth` | config | Authentication: none / token / basic with its secret (write-only) |
| POST | `/api/v1/export/telegraf/test`, `/send` | config | Test the listener / upload the waiting points now |
| DELETE | `/api/v1/export/telegraf/queue` | config | Delete the points waiting on the phone |
| GET / DELETE | `/api/v1/export/telegraf/rejected` | config | Download (gzip line protocol) / delete the batches Telegraf refused |
| POST | `/api/v1/export/telegraf/rejected/retry` | config | Queue the refused batches again |
| POST | `/api/v1/profiles/import[?activate=false&car=<id>]` | layout | Import a profile file (pages and graphs) as a new profile of the selected car (or of `car`) |
| GET / POST | `/api/v1/profiles/{profile}/graphs` | read / layout | Graphs of the Graph tab / add one |
| GET / PUT / DELETE | `/api/v1/profiles/{profile}/graphs/{graph}` | read / layout | One graph / replace it / delete it (pages never touched) |
| GET | `/api/v1/diag/vehicle` | diag | The selected car, the ECUs of its last detection and their last fault read (no car traffic, never the VIN) |
| GET | `/api/v1/diag/faults` | diag | Read the fault memory from the car (`?ecu=0x12` for one ECU, `?cached=true` for the last read) |
| POST | `/api/v1/diag/faults/clear` | diag | **Writes.** Clear one ECU (`{"ecu":"0x12"}`) or all (`{}`), after a confirmation on the phone |
| POST | `/api/v1/diag/jobs` | diag | **May write.** Run one EDIABAS job (`{"sgbd","job","args"}`), after a confirmation on the phone |

### Layout rules

- Phone pages: up to 4 columns × 8 rows, 32 cards. Android Auto pages: up to 4 × 2, 8 cards.
- A card covers `colSpan × rowSpan` cells from (`col`, `row`), 0-based; cards may not overlap
  or leave the grid.
- Card types: `Dial`, `BipolarBar` (1–4 measurements), `BigNumber`, `Trend`, `MultiValue`
  (2–3 measurements), `Status`; the others take exactly one measurement.
- A profile holds at most **42 values** (distinct measurement ids over all its phone and Android
  Auto pages; a value on several cards counts once). The whole profile is read at every cycle and
  one ECU's dynamic block holds 42 values. A card, a layout or a profile file that would make more
  is refused (`409 too_many_values`, `422 invalid_layout` with an issue `too_many_values`,
  `422 invalid_profile_file`): split the values into several profiles.
- **Android Auto shows the same cards as the phone** (the order may differ): every card of the
  phone pages is on an Android Auto page, and Android Auto has no other card. After creating or
  changing phone pages, call `POST /api/v1/dashboard/car/from-phone`: it rebuilds the Android Auto
  pages from the phone pages (up to 8 cards per Android Auto page on its 4 × 2 grid, "Engine 1/2", "Engine 2/2"…). For a
  profile file, build `carPages` the same way. The app does not refuse a layout that breaks the
  rule (older or shared profiles), but an agent must not create one.
- Unknown JSON fields are rejected (400), so typos do not silently vanish.
- Measurement ids that are not in the imported ECU folder are accepted but reported as
  `warnings` (the card shows "--" until the SGBD is imported).

### Cars, profiles and ECU lists

- **Car → profiles → pages.** Every profile belongs to one car (`carId`). One car is selected
  (`GET /api/v1/cars` › `selectedId`): the profile list, the active profile (always one of the
  selected car's; none when it has no profile), the Faults tab, the diag routes and the Telegraf
  `car` tag follow it. Selecting a car (`POST /api/v1/cars/{car}/select`, or activating one of its
  profiles) shows its last used profile; a car with profiles always shows one of them.
- **Where cars come from.** At each connection the app reads the short VIN (one read-only job):
  a known car becomes the selected one with its last used profile (only proposed on the phone
  while a CSV recording runs); an unknown VIN goes to the selected car if it was added by hand and
  has no VIN yet (its first connection), else it makes a new car "Car <VIN>". `POST /api/v1/cars`
  adds a car by hand; a detection (`POST /api/v1/cars/{car}/detect`) fills the car with the VIN it
  reads (the car with that VIN, the selected car without VIN, or a new car: never a duplicate).
  The VIN itself never leaves the phone: car JSON only says `vinKnown`. The one exception is the
  user's own full backup (`GET /api/v1/backup`, Settings › Backup), see [Backup](#backup).
- **ECU list of a car** (`ecus` of the car): the ECUs found by its last detection, then those
  added by hand (`ecusByHand`; `PUT /api/v1/cars/{car}` `{"ecus":[…]}` sets them). A profile's
  main ECUs must be in its car's list: `POST` / `PUT /api/v1/profiles…` answer
  `422 ecu_not_in_car` with `details.allowed` otherwise. A car without list yet takes the ECUs of
  the profile given to it; a profile copied (`…/copy`) or imported onto a car brings the ECUs the
  car lacks into its list (added by hand). A new detection replaces the detected ECUs and keeps
  those added by hand; an ECU it no longer finds that profiles still use stays in the list
  (`keptInUse` of the detection; the phone asks the user).
- **Nothing is removed silently.** Taking an ECU out of a car's list (`PUT /api/v1/cars/{car}`)
  while the car's profiles use it (main ECUs, cards, graph values), or out of a profile's `ecus`
  (`PUT /api/v1/profiles/{profile}`) while it still has cards or graph values there, answers
  `409 ecu_in_use` with `details` listing each ECU and the profiles concerned (`cards`,
  `graphSeries`). Repeat the request with `"removeCards": true` to remove it from those profiles
  too: their cards using it are deleted from the phone and the Android Auto pages alike, and its
  values from their graphs.
- Deleting a car (`DELETE /api/v1/cars/{car}`) deletes its profiles (pages and graphs) and its
  last fault read; export the profiles first to keep them.
- Profile files (`…/export`, `…/import`) keep `series` and `model` as metadata, never the VIN
  nor the car.

### Errors

Every error has the same shape and a meaningful status:

```json
{"error":{"code":"card_overlap","message":"card 'k3j4…' (col 0, row 1, 1×1) overlaps card 'a1b2…' (col 0, row 0, 1×2); free cells: (1,0) (1,1) (0,2) (1,2)"}}
```

`400` bad input · `401` missing / wrong token · `403` scope not enabled (or API settings,
which are phone-only) · `404` unknown id · `405` wrong method · `409` conflict (overlap, page
full, last page, not connected) · `413` body over 1 MB (64 MB for an ECU file upload) · `422` invalid layout (`details` lists
each problem with its JSON path) · `429` rate limited (`Retry-After`) · `501` not available on
this phone · `507 insufficient_storage` (the app always keeps 100 MB free). Diagnostics and the
adapter CAN mode add `403 refused_on_phone`, `408 confirmation_timeout`, `409 vehicle_moving` and
`503 confirmation_unavailable` / `not_connected`. Cars and profiles add `404 car_not_found`,
`409 ecu_in_use`, `409 vehicle_busy` (a detection or fault read runs) and `422 ecu_not_in_car` /
`invalid_car`.

## Set up the app entirely through the API

Everything the Settings screens, the Profiles tab, My cars and the Graph tab do can be done
through the API, so an agent can set BimmerData up while the user does not touch the phone,
except for the few steps Android reserves to the user (listed below). Grant the `read`, `layout`
and `config` scopes for this (and `diag` to detect a car's ECUs; Settings › Local API: that first
step is on the phone, see [Enabling it](#enabling-it)). Order of the calls:

0. **Restore a backup if you have one** (`GET /api/v1/backup` made before, or Settings › Backup ›
   Export on the phone): `POST /api/v1/backup/restore?confirm=true` with the file as body puts back
   the cars (with their VIN, ECU lists, last detection and fault read), every profile with its pages
   and graphs, the active profile, the connection, Bluetooth adapter, GPS, recording, Telegraf and
   translation settings and the cached translation files in one call. Then only steps 1 and 2 (and
   the Telegraf token of step 4) remain: the answer's `todo` lists them. See [Backup](#backup).
1. **What the user still has to allow**: `GET /api/v1/permissions`. Each entry has `status`
   (`granted`, `missing`, `notRequired`, `on`, `off`, `askedOnConnect`), `neededFor` and
   `howToGrant` (what to tap); `blocking` lists those a feature that is switched on cannot work
   without. Show the `howToGrant` text of the missing entries to the user: an app cannot grant
   itself a permission.
2. **ECU files** (EDIABAS `.prg` / `.grp`, the same storage as the in-app import, lower-case names):
   - the whole `Ecu` folder: put it in the app's own folder on the phone, then import it. The
     folder is `importDir` of `GET /api/v1/ecu-files`,
     `/sdcard/Android/data/io.github.jonathangtd.bimmerdata/files/ecu-import/` on most phones
     (created at the first start of the app):
     ```sh
     adb push <path-to>/Ecu/. /sdcard/Android/data/io.github.jonathangtd.bimmerdata/files/ecu-import/
     curl -s -H "$H" -X POST "$BD/ecu-files/import-from-device" -d '{"mode":"replace"}'
     curl -s -H "$H" "$BD/ecu-files"      # deviceImport.phase: running → done, filesDone / filesTotal
     ```
     The import runs in the background (≈ 1600 files / 800 MB take a minute or two). `replace`
     makes this folder the new ECU set, swapped in only once complete (the app disconnects from
     the car just before); `merge` adds and replaces files and keeps the others. It is safe to
     call twice: while it runs, the call returns the progress; files already imported with the
     same size and time are not copied again, so calling it again after an interruption goes on
     where it stopped. Sub-folders are searched (pushing `Ecu` itself instead of its content
     works too), only regular files with a plain `.prg` / `.grp` name are taken, symbolic links
     and other files are listed in `skipped`. Afterwards the folder can be emptied, either with
     `"deleteSource": true` or by hand (`adb shell rm -r /sdcard/Android/data/io.github.jonathangtd.bimmerdata/files/ecu-import/*`);
     it is also removed with the app. Without `adb`, copying with a USB file transfer from a
     computer works on phones that still show `Android/data` over USB (many Android 11+ phones
     hide it: use `adb push` then).

     Two pitfalls, both reported in the error message:
     - **start the app before copying.** If the folder is created by `adb` (with `mkdir`, or by a
       push before the app's first start), it belongs to the shell user and the app cannot list
       it: the import answers `422 no_ecu_files` although the files are there. Delete it
       (`adb shell rm -r <importDir>`), start the app once, then copy again.
     - **copy with `adb push`, not `adb shell cp`.** Files copied by `cp` inside the phone (for
       example from `Download`) are only readable by their owner (mode 0660): the import fails
       with `EACCES`. Run `adb shell chmod -R a+rw <importDir>` and call the import again.
   - a few files: `PUT /api/v1/ecu-files/<sgbd>.prg` with the raw file as body, one file per call
     (`curl -T <sgbd>.prg -H "$H" "$BD/ecu-files/<sgbd>.prg"`). The body is streamed to disk and
     moved in place once complete; uploading a whole folder this way is not intended.
   The catalog (`GET /api/v1/ecus`) sees the new files at once.
3. **Bluetooth adapter** (if the car is read through a Deep OBD adapter; the USB cable needs
   nothing here): pair it on the phone, then `GET /api/v1/bluetooth/devices`,
   `PUT /api/v1/bluetooth/adapter` `{"address":"<mac>","connectOnStart":true}`. Once connected,
   `POST /api/v1/bluetooth/adapter/read`; `PUT /api/v1/bluetooth/adapter/can-mode` writes to the
   adapter and waits for the user's confirmation on the phone.
4. **Telegraf export**: `PATCH /api/v1/export/telegraf` `{"enabled":true,"url":"http://<host>:8186/api/v2/write","car":"My car","mode":"afterTrip"}`,
   `PUT /api/v1/export/telegraf/auth` `{"auth":"token","token":"<token>"}`, then
   `POST /api/v1/export/telegraf/test`.
5. **Other settings**: `PATCH /api/v1/settings` (auto-connect on the cable),
   `/api/v1/settings/gps`, `/api/v1/settings/recording`, `/api/v1/settings/translations`
   (then `POST /api/v1/settings/translations/update`).
6. **Cars**: `GET /api/v1/cars`. The app adds the car itself at the first connection (from its
   VIN); to prepare profiles before that, add it by hand: `POST /api/v1/cars`
   `{"label":"My car","series":"<series>","model":"<model>","select":true}`, and give it its ECU
   list by hand (`PUT /api/v1/cars/{car}` `{"ecus":["<sgbd>",…]}`) or, connected to the car,
   detect it (`POST /api/v1/cars/{car}/detect`, scope `diag`, then poll
   `GET /api/v1/cars/{car}/detect` until `running` is false). See
   [Cars, profiles and ECU lists](#cars-profiles-and-ecu-lists).
7. **Profiles and graphs** (on the selected car, or `car` / `?car=`): `POST /api/v1/profiles/import`
   with a profile file (the JSON of `GET /api/v1/profiles/{profile}/export`, pages and graphs
   included), or `POST /api/v1/profiles` and the dashboard routes; graphs with
   `/api/v1/profiles/{profile}/graphs`; `POST /api/v1/profiles/{profile}/copy` puts a copy on
   another car. Android Auto gets the same cards as the phone:
   `POST /api/v1/dashboard/car/from-phone` once the phone pages are done (see
   [Layout rules](#layout-rules)).
8. **Connect**: `POST /api/v1/connect` `{"mode":"usb"}` / `{"mode":"bluetooth"}` /
   `{"mode":"simulation"}`, then `GET /api/v1/status`.

What still needs a tap on the phone, and why:

| Step | Why |
|---|---|
| Enable the API, its scopes, port, HTTPS, (re)generate the token | Read-only through the API: changing them remotely would let a client widen its own access |
| Runtime permissions: Nearby devices (Bluetooth), notifications, location, local network (Android 17) | Android only lets the user grant them; `GET /api/v1/permissions` says which and where |
| USB access to the cable | Android asks once per cable when the app connects (tick "Always open") |
| Pairing a Bluetooth adapter (PIN) | Done in Android's Bluetooth settings; the app does not scan |
| Bluetooth / location switched on | System switches |
| Choosing a recording folder or a translation folder | Android's folder picker (SAF) can only be used by the user; the API can go back to the default recording folder |
| Each fault clear, EDIABAS job and adapter CAN mode change | Confirmed on the phone for every call, by design |
| Exporting a profile or the refused Telegraf batches to a file of the phone | The API returns them as downloads instead |

## Backup

One JSON file with everything needed to get the app back as it was, after a reinstall or on a new
phone: Settings › Backup on the phone (Export / Restore, Android's file dialogs) or
`GET /api/v1/backup` / `POST /api/v1/backup/restore` (scope `config`). Same file both ways.

- **In it**: `format` `"bimmerdata-backup"`, `version` 1, `appVersion` / `appVersionCode`,
  `createdAt`; `garage` (the cars **with their VIN**, ECU lists detected and added by hand, last
  detection with addresses and identification, last fault read, the selected car); `profiles` (every
  profile with its car, ECUs, phone and Android Auto pages and graphs, the active one); `settings`
  (auto-connect, last connection mode, Bluetooth adapter address and connect-on-start, GPS,
  recording row rate and prefix, Telegraf URL / mode / car / user name, Local API enable / port /
  scopes / HTTPS); `translations` (language, source URL, ECU selection, and the cached files, left
  out beyond 4 MB: `filesOmitted`).
- **Never in it**: the API token, the Telegraf token or password, the translation token, any key.
  **Not in it**: the ECU files (import them again), the folders picked with Android's folder picker
  (recording, translations: their permission does not survive a reinstall), the CSV recordings and
  the Telegraf queue.
- **Private**: it is the only place the VIN leaves the phone (profile files never hold it): keep it
  for yourself.
- **Restore = replace everything, all or nothing.** The whole file is checked first
  (`not_a_backup` 400, `invalid_backup` / `newer_version` 422; a profile file is recognised and
  refused with a hint). Then the app disconnects from the car (`busy` 409 while a detection or fault
  read runs) and replaces the cars, the profiles, the settings and the translation cache one after
  the other; if one of them cannot be written, the state of just before is put back in all of them
  (`restore_failed` 500). It needs `"confirm": true` at the top level of the file or
  `?confirm=true` (else `confirm_required` 400); `?dryRun=true` only checks the file and returns the
  summary. A file without translation files keeps the phone's cache.
- **Through the API, Settings › Local API stays as it is** (`apiSettingsRestored`: false): a client
  cannot widen its own access by restoring a file. A restore on the phone puts it back too. The
  phone keeps its own API token and Telegraf secrets in any case.
- The answer (`RestoreSummary`) counts what came back (`cars`, `carsWithVin`, `carsScanned`,
  `profiles`, `graphs`, `activeProfile`, `selectedCar`, `translationFiles`) and lists what is left
  to do by hand in `todo` (text) and `todoCodes` (`ecu_files`, `telegraf_secret`,
  `translation_folder`, `recording_folder`).
- A Play update keeps the app's data: a backup is for a reinstall, a new phone or a switch between
  builds signed with different keys.

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
  `config` (connect / disconnect, recordings, settings, ECU files, Bluetooth adapter, Telegraf
  export and its secrets), `diag` (fault memory;
  clearing faults and running jobs, each confirmed on the phone, see [Diagnostics](#diagnostics)).
  The API settings themselves (enable, port, token, scopes, HTTPS) can only be changed on the
  phone: through the API they are read-only (`GET /api/v1/settings`).
- **Secrets are write-only**: the Telegraf token / password (`PUT /api/v1/export/telegraf/auth`,
  stored encrypted by the Android Keystore) and the translation source token are never
  returned; the views only say whether one is stored (`secretSet`, `tokenSet`).
- **ECU files**: only plain `.prg` / `.grp` names are accepted (no folder, no `..`, letters,
  digits, `_`, `-`, `.`), stored in lower case; the device import reads only regular files under
  its own folder (symbolic links are skipped, paths are checked to stay inside the folder);
  uploads and imports stop before the free space goes under 100 MB.
- **Local network only**: the server binds the phone's private IPv4 addresses on Wi-Fi,
  Ethernet, USB tethering and its own hotspot (plus 127.0.0.1), never mobile data or VPN
  interfaces, and drops connections from non-private addresses.
- **Limits**: about 20 requests/s per client (bursts of 60), lock-out for 5 minutes after 10
  wrong tokens, 16 connections, 4 event streams, 1 MB request bodies (64 MB for an ECU file
  upload, streamed to disk), 16 KB headers.
- **Encryption**: plain HTTP by default. On a shared or public Wi-Fi, anyone sniffing can then
  read the token: switch [HTTPS](#https) on, or use the API on networks you trust (home Wi-Fi,
  the phone's own hotspot), over a VPN or over `adb forward`.
- **Writes to ECUs only with the user's consent, per call.** Without the `diag` scope the API
  reads live values and changes app data only. With it, clearing faults and running jobs wait
  for the user's confirmation on the phone, every single time, and are refused while the car is
  moving; see [Diagnostics](#diagnostics). The one write to the Bluetooth adapter (its CAN mode,
  scope `config`) goes through the same confirmation.

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
  must have been detected once (Profiles tab › Detect, or `POST /api/v1/cars/{car}/detect`). Fault texts are the ECU file's (German).
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
- Request bodies are read into memory (1 MB at most), except on routes declared as uploads
  (`PUT /api/v1/ecu-files/{name}`): there the server hands the socket's body to the handler as a
  stream, answers `Expect: 100-continue` only when the handler starts reading (a request refused
  for its token, scope or name is never sent), and closes the connection if the body was not
  read to the end. The setup routes live in `app/.../api/SetupRoutes.kt` and
  `ProfileGraphRoutes.kt`; the ECU file storage in `app/.../files/EcuFileStore.kt`.
- Cars are the garage of `files/vehicles.json` (`VehicleRepository`, `Garage.currentId` = the
  selected car), each profile points to one by `carId`; `cars/CarBook.kt` changes both together
  under the profile store's lock (pure operations in `cars/Cars.kt`, ECU use in `cars/EcuUsage.kt`,
  routes in `CarRoutes.kt`). Profiles written before cars existed are given cars on the first read
  (grouped by their "series model", the old link to a Faults-tab car kept).
- Layouts belong to profiles (`files/profiles.json`, `ProfileStore`); the dashboard routes go
  through `DashboardStore`, i.e. the active profile. The phone and Android Auto follow every
  change live; a change made while the phone editor is open is offered to the user, not forced.
- Before profiles the layout was `files/dashboard.json`: on the first start of a version with
  profiles it becomes the first profile (the old file is left in place, unused).

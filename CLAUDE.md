# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

amission is a native Android app (Kotlin, Jetpack Compose, MapLibre) for planning INAV waypoint missions and sending them to a flight controller over MSP. Target firmware is INAV 9.1. GPL-3.0. It is unofficial and not affiliated with the INAV project.

## Commands

Gradle 9 needs JDK 17 or newer to run. If `JAVA_HOME` points at an older JDK, point it at a newer one (Android Studio's bundled JDK works). `local.properties` (git-ignored) holds `sdk.dir` for the Android SDK.

```sh
./gradlew :msp:test                                                    # protocol and logic unit tests (fast, no device)
./gradlew :msp:test --tests "org.amission.msp.MissionClientTest"       # one test class
./gradlew :app:assembleDebug                                           # debug APK
./gradlew :app:lintDebug                                               # Android lint (0 errors at last check)
./gradlew :app:assembleRelease                                         # signed release APK (needs keystore.properties; unsigned without it)
./gradlew :app:assembleRelease -PallAbis                              # same, but also for x86 emulators (about 25 MB larger)
```

## Architecture

- `:msp` is a pure Kotlin/JVM module with no Android dependencies, so the protocol and all the logic that matters for safety is unit-testable without hardware. Bottom to top:
  - `ByteChannel` (raw read/write) is the only platform-specific seam. USB, Bluetooth and TCP each implement it.
  - `SerialMspLink` turns a `ByteChannel` into request/reply `MspLink` (MSP v1 framing, retries, drops replies for other commands).
  - `MissionClient` does mission operations (info, download, upload with read-back verification, EEPROM save/load); `TelemetryClient` reads the FC's GPS.
  - Also here: `MissionXml` (Configurator-compatible file format), `FlightProfileBuilder` (height above ground), `TileMath` (offline download sizing), `Geo`.
- `:app` holds Android code: `UsbSerialChannel` (usb-serial-for-android), `MissionStore` (auto-saves the current mission and home as JSON), `MissionMap` (MapLibre; the route and markers are GeoJSON sources and generated bitmaps), `OfflineMaps`, `Locator`, `MissionFiles`, `ProfileView`, `ElevationService`, and the Compose UI in `MainActivity.kt` / `WaypointEditor.kt`.
- The mission is a local document, edited with no FC attached. A USB connection is opened only for the duration of an upload, download or GPS read. New transports (Bluetooth Classic or BLE) should implement `ByteChannel` and reuse everything above it.
- MSP calls are blocking; call them off the main thread (`Dispatchers.IO`).
- Connections: `FcConnections` (`FcConnection.kt`) picks USB or the saved Bluetooth device and returns an `FcLink` wrapping a `ByteChannel`. `Bluetooth.kt` has the scanner and two channels: `BleUartChannel` (GATT serial, found by `BleUart.select` in `:msp`, which knows Nordic UART, HM-10, SpeedyBee and a generic fallback) and `BluetoothClassicChannel` (RFCOMM/SPP). The GEPRC TAKER H743 BT module advertises as "GEPRC" over BLE. A BLE connect that times out (status 147) is retried once after 1.5 s. Android 12+ needs BLUETOOTH_CONNECT and BLUETOOTH_SCAN (declared `neverForLocation`); older versions need location for scanning.
- `MissionCheck` (in `:msp`) walks the list the way the FC does (RTH and Land end the mission, Jumps loop or fall through) and reports points that are never flown or Jumps to missing points. The FC checks none of this. It feeds the List tab, the upload warning and nothing blocks an upload.

## Protocol notes

- Waypoint wire format is 21 bytes little-endian: number u8, action u8, lat i32, lon i32 (degrees × 1e7), alt i32 (cm), p1/p2/p3 i16, flag u8 (0xA5 marks the last waypoint). Commands: WP_GETINFO 20, WP 118, SET_WP 209, WP_MISSION_LOAD 18, WP_MISSION_SAVE 19, RAW_GPS 106.
- These were checked against the INAV 9.1.0 source (`fc_msp.c`, `navigation.h`, `navigation.c`, `logic_condition.c`). On a real FC over USB, reading info, downloading a mission (including loading the saved EEPROM mission) and reading GPS are confirmed. Uploading to a real FC has not been tried yet. Uploads are always read back and compared (`uploadAndVerify`). The max waypoint count comes from the FC; the source default is 15 unless the target overrides it.
- INAV keeps a saved mission in EEPROM and only copies it into working memory at boot (`nav_wp_load_on_boot`) or on `WP_MISSION_LOAD`, so a download falls back to loading it when memory is empty.
- p3 is a bitfield: bit 0 altitude is sea-level (absolute), bits 1-4 = user actions 1-4. User actions do nothing by themselves; the FC exposes them only to logic conditions ("Waypoint user action N" reads the waypoint just left, the `_NEXT_WP` operands the one being flown to) and, in 9.x, to the optional VTOL mission transition. For JUMP, p3 is a runtime counter (reset from p2 at mission start), so always send p3 = 0.
- A Jump's p1 is a 1-based list number on the wire: the FC subtracts 1 on receive and adds 1 on read-back.
- Only MSP v1 is implemented; the mission commands are v1.

## Mission files

`MissionXml` reads/writes the INAV Configurator's `.mission` XML: `<mission>` with `<version>`, `<mwp>` and one `<missionitem>` per FC list entry; lat/lon in degrees, `alt` in metres, `flag="165"` on the last item, and home in `<mwp home-x/home-y>`. Checked against the Configurator source (`tabs/mission_control.js`, `js/mwnp.js`). The Configurator groups RTH/Jump/Set Head under the previous map point; this app shows the flat FC list. Files are untrusted: DOCTYPE/ENTITY are refused, size and item counts are capped, malformed numbers are errors (not defaults), and only the first mission of a multi-mission file is imported. `MissionFiles` does Save/Open through Android's document picker (works with Drive, pCloud and local storage) and Share via a FileProvider.

## Flight profile and home

INAV waypoint altitude is relative to the take-off origin unless p3 bit 0 is set (`waypointMissionAltConvMode`). `FlightProfileBuilder` samples the route, takes terrain heights from outside, and returns height above ground plus any stretch lower than the limit (default 40 m, a setting). Terrain comes from `ElevationService` (Open-Meteo, ~90 m resolution, bare ground only, cached on the device). The planned home (More menu; saved in the mission file) fixes the ground height relative altitudes count from; with none set, home is assumed at point 1 and the tab says so. A Set POI is not flown to (`WaypointAction.isFlownTo`). Uploading a mission with a low stretch asks for confirmation rather than blocking. New waypoints start at the "New point alt" setting (default 80 m).

## Maps and network etiquette

- Street is the keyless OpenFreeMap style. Satellite is a raster style built in `MapSettings.kt` from the user's own MapTiler/Mapbox key or a custom `{z}/{x}/{y}` template; the key lives only in app-private preferences and is excluded from Android backup (`res/xml/backup_rules.xml`, `data_extraction_rules.xml`). Never commit a key.
- `MapSdk` (in `MapHttp.kt`) sets one User-Agent that identifies the app and at most 4 parallel requests per host. OpenFreeMap's terms ask for this and cap an offline download at 3,000 tiles or 50 MB; `OfflineMaps` enforces that for the Street layer. Re-read their terms before changing it.
- Offline areas use MapLibre's offline region database for whatever style is active. Measured on the Street style: the first area also stores about 17-18 MB of font glyphs, shared by later areas. Waypoint markers are generated bitmaps, not map text, so they render offline without glyphs.
- The satellite offline path (style written to a `file://` URL) has not been tried against a real provider.
- Terrain heights come from Open-Meteo's free tier: non-commercial use, CC BY 4.0 attribution (shown in the About dialog and Profile tab), under 10,000 calls a day.

## Conventions

- Tests use neutral coordinates, never a real flying site or a real person's location.
- No keys, tokens or personal data in code, tests, commit messages or docs.
- Commit messages: imperative, sentence case, no `feat:`-style prefixes, never "WIP"; say why when it is not obvious.
- An RTH entry's own altitude is not read by INAV (it just starts the normal return-to-home), so the editor offers none for RTH.
- Not built yet: dragging waypoints on the map, a mission library, release signing, Bluetooth download and upload tests on hardware.

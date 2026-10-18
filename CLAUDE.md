# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

amission is a native Android app (Kotlin, Jetpack Compose) for creating INAV waypoint missions and uploading them to a flight controller over MSP. Target firmware is INAV 9.1; the test FC is a GEPRC TAKER H743 BT. GPL-3.0, intended for open-source release.

## Commands

The machine's `JAVA_HOME` points to JDK 8, which Gradle 9 cannot run on. Use Android Studio's JDK for every Gradle call:

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :msp:test                              # protocol unit tests (fast, no device)
.\gradlew.bat :msp:test --tests "org.amission.msp.MissionClientTest"   # one test class
.\gradlew.bat :app:assembleDebug                     # debug APK
```

`local.properties` (git-ignored) holds `sdk.dir`; the SDK is at `%LOCALAPPDATA%\Android\Sdk`.

## Architecture

- `:msp` is a pure Kotlin/JVM module with no Android dependencies, so the whole protocol is unit-testable without hardware. Layers, bottom to top:
  - `ByteChannel` (raw read/write) is the only platform-specific seam. USB, Bluetooth and TCP each implement it.
  - `SerialMspLink` turns a `ByteChannel` into request/reply `MspLink` (MSP v1 framing, retries, drops replies for other commands).
  - `MissionClient` does mission operations (info, download, upload with read-back verification, EEPROM save/load) on an `MspLink`.
- `:app` holds Android code only: `UsbSerialChannel` (usb-serial-for-android), `MissionStore` (auto-saves the current mission as JSON), `MissionMap` (MapLibre; GeoJSON sources/layers for the route, tap empty map = add waypoint, tap marker = select) and the Compose UI in `MainActivity.kt`. The mission is a local document edited with no FC attached; a USB connection is opened only for the duration of an upload/download/save. New transports (Bluetooth Classic or BLE) should implement `ByteChannel` and reuse everything above it.
- MSP calls are blocking; call them off the main thread (`Dispatchers.IO`).

## Protocol notes

- Waypoint wire format is 21 bytes little-endian: number u8, action u8, lat i32, lon i32 (degrees × 1e7), alt i32 (cm), p1/p2/p3 i16, flag u8 (0xA5 marks the last waypoint). Commands: WP_GETINFO 20, WP 118, SET_WP 209, WP_MISSION_LOAD 18, WP_MISSION_SAVE 19.
- Wire format, action codes, `0xA5` last flag, and the 1-byte payload of WP_MISSION_LOAD/SAVE were checked against the INAV 9.1.0 source (`fc_msp.c`, `navigation.h`) but not yet against a real FC. Uploads must always be read back and compared (`uploadAndVerify`). The max waypoint count comes from the FC (`WP_GETINFO`); the source default is 15 unless the target overrides it.
- p3 is a bitfield: bit 0 altitude is sea-level/absolute, bits 1-4 = user actions 1-4. User actions do nothing by themselves; the FC exposes them only to logic conditions ("Waypoint user action N" reads the waypoint just left, the `_NEXT_WP` operands the one being flown to) and, in 9.x, to the optional VTOL mission transition. For JUMP, p3 is a runtime counter (reset from p2 at mission start), so always send p3 = 0.
- Only MSP v1 is implemented; the mission commands are v1.

## Mission files

`MissionXml` (in `:msp`) reads/writes the INAV Configurator's `.mission` XML: `<mission>` with `<version>`, `<mwp>` and one `<missionitem>` per FC list entry; lat/lon in degrees, `alt` in metres, `flag="165"` on the last item, and a Jump's `parameter1` is the 1-based list number (same as on the wire; the FC subtracts 1 on receive and adds 1 on read-back). Checked against the Configurator source (`tabs/mission_control.js`, `js/mwnp.js`). The Configurator groups RTH/Jump/Set Head under the previous map point; this app shows the flat FC list. Files are untrusted: DOCTYPE/ENTITY are refused, size and item counts are capped, malformed numbers are errors, and only the first mission of a multi-mission file is imported. `MissionFiles` (app) does Save/Open through Android's document picker (works with Drive, pCloud and local storage) and Share via a FileProvider.

## Flight profile and home

INAV waypoint altitude is relative to the take-off origin unless p3 bit 0 (sea-level ref) is set (`waypointMissionAltConvMode` in `navigation.c`). `FlightProfileBuilder` (in `:msp`, unit tested) samples the route, takes terrain heights from outside, and returns height above ground at each sample plus any stretches lower than the limit (default 40 m, a setting). Terrain comes from `ElevationService` (Open-Meteo, ~90 m resolution, bare ground only, cached on the device so a checked route still shows offline). The planned home (More menu; saved in the mission file's `home-x/home-y`) fixes the ground height relative altitudes count from; with none set, home is assumed at point 1 and the Profile tab says so. A Set POI is not flown to (`WaypointAction.isFlownTo`), so it is left out of the route line and the profile. Uploading a mission with a low stretch asks for confirmation rather than blocking. New waypoints start at the "New point alt" setting (default 80 m).

## Planned, not yet built

Map layers: Street is the keyless OpenFreeMap style; Satellite is a raster style built in `MapSettings.kt` from the user's own MapTiler/Mapbox key or a custom `{z}/{x}/{y}` template (stored in app-private SharedPreferences, never in the repo). Both need internet for now. Switching styles drops the mission layers, so `MissionMap` re-adds them on every style change. Offline areas (`OfflineMaps.kt`) use MapLibre's offline region database for whatever style is active; the dialog sizes the download with `TileMath` before starting. Measured on the Street style: the first area also stores ~17-18 MB of font glyphs, shared by later areas. Waypoint markers are generated bitmaps (`MarkerBitmaps` in `MissionMap.kt`), not map text, so they render offline without glyphs. The satellite offline path (style written to a `file://` URL) has not been tried against a real provider. Still to do: drag to move waypoints, mission library, offline region download, bring-your-own tile API key (no keys in the repo), MBTiles/PMTiles import, Bluetooth transport (FC Bluetooth is on UART3; Classic vs BLE still unconfirmed), mission XML import/export.

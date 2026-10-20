# Indoor AR Wayfinder

Native Android (Kotlin, ARCore, Jetpack Compose, Room) indoor AR wayfinding MVP, structured so a
native iOS (Swift, ARKit, RealityKit) implementation can mirror the same contracts.

## Status

| Milestone | Scope | State |
|---|---|---|
| 1 | Camera AR view, reference-marker localization, AR point selection, save/rename/delete destinations, relocalization gate (`DestinationLocator`), saved points shown in AR after rescan | **Implemented** — automated tests pass; physical marker workflow needs on-site validation ([docs/DEVICE_TESTS.md](docs/DEVICE_TESTS.md)) |
| 2 | Venue route graph, Dijkstra, turn instructions, navigation state machine, arrival logic (pure Kotlin + tests) | Not started |
| 3 | Navigation screen: AR arrow, beacon, next-turn, distance label, fallback guidance, arrival dialog | Not started |

Tapping a destination on the home screen currently shows "not implemented yet" instead of
pretending to navigate.

## Prerequisites

- JDK 21 (Gradle daemon pinned via `gradle/gradle-daemon-jvm.properties`)
- Android SDK platform 37 (compileSdk), build-tools 36+
- An [ARCore-supported device](https://developers.google.com/ar/devices) with Google Play Services for AR
  (emulators are not useful for marker testing)
- A printed marker: `docs/marker/venue_marker_print_20cm.pdf` at 100 % scale, exactly 20 cm wide,
  mounted flat on a wall

## Build and test

```bash
./gradlew test                       # JVM unit tests (domain + app)
./gradlew connectedDebugAndroidTest  # on-device: Room repositories, ARCore marker acceptance
./gradlew :app:installDebug
```

## Modules

```
:core:domain   Pure Kotlin. Public API, domain models, coordinate math, localization tracker,
               hit validation, destination use cases. No Android / ARCore types.
:core:data     Room persistence + repository implementations (WayfinderData entry point).
:ar:arcore     ARCore adapter: session lifecycle, marker database, ARCore→domain conversions,
               OpenGL ES renderer, pluggable ArLayers (PointSelectionLayer).
:app           Compose screens, ViewModels, manual DI (AppContainer), venue configuration.
```

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) and [docs/COORDINATE_FRAMES.md](docs/COORDINATE_FRAMES.md).

## Dependencies (verified Oct 2026)

AGP 9.4.1 (built-in Kotlin), Kotlin 2.4.21, KSP 2.3.12, Gradle 9.8.2, ARCore 1.57.0,
Compose BOM 2026.09.00, Room 2.8.5, Coroutines 1.11.0, Navigation 2.10.2, Lifecycle 2.11.0.

## Privacy

Camera only. No location permission, no background access, no backend, no accounts. Destinations
are stored locally in Room. Camera frames are never stored.

## Regenerating the marker

`python3 tools/generate_marker.py` (Pillow). If the image or print size changes, update
`DefaultVenue` and use *Venue → Marker was moved…* so old destinations are flagged.

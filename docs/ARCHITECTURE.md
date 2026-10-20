# Architecture

```
app (Compose UI, ViewModels, AppContainer)
 ├── core:data   (Room)          ──┐
 ├── ar:arcore   (ARCore, GL)    ──┼──> core:domain (pure Kotlin contracts + logic)
 └── core:domain                 ──┘
```

Rule: platform AR types stay in `:ar:arcore`. Everything crossing a module boundary is a domain type.

## Domain contracts (to be mirrored 1:1 in Swift)

| Kotlin (`core:domain`) | Purpose | iOS equivalent |
|---|---|---|
| `Vec3`, `Quaternion` (x,y,z,w), `RigidTransform` | geometry | `SIMD3<Double>`, `simd_quatd` |
| `Venue`, `Floor`, `MarkerSpec`, `MarkerMounting` | venue config | same structs |
| `VenueAlignment`, `VenueFrameEstimator` | session↔venue transform | identical math |
| `TrackingInput`, `CameraTracking`, `MarkerObservation` | per-frame AR input | built from `ARFrame`, `ARImageAnchor.isTracked` (FULL) / not tracked but present (LAST_KNOWN) |
| `LocalizationTracker`, `LocalizationStatus`, `LocalizationPolicy` | confidence/state machine | port as-is |
| `HitCandidate`, `HitValidator`, `HitPolicy` | point validation | from `ARRaycastResult` (`.existingPlaneGeometry` → PLANE, `.estimatedPlane` with LiDAR → DEPTH) |
| `Destination`, `DestinationRepository`, `DestinationService` | destinations | SwiftData/Core Data repository |
| `VenueRepository` | venue config persistence | same |

`:core:domain` is plain Kotlin/JVM, so it could alternatively become a Kotlin Multiplatform module
shared with iOS; the native-Swift path only needs the table above and the unit tests as a spec.

## ARCore adapter (`:ar:arcore`)
- `ArSessionController`: owns `Session`, configures it (augmented image DB, horizontal planes,
  auto-focus, depth if supported), runs the GL loop, feeds `LocalizationTracker`, publishes an
  `ArFrameSnapshot` at 10 Hz, reports `ArFailure`s. Lifecycle: `resume(activity)` / `pause()` /
  `close()` (owned by the screen's ViewModel).
- `ArLayer`: feature plug-ins on the GL thread. `PointSelectionLayer` provides the reticle,
  tap hit-testing, validation and a **single** preview anchor (detached on retry/clear/release).
  Milestone 3 adds a `NavigationLayer`.
- Rendering: OpenGL ES 2.0, camera background + procedural meshes (no model files). Venue axes
  are drawn at the marker when localized as a visual sanity check.

## Persistence (`:core:data`)
Tables `venues`, `floors`, `destinations` (venue-frame xyz, frame version, NOCASE unique name per
venue). Schema exported to `core/data/schemas`. Room types are hidden behind `WayfinderData`.

## DI
Manual `AppContainer` (one venue, a few repositories). A DI framework would add more than it saves.

## Threading
GL thread: ARCore calls, tracker, layers. UI collects StateFlows. Taps are queued to the GL thread.

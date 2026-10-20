# Coordinate frames

## AR session frame (transient)
ARCore world / ARKit world. Gravity-aligned (+Y up), metres, **origin and heading arbitrary per
session**. Never persisted. Anchor IDs are never persisted either.

## Venue frame (persistent)
Defined by the physical reference marker (`MarkerSpec`):

- Origin: centre of the marker image.
- +Y: up (opposite gravity).
- +Z: horizontal "forward" of the marker
  - `WALL`: out of the wall, into the room (horizontal projection of the image normal)
  - `FLOOR`: towards the image's top edge (horizontal projection)
- +X = +Y × +Z (right-handed).

Because both frames are gravity aligned, `VenueAlignment` is just `originInSession` + `yawRadians`
(rotation about Y). Marker pitch/roll estimation noise therefore cannot tilt the venue.
Implementation: `core/domain/.../venue/VenueFrame.kt`.

Image pose convention used by `VenueFrameEstimator` (same on ARCore `AugmentedImage.centerPose`
and ARKit `ARImageAnchor.transform`): +X image right, +Z image bottom, +Y outward normal.

Mounting validation: a WALL marker must have |normal·up| ≤ 0.57; a FLOOR marker normal·up ≥ 0.86,
otherwise localization reports `MARKER_ORIENTATION_MISMATCH`.

## Saved destinations
`Destination.positionInVenue` (x, y, z in the venue frame), optional capture heading (venue yaw),
`frameVersion`, capture source and estimated error. y is relative to the marker centre (a floor point
below a chest-height wall marker has y ≈ −1.3).

## Frame versioning
`Venue.frameVersion` identifies the marker's physical placement. Moving or reprinting the marker
requires a new version (*Venue → Marker was moved…*). Destinations with an older version are shown
as "recapture needed" and must not be used for navigation.

## Restoring across sessions
1. New session → `LocalizationTracker.resetForNewSession()` → `NotLocalized(SCAN_MARKER)`.
2. User scans the marker. A **fully tracked** observation within 4 m establishes the alignment.
3. `DestinationLocator.locate(destination, venue, status)` is the only path from a saved point to
   session coordinates. It returns `Located` only for a reliable `Localized` status and a matching
   frame version; otherwise `RequiresRelocalization` or `StaleFrame`.

When saving, the preview anchor position and the localization status are captured in the same AR
frame (`SelectedPoint.localization`), and `DestinationService.create` converts to the venue frame
before anything is written. Screen coordinates are only used as hit-test input, never stored.

## Localization confidence
Error estimate = fix error (5 cm + 2 cm per metre of viewing distance) + drift (2 % of distance
walked) + 15 cm per tracking interruption or relocalization jump. HIGH ≤ 0.25 m, MEDIUM ≤ 0.6 m,
LOW above; > 1.5 m, interruptions > 5 s, app pauses > 5 s, or loss of the marker trackable →
`Lost` until the marker is scanned again. Saving requires HIGH or MEDIUM. These constants
(`LocalizationPolicy`) are starting points and must be tuned with the device tests.

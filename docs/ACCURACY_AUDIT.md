# Localization accuracy audit

Status: code audit + automated tests done. **No physical accuracy measurements exist yet.** Every
accuracy number the app shows (the "±" value) is a *model estimate*, not a measured accuracy. This
document does not claim any accuracy level, and certainly not centimetre-level accuracy, until the
measurements below have been taken.

## 1. Two different things

| | What it means | How we observe it |
|---|---|---|
| **AR tracking stability** | ARCore keeps a consistent local map while the session runs: tracking state, interruptions, map corrections, how much marker detections jitter | Diagnostics overlay ("Diag"), `adb logcat -s IndoorAR-Loc` |
| **Absolute positioning accuracy** | A point the app reports in the venue frame lies at the right physical spot, across sessions | Only from the **accuracy log**: taps on tape-measured or previously saved floor marks |

A stable session can still be wrong in absolute terms, for example with a 2° heading error that
is perfectly stable. Absolute accuracy also depends on the marker fix, the user's distance from the
marker, and the distance walked since the fix.

## 2. Error sources

| Source | Effect | Notes |
|---|---|---|
| Marker position estimate | Offsets the whole venue frame | Grows with viewing distance and angle |
| **Marker heading estimate** | Rotates the frame about the marker: error ≈ heading error × distance from marker | Likely the dominant term far from the marker: 1° → 0.17 m at 10 m, 0.35 m at 20 m. A 20 cm marker gives a short baseline for estimating its normal |
| Odometry drift (VIO) | Error grows with distance walked | Assumed 2 % of distance walked. Not measured |
| ARCore map corrections and relocalization | Sudden shifts of session coordinates | Detected, counted and penalised |
| Hit test / plane height | Error in the tapped point | Usually small on a well-detected floor; worse at grazing angles and far away |
| Print scale | ARCore uses the declared width. A wrong print size shifts the marker's estimated **distance** (origin offset ≈ scale error × viewing distance) | World scale comes from ARCore's visual-inertial odometry, not from the marker |
| Marker moved | Every saved point is wrong | Handled by the frame version |

## 3. Findings and changes

| # | Finding | Change |
|---|---|---|
| F1 | The venue frame was set up from **one** marker detection with HIGH confidence | It now needs ≥ 8 fully tracked detections within 1.5 s, spread ≤ 4 cm / 3°. Otherwise the reason is STABILIZING or UNSTABLE |
| F2 | Oblique and distant views were accepted | Fixes are rejected beyond 60° from the marker normal or 3 m distance, with a specific recovery instruction |
| F3 | The error model ignored heading error × distance from marker | Error = fix + σ_heading × lever arm + drift + penalties. σ_heading = max(1° assumed, observed spread). Confidence now degrades with distance from the marker |
| F4 | One outlier detection could replace the frame immediately | A disagreeing view must itself be stable before it replaces the frame. Until then, the disagreement is shown and the position counts as unreliable. Accepted corrections are recorded (measured drift evidence) |
| F5 | ARCore map corrections were adopted silently | Each one is counted and adds a 0.15 m penalty |
| F6 | Arrival allowed 0.75 m estimated error with a 1 m radius (could fire about 1.6 m away) | Default max error is now 0.5 m, and the policy rejects max error > radius |
| F7 | Constants had no measurement basis | They are marked as assumptions in `LocalizationPolicy`, and an accuracy log exists to calibrate them |
| F8 | No diagnostics | Added a diagnostics overlay, a 1 Hz logcat line, an accuracy log with CSV export, and specific recovery instructions |

Unchanged and confirmed by tests: saved points are stored only in the venue frame; a new session
requires a marker fix before any saved coordinate is used (`DestinationLocator`); a last-known
marker pose never sets up a frame; interruptions over 5 s require a rescan; and while localization
is unreliable, navigation shows no arrow, no deviation and no arrival.

## 4. Scenario assessment

| Scenario | Automated evidence | Needs a device |
|---|---|---|
| 1 Save → restart → relocalize → navigate | `SessionRestartTest` (arbitrary different session frames give the same physical point); `NavigationSessionTest` | Real repeatability: checklist A |
| 2 Several destinations keep their relative positions | Exact under the rigid-transform model (`CoordinateTransformTest`) | Real heading error distorts relative positions in proportion to distance: checklist B |
| 3 Marker from different angles and distances | `FrameValidationTest` (oblique, far, jittery, outlier) | Whether 60° / 3 m / 8 frames are the right limits: checklist C |
| 4 Loss of marker or tracking | `LocalizationTrackerTest`, `NavigationSessionTest` (no guidance or arrival when unreliable) | Real interruptions: checklist D |
| 5 Walking off route | `NavigationSessionTest` deviation and re-route tests | Checklist E |
| 6 Arrival rule | `NavigationSessionTest` (radius configurable, error ≤ 0.5 m, dwell, through-wall) | Checklist F |
| 7 Apparent drift approaching from different directions | Accuracy log records approach heading; the spread per reference is reported | Checklist G |

## 5. Can a single marker reliably restore the venue frame?

- **Near the marker (within a few metres): likely yes.** The frame definition is deterministic, and the
  stable-fix gate rejects bad detections. This still has to be confirmed with checklist A.
- **Far from the marker: not demonstrated, and structurally limited.** Heading uncertainty from a single
  20 cm marker is multiplied by distance, and drift accumulates with walking. Both grow without bound
  until the user returns to the marker. The app now reports this honestly (confidence drops, and
  navigation suspends when unreliable), but **no UI change can fix the underlying geometry**.

### Proposed improvement: several surveyed markers per venue
1. **Model.** `Venue.markers: List<MarkerSpec + poseInVenue>`. Marker 0 defines the frame; other markers
   have surveyed venue poses. ARCore can hold many images in one database and track several at once.
2. **Survey mode.** After fixing on marker 0, walk to marker k and record its venue pose. Repeat
   ≥ 3 passes in both directions and store the mean, plus the spread as that marker's uncertainty.
   Reject surveys with spread > 5 cm.
3. **Tracking.** A stable fix on *any* marker re-establishes the frame from that marker's surveyed pose.
   The lever arm becomes distance to the *nearest* fixed marker, and drift resets at each marker.
4. **Placement.** Put a marker at each main junction, roughly every 10–15 m. Navigation can then
   bound the expected error along the route and tell the user to look at the next marker.
5. **Heading.** Use the heading between two markers several metres apart instead of one marker's normal.
   For example, a 0.1 m position error over a 10 m baseline is 0.6°, versus an assumed 1°+ from a 20 cm marker.
6. **Cheap interim step.** Print the marker larger (A3, about 30 cm), set `physicalWidthMeters` to match,
   and bump the frame version. The physical-device checklist will show whether heading error improves.

This stays local-only: no backend or cloud anchors.

## 6. Physical-device checklist

Setup:
- Print the marker at 100 % and measure it (record the actual width).
- Mount it flat, about 1.3–1.5 m high, in even light.
- Lay tape crosses on the floor at about 2, 5, 10 and 15 m from the marker, including one around a corner.
- Measure each cross from the marker with a tape measure and laser:
  - **z** = perpendicular distance out from the wall.
  - **x** = distance along the wall, **positive to the LEFT** when you stand with your back to the marker
    (the frame is right-handed with +Y up).
  - **y** = −(height of the marker centre above the floor).
- Enter the crosses in `app/src/main/assets/reference_points.json`, then rebuild and install.

How to measure: in *Add destination in AR*, tap the cross, press **Check**, and choose the reference.
Turn on **Diag** to watch the fix. Collect logs with:

```
adb logcat -s IndoorAR-Loc IndoorAR-Accuracy
adb shell run-as com.indoorar.wayfinder cat files/accuracy_log.csv > accuracy_log.csv
```

Record the device model, ARCore version, lighting, marker width as printed, and the date.

**A. Session-restart repeatability**
1. Save destinations on every cross (session 1).
2. Force-stop the app and reopen. Rescan the marker, tap each cross, and press **Check** against its saved
   destination. Repeat for 5 sessions, starting from different spots and approach directions.
3. Report the repeatability median, p90 and max, and the spread per point.

**B. Relative consistency**
- From the CSV, compare the pairwise distances between measured crosses with the tape-measured distances.
- Report the error against distance between points and against distance from the marker.
  An error growing linearly with distance from the marker indicates heading error.

**C. Marker angle and distance**
- Fix from 0.5, 1, 2 and 3 m at 0°, 30°, 45° and 60°.
- After each fix, check the 10 m cross without walking further than needed.
- Record the time to fix (diagnostic frames), the fix spread, the rejections, and the 10 m error.
- Decide whether `maxViewingAngle` and `maxFixDistance` need tightening.

**D. Loss and interruption**
- Cover the camera for 2 s and then for 6 s while navigating.
- Shake the phone to cause motion blur.
- Background the app for 3 s and then for 10 s.
- Expected: guidance hides with a warning, no arrival or deviation is reported, a rescan is required
  after more than 5 s, and guidance resumes only after recovery. Note any case where the arrow showed
  while the warning was up (that would be a defect).

**E. Deviation**
- Navigate, then deliberately take the wrong corridor for more than 5 m.
- Expected: an off-route warning within about 3–5 s, then a re-route if a path exists.
- Record the time to detection and any false off-route events while walking correctly.

**F. Arrival**
- Approach a destination slowly from 5 m away, five times.
- Record the tape distance from the cross when the dialog appears, the localization ± at that moment,
  and whether the dialog appeared twice (it must not).
- Repeat with the radius set to 2 m in `AppContainer.navigationPolicy`.

**G. Approach-direction drift**
- For the 10 m and 15 m crosses, approach each from four directions in one session without rescanning,
  and **Check** each time.
- The spread per reference in the Accuracy log screen shows apparent drift.
- Also note the "frame corrections, last …" value when you return to the marker: that is the
  accumulated drift actually measured by re-observing the marker.

**H. Calibrate the error model**
- After A–G, look at "within the app's own estimate" in the Accuracy log screen.
  - Well below about 68 %: the model is optimistic. Increase `baseYawSigmaRadians` and/or `driftPerMeterWalked`.
  - Close to 100 % with large estimates: the model is pessimistic.
- Change the values in `LocalizationPolicy` only on the basis of these measurements.

## 7. Remaining limitations
- No measured accuracy yet. All ± values are model estimates.
- Single-marker heading uncertainty limits accuracy far from the marker (section 5).
- Single floor only. The user is assumed to be on the destination's floor.
- The floor height used for guidance comes from detected planes or the destination's saved height.
  On uneven or multi-level floors it can be wrong.
- Smoothing time constants (0.25 s alignment, 0.15–0.2 s guidance) are not tuned on device.
- The database migration v1 → v2 is covered by an instrumented test that has **not yet run on a device** at the time of writing.

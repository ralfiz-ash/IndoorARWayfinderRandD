# Physical-device validation (manual)

Automated tests cover the logic; these checks can only be done on a real device with the printed
marker. Record device model, ARCore version, lighting and results for each run.

Setup: print `docs/marker/venue_marker_print_20cm.pdf` at 100 %; verify 20.0 cm with a ruler;
mount flat on a wall at ~1.3 m in even light.

## M1 — marker recognition
1. Open *Add destination in AR*. From 0.5, 1, 2 and 3 m and at 0°, 30°, 60° viewing angle, point at
   the marker. Expected: status turns green ("Localized ±…"), venue axes appear at the marker
   (blue axis pointing out of the wall, green up).
2. Repeat in dim light and with glare. Note failures.
3. Mount the marker flat on a table (wrong mounting). Expected: "Marker orientation does not match".

## M2 — point selection
1. Before scanning the marker, tap the floor. Expected: "Scan the venue marker first".
2. After localization, sweep the floor until "Step 3" shows. Tap a wall, a table and the floor.
   Expected: wall rejected (not horizontal), table accepted as "raised horizontal surface",
   floor accepted as "floor plane". A blue pin appears at the tapped point.
3. Retry: pin disappears. Save with an empty name / duplicate name: errors shown.

4. Leave the marker out of view for > 12 s: "Marker not found yet…" troubleshooting text.
   After localizing, point at the ceiling for > 15 s: "Floor not detected yet…".

## M3 — saved-point repeatability
1. Put tape crosses on the floor at 2, 5 and 10 m from the marker. Save each as a destination.
2. Close the app (swipe away) and reopen *Add destination in AR*. Before scanning the marker the
   banner must say the saved destinations are hidden and no yellow markers may appear.
3. Scan the marker. Yellow markers must appear on the tape crosses. Measure the offset between
   each yellow ring centre and its cross with a tape measure.
4. Re-save the same crosses with new names.
5. Compare venue coordinates (home list shows x/z) of each pair. Record the deltas.
   Target for MVP: ≤ 0.3 m at 5 m. Repeat 5×.

## M4 — tracking interruption
1. Localize, then cover the camera for 2 s. Expected: "Tracking interrupted", then back to
   localized with a larger error. Cover for > 5 s: "Localization lost … scan the marker again".
2. Background the app for > 5 s and return: must require a rescan.
3. Walk 30 m away from the marker without rescanning: confidence should degrade (amber).

## M5 (milestone 3) — navigation after AR session restart
To be defined with the navigation screen.
